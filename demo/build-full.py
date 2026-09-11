#!/usr/bin/env python3
"""Generate video-full.html: the whole feature set, one timeline.

Takes the proven video.html stage - deterministic seek(t), the phosphor stack -
and swaps in a longer scene list. Every card shown here is one the device
actually produced during testing; nothing is invented for the demo.
"""
import pathlib
import re
import shutil

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE / "video.html"
DST = HERE / "video-full.html"

TIMELINE = r"""var S=[
{id:'title',dur:5.0},

{id:'weather',dur:9.0,foot:'GPS, not a typed-in city — the phone knows where it is.',
 caps:[[0,'Click the crown and ask. The wake word goes at the <b>end</b> — the glasses clip the start.'],
       [4.6,'No city was named. It used the phone’s own position.']],
 logs:[[0.3,'listening (wake word: jarvis)',''],[1.4,'heard: what is the weather right now, Jarvis?',''],
       [3.0,'loc: Sproul Plaza, UC Berkeley','g'],[4.4,'+ live search','g']],
 cards:[[5.6,'Weather','☀ Sunny, 64 °F in Oakland.']],
 st:[[0,'listening'],[3.0,'thinking'],[5.6,'listening']]},

{id:'where',dur:8.0,foot:'Street level, because "you are in Oakland" is not an answer on a street corner.',
 caps:[[0,'Location comes from the newest fix the phone already has — it never waits on GPS.'],
       [4.2,'A street address, not coordinates.']],
 logs:[[0.4,'heard: what is my current location, Jarvis?',''],[2.0,'geocode: reverse','g']],
 cards:[[3.6,'Location','⌖ You’re at Sproul Plaza, UC Berkeley, CA.']],
 st:[[0,'thinking'],[3.6,'listening']]},

{id:'directions',dur:9.0,foot:'Route and time from a live lookup, with the road actually named.',
 caps:[[0,'Routes are fetched, not recalled — and the origin is where you are standing.'],
       [4.8,'Twenty-six minutes, by a named road.']],
 logs:[[0.4,'heard: how long is the drive to San Francisco, Jarvis?',''],
       [1.8,'+ directions (origin = GPS)','g'],[3.6,'groq: 26 minutes via I-80 W','g']],
 cards:[[4.6,'Commute','→ 26 minutes via I‑80 W.']],
 st:[[0,'thinking'],[4.6,'listening']]},

{id:'local',dur:8.5,foot:'Local answers carry the street address, not just a name.',
 caps:[[0,'"Near me" resolves against the phone’s position.'],
       [4.4,'One best match with its address — the address is the point.']],
 logs:[[0.4,'heard: best restaurant near me, Jarvis?',''],[2.0,'+ google_local','g']],
 cards:[[4.2,'Nearby','⌖ ACRE Kitchen & Bar, 1 Kaiser Plaza, Oakland — 0.4 mi']],
 st:[[0,'thinking'],[4.2,'listening']]},

{id:'facts',dur:7.5,foot:'Looked up, not remembered: the model would answer this from stale memory.',
 caps:[[0,'Ordinary questions get a live search rather than the model’s recollection.']],
 logs:[[0.4,'heard: how tall is the Transamerica Pyramid, Jarvis?',''],[2.0,'+ live search','g']],
 cards:[[3.8,'Answer','The Transamerica Pyramid is 260 m (853 ft) tall.']],
 st:[[0,'thinking'],[3.8,'listening']]},

{id:'markets',dur:9.0,foot:'Markets get a glyph for direction: ▲ up, ▼ down, $ when flat.',
 caps:[[0,'Prices move, so they are always looked up.'],
       [4.8,'The glyph carries the direction before you read the number.']],
 logs:[[0.4,'heard: price of a barrel of oil, Jarvis?',''],[1.8,'+ live search','g']],
 cards:[[3.4,'Markets','$ About $80 per barrel.'],
        [6.2,'Markets','▲ Nasdaq up 0.8% at 20,140.']],
 st:[[0,'thinking'],[3.4,'listening']]},

{id:'sports',dur:7.0,foot:'Scores, headlines and opening hours all take the same path.',
 caps:[[0,'Only the glyph changes — ⚑ for a score, ▤ for news.']],
 logs:[[0.4,'heard: who won the Warriors game, Jarvis?',''],[1.8,'+ live search','g']],
 cards:[[3.2,'Sports','⚑ Golden State Warriors won, 118–110.'],
        [5.2,'News','▤ Bay Bridge reopens after overnight closure.']],
 st:[[0,'thinking'],[3.2,'listening']]},

{id:'spotify',dur:9.0,foot:'A spoken name becomes an exact track — app credentials only, no user login.',
 caps:[[0,'Ask for music and the name is resolved to a real track, not guessed at.'],
       [5.0,'Playing on the phone.']],
 logs:[[0.4,'heard: play Bob Dylan, Jarvis',''],[2.0,'spotify: search','g'],
       [3.6,'track: 3AhXZa8sUQht0UEdBJgpGc','k']],
 cards:[[4.8,'Music','▶ Like a Rolling Stone — Bob Dylan']],
 st:[[0,'thinking'],[4.8,'listening']]},

{id:'sonos',dur:10.0,foot:'Sent straight to the speaker over the local network — no cloud in the path.',
 caps:[[0,'Name a speaker and it plays there instead.'],
       [5.6,'Spotify’s own API refuses to target a Sonos, so this talks to the speaker directly.']],
 logs:[[0.4,'heard: play Dead Can Dance on bedroom Sonos, Jarvis',''],
       [2.0,'sonos: found 2 speakers','g'],[3.2,'sonos: sid=12 sn=20','k'],
       [4.4,'SetAVTransportURI -> Play','g']],
 cards:[[5.4,'Music','▶ The Host of Seraphim — Dead Can Dance (Bedroom Sonos)']],
 st:[[0,'thinking'],[5.4,'listening']]},

{id:'albums',dur:9.0,foot:'Albums and playlists expand into the speaker queue; shuffle is a play mode.',
 caps:[[0,'Whole albums and playlists, not just single tracks.'],
       [4.8,'"Shuffle" sets the play mode before it starts.']],
 logs:[[0.4,'heard: play the album Kind of Blue on bedroom Sonos, Jarvis',''],
       [2.0,'AddURIToQueue -> x-rincon-queue','g'],
       [5.2,'heard: shuffle the jazz classics playlist, Jarvis','']],
 cards:[[3.0,'Album','▶ Kind Of Blue — Miles Davis (Bedroom Sonos)'],
        [6.4,'Playlist','▶ Jazz Greatest Hits (shuffled, Bedroom Sonos)']],
 st:[[0,'listening']]},

{id:'sonosctl',dur:8.5,foot:'Transport needs no credentials at all; the speaker answers on the LAN.',
 caps:[[0,'Pause, skip and volume work per room, by name.'],
       [4.6,'A bare "the Sonos" means whichever you last played to.']],
 logs:[[0.4,'heard: louder in the bathroom, Jarvis',''],[2.2,'sonos: Bathroom Sonos 60%','g']],
 cards:[[3.2,'Sonos','♫ Bathroom Sonos 60%'],
        [6.0,'Sonos','⏸ Bedroom Sonos']],
 st:[[0,'listening']]},

{id:'youtube',dur:8.0,foot:'Resolved to a watch link, which starts playing. A results page would not.',
 caps:[[0,'YouTube resolves through the same search key — nothing extra to set up.']],
 logs:[[0.4,'heard: play Dead Can Dance on YouTube, Jarvis',''],
       [2.0,'serpapi: engine=youtube','g'],[3.4,'watch?v=mPDLJ1UU2Uk','k']],
 cards:[[4.4,'Video','▶ Dead Can Dance — The Carnival Is Over']],
 st:[[0,'thinking'],[4.4,'listening']]},

{id:'nowplaying',dur:7.0,foot:'Read from the live media session, or from the speaker itself.',
 caps:[[0,'It also knows what is already playing, on the phone or in a room.']],
 logs:[[0.4,'heard: what is playing in the bathroom, Jarvis?',''],[2.0,'sonos: GetPositionInfo','g']],
 cards:[[3.4,'Now playing','♪ Ain’t Gonna Let Nobody Turn Me Round — The Roots']],
 st:[[0,'listening']]},

{id:'remind',dur:9.0,foot:'Clock times and durations both schedule. A reminder that never fires is the worst bug.',
 caps:[[0,'Reminders take a wall-clock time, not only a countdown.'],
       [4.8,'It says the time back, so a misheard hour is obvious now rather than later.']],
 logs:[[0.4,'heard: remind me to pet the dog at 11:20 AM, Jarvis',''],
       [2.0,'command: reminder 11:20 AM','k']],
 cards:[[3.4,'Reminder','⏰ pet the dog at 11:20 AM (in 2 min).']],
 st:[[0,'listening']]},

{id:'count',dur:11.0,foot:'The bar is block characters — the only way to draw a shape in three lines of text.',
 caps:[[0,'Inside the last stretch the card becomes a countdown.'],
       [8.4,'It fires on its own, with nobody asking.']],
 logs:[[8.3,'pushed timer: Timer','g']],
 tick:function(lt){
   if(lt>=8.2){return ['Timer','⏰ Timer done',8.2];}
   var remain=Math.max(2,Math.round(72-(lt/8.2)*70));
   var done=Math.max(0,Math.min(1,1-remain/120));
   var n=Math.round(done*10),b='';
   for(var i=0;i<10;i++){b+=(i<n?'▓':'░');}
   var span=remain>=60?(Math.floor(remain/60)+' min'):(remain+' sec');
   return ['Timer','⏳ '+b+' '+span+' left',0];
 },
 st:[[0,'listening']]},

{id:'todo',dur:9.0,foot:'Lists and the calendar never leave the phone.',
 caps:[[0,'To-dos are local, and they survive a restart.'],
       [5.0,'Read straight back out of the phone’s own store.']],
 logs:[[0.4,'heard: add a todo buy oat milk, Jarvis',''],[1.6,'command: todo','k'],
       [4.8,'heard: show my task list, Jarvis','']],
 cards:[[2.4,'To-do','✓ Added: buy oat milk'],[6.4,'To-do','✓ 1 open: buy oat milk']],
 st:[[0,'listening']]},

{id:'cal',dur:8.5,foot:'A heads-up ten minutes ahead, and again at the time itself.',
 caps:[[0,'Some cards arrive unasked.'],
       [4.6,'And again when it starts — a heads-up alone is not a reminder.']],
 logs:[[1.2,'pushed calendar: RayNeo iO','g']],
 cards:[[2.0,'Calendar','▦ RayNeo iO in 10 min (6:00 PM)'],
        [5.4,'Calendar','▦ Now: RayNeo iO']],
 st:[[0,'listening']]},

{id:'full',dur:9.5,foot:'115 characters render in full · 116–130 are cut · 131 or more never render at all.',
 caps:[[0,'Everything the display holds at once: 107 characters, eleven glyphs, one line of green.'],
       [5.0,'At 131 characters the glasses draw nothing — and every layer still reports success.']],
 logs:[[0.3,'render probe: 107 chars / 152 bytes -> OK','g']],
 cards:[[0.9,'Full frame','☀ 64°F ↑79 ↓55 ▓▓▓▓▓▓▓░░░ ▦ Standup 9:00 ⏰ 12m ⚡ BART 8m → Berkeley ✓ 3 todos ★ AQI 21 ☂ 0% ♪ Radiohead ✉ 5']],
 st:[[0,'listening']]},

{id:'end',dur:8.0}
];

"""


def main():
    shutil.copy(SRC, DST)
    s = DST.read_text(encoding="utf-8")
    a = s.index("var S=[")
    b = s.index("var starts=[],T=0,i;")
    s = s[:a] + TIMELINE + s[b:]
    s = s.replace("&ldquo;EOTR1&rdquo; by EAR OF THE RAT</b>, from <i>6 of 1</i>",
                  "&ldquo;CHOOSE FUN&rdquo; by EAR OF THE RAT</b>, from <i>6 of 1</i>")
    DST.write_text(s, encoding="utf-8")
    total = sum(float(m) for m in re.findall(r"dur:([0-9.]+)", TIMELINE))
    print(f"{len(re.findall(r'\{id:', TIMELINE))} scenes, {total:.1f}s -> {DST.name}")


if __name__ == "__main__":
    main()
