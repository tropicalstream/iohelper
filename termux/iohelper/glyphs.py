"""'Graphics' for a text-only relay.

Research result: the RayNeo relay forwards only the notification title and
body - custom icons, bigtext/messaging styles and images all change nothing
(the payload is byte-identical). What DOES render is any Basic-Multilingual-
Plane character, so pictures are composed from glyphs: icons, bars, arrows.
Non-BMP emoji are stripped by display.sanitize(); everything here is BMP.
"""
import re

ICON = {
    "sun": "☀", "cloud": "☁", "rain": "☂", "storm": "⚡",
    "snow": "☃", "fog": "≈", "ok": "✓", "no": "✗",
    "warn": "⚠", "timer": "⏰", "star": "★", "star_o": "☆",
    "heart": "♥", "flag": "⚑", "gear": "⚙", "pin": "⌖",
    "phone": "☎", "mail": "✉", "up": "↑", "down": "↓",
    "right": "→", "left": "←", "ne": "↗", "se": "↘",
    "bullet": "•", "dot": "·", "music": "♪", "info": "ⓘ",
    "clock": "◷", "cal": "▦", "car": "⛟", "coffee": "☕",
}

_WEATHER = [
    (r"thunder|storm|lightning", "storm"), (r"snow|flurr|sleet", "snow"),
    (r"rain|shower|drizzle|wet", "rain"), (r"fog|mist|haze|smoke", "fog"),
    (r"cloud|overcast", "cloud"), (r"sun|clear|fair|bright", "sun"),
]


def weather_icon(text):
    low = (text or "").lower()
    for pat, key in _WEATHER:
        if re.search(pat, low):
            return ICON[key]
    return ""


def bar(fraction, width=10, filled="▓", empty="░"):
    """A progress/level bar: bar(0.7) -> '▓▓▓▓▓▓▓░░░'."""
    fraction = max(0.0, min(1.0, float(fraction)))
    n = int(round(fraction * width))
    return filled * n + empty * (width - n)


def card(icon, text):
    """Prefix a body with an icon (by name, or a literal glyph)."""
    g = ICON.get(icon, icon or "")
    return f"{g} {text}".strip()


def trend(delta):
    return ICON["up"] if delta > 0 else ICON["down"] if delta < 0 else ICON["right"]


def is_bmp(text):
    return all(ord(c) < 0x10000 for c in text)


def decorate(answer):
    """Light automatic decoration of an LLM answer: a weather glyph when the
    reply is about weather. Never changes the words."""
    low = answer.lower()
    if re.search(r"(°|\bdegrees\b|\bweather\b|\bforecast\b|\brain\b|\bsunny\b|\bcloudy\b|\bsnow\b)", low):
        g = weather_icon(low)
        if g and not answer.startswith(g):
            return f"{g} {answer}"
    return answer
