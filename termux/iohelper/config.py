"""Configuration: one JSON file, dotted access, thread-safe, atomic save.

Secrets live in the same file (plaintext) - this is a personal, local app; the
portal that edits them binds to localhost by default. Environment variables
override the file for the keys people commonly export (GROQ_API_KEY,
GEMINI_API_KEY, SERPAPI_KEY, CLAUDE_BIN, ADB, IOHELPER_DEVICE).
"""
import copy
import json
import os
import threading

APP_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_PATH = os.environ.get("IOHELPER_CONFIG", os.path.join(APP_DIR, "config.json"))

DEFAULTS = {
    "device": {
        # adb reaches the PHONE (never the glasses); the companion app relays.
        "adb": r"C:\platform-tools\adb.exe" if os.name == "nt" else "adb",
        "serial": "",
        "title": "",                   # card title line; empty = capitalised wake word
        "header": "",                  # app-name line; empty = same as title
        "use_card_app": True,          # post via com.iohelper.card for a real header
        "tag": "iohelper_reply",       # one stable tag: reposts update in place
        "body_limit": 115,             # >115 is cut, >=131 never renders (measured)
        "settle": 6,                   # seconds to wait for the render receipt
    },
    "wake": {
        "trigger": "jarvis",
        "soft_mangles": ["travis", "jervis", "jarvise", "jarvas", "darvis"],
        "source": "alwayson",          # alwayson (hands-free) | assistant (crown)
        "role": "any",                 # host/guest labels are unreliable -> any
        "arm_window": 45,              # s: bare wake word -> question may arrive late
        "fast_window": 6,              # s: within this, no shape gate on follow-up
        "soft_arm_window": 25,
        "dedup_window": 45,
        "min_chars": 4,
    },
    "llm": {
        "backend": "groq",             # groq | gemini | claude | openai
        "groq_api_key": "",
        "groq_model": "",              # empty = auto-pick newest free 70B
        "gemini_api_key": "",
        "gemini_model": "",            # empty = auto-pick newest free Flash
        "claude_bin": "",              # empty = auto-resolve
        "claude_model": "claude-sonnet-5",
        "claude_effort": "high",
        "openai_base_url": "http://127.0.0.1:11434/v1",   # Ollama / any compatible
        "openai_api_key": "",
        "openai_model": "",
        "timeout": 45,
        "brief": ("Reply in ONE short sentence, under 90 characters, plain text "
                  "only - no lists, no markdown, no line breaks. Be terse."),
    },
    "search": {"serpapi_key": "", "location": "", "mode": "auto"},
    "calendar": {"enabled": True, "hours": 24},
    "proactive": {"enabled": True, "lead_minutes": 10, "interval": 45,
                  "start_ping": False, "digest_hours": 0},
    "display": {"repost_on_wake": True, "repost_window": 120,
                # crown channel only: RayNeo's own streamed answer owns the screen,
                # so hold our card until VOICE_ASSISTANT has been quiet this long
                # and then a little longer so theirs can be read/dismissed
                "defer_after_native": True, "native_quiet": 3, "native_dwell": 8,
                "native_max_wait": 30, "native_min_hold": 10},
    "portal": {"host": "127.0.0.1", "port": 8765, "token": ""},
}

SECRETS = {"llm.groq_api_key", "llm.gemini_api_key", "llm.openai_api_key",
           "search.serpapi_key", "portal.token"}

ENV_OVERRIDES = {
    "llm.groq_api_key": "GROQ_API_KEY",
    "llm.gemini_api_key": "GEMINI_API_KEY",
    "search.serpapi_key": "SERPAPI_KEY",
    "llm.claude_bin": "CLAUDE_BIN",
    "device.adb": "ADB",
    "device.serial": "IOHELPER_DEVICE",
}


def _merge(base, over):
    out = copy.deepcopy(base)
    for k, v in (over or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


class Config:
    def __init__(self, path=CONFIG_PATH):
        self.path = path
        self._lock = threading.RLock()
        self._data = copy.deepcopy(DEFAULTS)
        self.load()

    # -- persistence --------------------------------------------------------
    def load(self):
        with self._lock:
            try:
                with open(self.path, encoding="utf-8") as fh:
                    self._data = _merge(DEFAULTS, json.load(fh))
            except (OSError, json.JSONDecodeError):
                self._data = copy.deepcopy(DEFAULTS)

    def save(self):
        with self._lock:
            os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(self._data, fh, indent=2)
            os.replace(tmp, self.path)

    # -- access ---------------------------------------------------------------
    def get(self, dotted, default=None):
        env = ENV_OVERRIDES.get(dotted)
        if env and os.environ.get(env):
            return os.environ[env]
        with self._lock:
            cur = self._data
            for part in dotted.split("."):
                if not isinstance(cur, dict) or part not in cur:
                    return default
                cur = cur[part]
            return copy.deepcopy(cur)

    def set(self, dotted, value):
        with self._lock:
            cur = self._data
            parts = dotted.split(".")
            for part in parts[:-1]:
                cur = cur.setdefault(part, {})
            cur[parts[-1]] = value

    def update(self, mapping):
        """Deep-merge a nested dict (as posted by the portal), coercing values
        to the default's type. Empty strings for secrets are ignored so the UI
        can leave an already-set key untouched."""
        with self._lock:
            for section, vals in (mapping or {}).items():
                if not isinstance(vals, dict) or section not in DEFAULTS:
                    continue
                for k, v in vals.items():
                    key = f"{section}.{k}"
                    if key in SECRETS and (v is None or v == ""):
                        continue
                    if k in DEFAULTS[section]:
                        want = type(DEFAULTS[section][k])
                        try:
                            if want is bool and isinstance(v, str):
                                v = v.lower() in ("1", "true", "yes", "on")
                            elif want in (int, float) and not isinstance(v, bool):
                                v = want(v)
                            elif want is list and isinstance(v, str):
                                v = [x.strip().lower() for x in v.split(",") if x.strip()]
                            elif want is str and v is None:
                                v = ""
                        except (TypeError, ValueError):
                            continue
                    self.set(key, v)

    def snapshot(self):
        with self._lock:
            return copy.deepcopy(self._data)

    def public(self):
        """Config for the portal: secrets blanked, plus which ones are set."""
        d = self.snapshot()
        flags = {}
        for key in SECRETS:
            sec, k = key.split(".")
            flags[key] = bool(self.get(key))
            d[sec][k] = ""
        d["_secrets_set"] = flags
        d["_path"] = self.path
        return d


CFG = Config()
