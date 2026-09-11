"""LLM backends behind one call: ask(prompt) -> one short sentence.

  groq    - api.groq.com, OpenAI-compatible, free tier (30 RPM). Default model
            auto-picks the newest 70B-class instruct model the key can see.
  gemini  - generativelanguage.googleapis.com (AI Studio key, free tier).
            Default model auto-picks the newest 'flash' that supports
            generateContent (model ids move fast; discovery beats hardcoding).
  claude  - Claude Code CLI headless (`claude -p`), your Claude plan via OAuth,
            no API key. Auto-locates the CLI (it is not on the service's PATH).
  openai  - any OpenAI-compatible endpoint (Ollama, LM Studio, a Hermes
            gateway) via base_url + key + model.
Model lists are fetched live for the portal's dropdowns.
"""
import json
import os
import re
import shutil
import ssl
import subprocess
import urllib.error
import urllib.request

from .config import CFG

# Python 3.13 enables VERIFY_X509_STRICT, which rejects some HTTPS-interception
# roots (Avast on this machine). Clear only that flag; the chain and hostname
# are still verified.
_SSL = ssl.create_default_context()
_SSL.verify_flags &= ~ssl.VERIFY_X509_STRICT

GROQ_URL = "https://api.groq.com/openai/v1"
GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta"
EFFORT_LEVELS = ["low", "medium", "high", "xhigh", "max"]


class LLMError(Exception):
    pass


def _http(url, data=None, headers=None, timeout=30):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, headers={
        "Content-Type": "application/json", "User-Agent": "iohelper", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_SSL) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        try:
            detail = json.load(e)
            msg = (detail.get("error") or {}).get("message") if isinstance(detail, dict) else None
        except Exception:
            msg = None
        raise LLMError(f"HTTP {e.code}: {msg or e.reason}")
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise LLMError(f"network: {getattr(e, 'reason', e)}")


# ---- model discovery --------------------------------------------------------
_GEM_VER = re.compile(r"gemini-(\d+(?:\.\d+)?)")


def list_models(backend):
    """Live model ids the configured key can use (empty list on any failure)."""
    try:
        if backend == "groq":
            key = CFG.get("llm.groq_api_key")
            if not key:
                return []
            d = _http(f"{GROQ_URL}/models", headers={"Authorization": f"Bearer {key}"})
            return sorted(m["id"] for m in d.get("data", []) if m.get("id"))
        if backend == "gemini":
            key = CFG.get("llm.gemini_api_key")
            if not key:
                return []
            d = _http(f"{GEMINI_URL}/models?pageSize=200&key={key}")
            out = []
            for m in d.get("models", []):
                if "generateContent" in (m.get("supportedGenerationMethods") or []):
                    out.append(m["name"].split("/", 1)[-1])
            return sorted(out)
        if backend == "openai":
            base = CFG.get("llm.openai_base_url").rstrip("/")
            key = CFG.get("llm.openai_api_key")
            hdr = {"Authorization": f"Bearer {key}"} if key else {}
            d = _http(f"{base}/models", headers=hdr, timeout=8)
            return sorted(m["id"] for m in d.get("data", []) if m.get("id"))
        if backend == "claude":
            return ["claude-sonnet-5", "claude-opus-5", "claude-fable-5-1",
                    "claude-haiku-4-5-20251001", "sonnet", "opus"]
    except LLMError:
        return []
    return []


def pick_model(backend, models):
    """Choose a sensible default from a live list (newest free-tier workhorse)."""
    if backend == "gemini":
        cands = [m for m in models if m.startswith("gemini-") and "flash" in m
                 and not re.search(r"lite|preview|exp|image|tts|live|audio|native|embed|8b|thinking", m)]
        cands = cands or [m for m in models if "flash" in m]
        if cands:
            return max(cands, key=lambda m: float(_GEM_VER.search(m).group(1))
                       if _GEM_VER.search(m) else 0)
        return "gemini-2.5-flash"
    if backend == "groq":
        # Groq's free catalog churns; rank by fit for a terse voice assistant
        # (quality, then speed). Never fall back to speech/guard/Arabic/agentic
        # models - "allam-2-7b" was once picked purely by alphabetical order.
        bad = re.compile(r"whisper|tts|orpheus|guard|safeguard|embed|allam|compound|"
                         r"prompt-guard|vision", re.I)
        chat = [m for m in models if not bad.search(m)]
        ranked = [
            r"^llama-3\.3-70b-versatile$", r"llama-4-.*instruct", r"gpt-oss-120b",
            r"qwen3\.\d+-.*b", r"qwen.*b", r"gpt-oss-20b", r"llama-3\.1-8b-instant",
            r"deepseek", r"mixtral", r"gemma", r"llama",
        ]
        for pat in ranked:
            hits = [m for m in chat if re.search(pat, m, re.I)]
            if hits:
                # newest version first when several match (qwen3.8 over qwen3.6)
                return max(hits, key=lambda m: [float(x) for x in re.findall(r"\d+\.\d+|\d+", m)] or [0])
        return chat[0] if chat else "openai/gpt-oss-120b"
    if backend == "openai":
        return models[0] if models else ""
    return "claude-sonnet-5"


def effective_model(backend):
    key = {"groq": "llm.groq_model", "gemini": "llm.gemini_model",
           "claude": "llm.claude_model", "openai": "llm.openai_model"}[backend]
    m = CFG.get(key)
    if m:
        return m
    picked = pick_model(backend, list_models(backend))
    if picked:
        CFG.set(key, picked)          # remember the pick (not saved until portal save)
    return picked


# ---- claude cli -------------------------------------------------------------
def resolve_claude_bin():
    cfg = CFG.get("llm.claude_bin")
    if cfg:
        return cfg
    found = shutil.which("claude")
    if found:
        return found
    home = os.path.expanduser("~")
    cands = ([os.path.join(home, ".local", "bin", "claude.exe"),
              os.path.join(os.environ.get("APPDATA", ""), "npm", "claude.cmd"),
              os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "claude", "claude.exe")]
             if os.name == "nt" else
             [os.path.join(home, ".local", "bin", "claude"),
              os.path.join(home, ".claude", "local", "claude"),
              "/opt/homebrew/bin/claude", "/usr/local/bin/claude", "/usr/bin/claude"])
    for c in cands:
        if c and os.path.exists(c):
            return c
    return "claude"


# ---- the call ----------------------------------------------------------------
def ask(prompt, system=None, backend=None, timeout=None):
    backend = backend or CFG.get("llm.backend")
    system = system if system is not None else CFG.get("llm.brief")
    timeout = timeout or CFG.get("llm.timeout")
    model = effective_model(backend)

    if backend in ("groq", "openai"):
        if backend == "groq":
            base, key = GROQ_URL, CFG.get("llm.groq_api_key")
            if not key:
                raise LLMError("Groq API key not set (portal -> LLM)")
        else:
            base, key = CFG.get("llm.openai_base_url").rstrip("/"), CFG.get("llm.openai_api_key")
        if not model:
            raise LLMError(f"no {backend} model available")
        body = {
            "model": model, "temperature": 0.3,
            # Reasoning models (gpt-oss, qwen3, deepseek) spend hidden reasoning
            # tokens from the same budget: 200 left them empty or cut to "I don't".
            "max_tokens": 1024,
            "messages": ([{"role": "system", "content": system}] if system else [])
            + [{"role": "user", "content": prompt}],
        }
        if re.search(r"gpt-oss|qwen|deepseek|r1", model, re.I):
            body["reasoning_effort"] = "low"        # one-sentence answers need none
        d = _http(f"{base}/chat/completions", body,
                  headers={"Authorization": f"Bearer {key}"} if key else {}, timeout=timeout)
        try:
            msg = d["choices"][0]["message"]
            text = (msg.get("content") or "").strip()
        except (KeyError, IndexError, TypeError):
            raise LLMError(f"unexpected {backend} response")
        if not text:
            raise LLMError(f"{model} returned no visible text (reasoning ate the budget?)")
        return text

    if backend == "gemini":
        key = CFG.get("llm.gemini_api_key")
        if not key:
            raise LLMError("Gemini API key not set (portal -> LLM)")
        if not model:
            raise LLMError("no Gemini model available")
        body = {"contents": [{"role": "user", "parts": [{"text": prompt}]}],
                "generationConfig": {"temperature": 0.3, "maxOutputTokens": 400}}
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        d = _http(f"{GEMINI_URL}/models/{model}:generateContent?key={key}", body,
                  timeout=timeout)
        try:
            parts = d["candidates"][0]["content"]["parts"]
            text = " ".join(p.get("text", "") for p in parts if not p.get("thought")).strip()
        except (KeyError, IndexError, TypeError):
            reason = (d.get("candidates") or [{}])[0].get("finishReason") if isinstance(d, dict) else None
            raise LLMError(f"Gemini returned no text ({reason or 'blocked/empty'})")
        if not text:
            raise LLMError("Gemini returned empty text")
        return text

    if backend == "claude":
        cmd = [resolve_claude_bin(), "-p", f"{system}\nQuestion: {prompt}" if system else prompt,
               "--output-format", "text"]
        if model:
            cmd += ["--model", model]
        effort = CFG.get("llm.claude_effort")
        if effort in EFFORT_LEVELS:
            cmd += ["--effort", effort]
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, errors="replace",
                               timeout=timeout)
        except FileNotFoundError:
            raise LLMError("claude CLI not found - install Claude Code and sign in, "
                           "or set llm.claude_bin")
        except subprocess.TimeoutExpired:
            raise LLMError(f"claude timed out after {timeout}s")
        if r.returncode != 0:
            raise LLMError(f"claude error: {(r.stderr or r.stdout).strip()[:200]}")
        return r.stdout.strip()

    raise LLMError(f"unknown backend {backend!r}")
