"""Entry point: runs the assistant, the proactive pusher and the web portal as
supervised threads in ONE process (so a single icon starts everything and one
`kill` stops it). A thread that dies is restarted after a short pause.

    python -m iohelper                 # run everything
    python -m iohelper ask "text"      # one-shot through the full pipeline
    python -m iohelper send "text"     # put text on the glasses
    python -m iohelper models groq     # list models the key can use
    python -m iohelper replay LOG      # score the wake gate against a log
"""
import sys
import threading
import time

from . import __version__, assistant, display, llm, portal, proactive, search, state
from .config import CFG


def _supervise(name, target, stop_event):
    def runner():
        while not stop_event.is_set():
            try:
                target(stop_event)
            except Exception as e:
                state.log(f"{name} crashed: {e!r} - restarting in 5s")
                stop_event.wait(5)
            else:
                break
    t = threading.Thread(target=runner, name=name, daemon=True)
    t.start()
    return t


def _startup_checks():
    backend = CFG.get("llm.backend")
    state.set_status(backend=backend)
    try:
        model = llm.effective_model(backend)
        state.set_status(model=model)
        state.log(f"llm: backend={backend} model={model or '(none - set a key in the portal)'}")
    except Exception as e:
        state.log(f"llm: model discovery failed: {e!r}")
    if search.available():
        acct = search.account()
        if acct.get("error"):
            state.log(f"serpapi: key rejected - {acct['error']}")
            state.set_status(serpapi=f"error: {acct['error']}"[:60])
        else:
            left = acct.get("total_searches_left")
            state.log(f"serpapi: plan={acct.get('plan_name')} searches_left={left}")
            state.set_status(serpapi=f"{acct.get('plan_name')} / {left} left")
    else:
        state.set_status(serpapi="no key")
    display.ensure_connected()


def run_all():
    import os
    state.log(f"iohelper {__version__} starting  config={CFG.path}")
    if not os.path.exists(CFG.path):
        CFG.save()                      # first run: materialise the defaults
        state.log("wrote default config.json - set your keys in the portal")
    stop = threading.Event()
    # Portal FIRST and unconditionally: the startup checks talk to adb and the
    # LLM providers, which can block for tens of seconds when unreachable.
    threads = [_supervise("portal", portal.serve, stop)]
    threading.Thread(target=_startup_checks, name="startup", daemon=True).start()
    threads += [
        _supervise("assistant", assistant.run, stop),
        _supervise("proactive", proactive.run, stop),
    ]
    try:
        while any(t.is_alive() for t in threads):
            time.sleep(1)
    except KeyboardInterrupt:
        state.log("stopping")
        stop.set()


def replay(path):
    """Score the wake gate against a captured log (lines with 'heard: "..."')."""
    import re
    from . import wake
    trig = CFG.get("wake.trigger")
    st, now, fired, ignored, soft = {}, 0.0, 0, 0, 0
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = re.search(r'heard: "(.*)"', line)
            if not m:
                continue
            now += 5
            action, q = wake.classify_wake(trig, m.group(1), st, now,
                                           soft_list=CFG.get("wake.soft_mangles"))
            if action.startswith("fire"):
                fired += 1
                print(f"FIRE {'(soft) ' if 'soft' in action else ''}{q!r}")
            elif action == "soft-arm":
                soft += 1
            else:
                ignored += 1
    print(f"\nwake={trig!r}  fired={fired}  soft_arms={soft}  ignored={ignored}")


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] == "run":
        return run_all()
    cmd, rest = argv[0], argv[1:]
    if cmd == "ask":
        res = assistant.ask(" ".join(rest), deliver="--no-send" not in rest)
        print(res)
    elif cmd == "send":
        display.ensure_connected()
        print(display.deliver(" ".join(rest)))
    elif cmd == "models":
        b = rest[0] if rest else CFG.get("llm.backend")
        models = llm.list_models(b)
        print("\n".join(models) or "(none - key missing or provider unreachable)")
        if models:
            print(f"\nsuggested default: {llm.pick_model(b, models)}")
    elif cmd == "replay" and rest:
        replay(rest[0])
    elif cmd == "portal":
        stop = threading.Event()
        try:
            portal.serve(stop)
        except KeyboardInterrupt:
            stop.set()
    else:
        print(__doc__)
