"""iohelper - a standalone voice assistant for RayNeo iO glasses.

Listens (read-only) to the glasses' transcripts on the paired phone over adb,
answers with Groq / Gemini / Claude (OAuth) plus SerpApi live data, and puts
the reply on the glasses as a notification card. Configured from a local web
portal. Designed to coexist with RayNeo's own voice assistant: it never touches
the companion app, never writes to its voice channel, and uses its own
notification identity.
"""
__version__ = "1.0.0"
