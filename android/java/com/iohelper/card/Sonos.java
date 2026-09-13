package com.iohelper.card;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sonos speakers, controlled directly over the local network.
 *
 * WHY NOT THROUGH SPOTIFY. Choosing a speaker with the Spotify Web API needs a
 * device id, and Sonos does not expose one: /v1/me/player reports the Sonos as
 * the active device with `"id": null, "is_restricted": true`, which is Spotify
 * saying the endpoint refuses commands for it. That is Sonos's Connect
 * implementation being read-only, not a missing scope - no amount of OAuth
 * changes it. So transport goes straight to the speaker instead.
 *
 * WHAT THAT BUYS. Play, pause, skip and volume need NO credentials at all: a
 * Sonos answers unauthenticated SOAP on port 1400. Nothing to expire, nothing
 * to provision, and it keeps working when Spotify's API is down.
 *
 * THE LIMIT. It is LAN-only. On cell data the speakers are unreachable, so every
 * call here is short-timeout and fails with a sentence the wearer can act on
 * rather than hanging on a socket.
 */
public final class Sonos {

    private static final int PORT = 1400;
    private static final int SSDP_PORT = 1900;
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final String ST = "urn:schemas-upnp-org:device:ZonePlayer:1";
    private static final int SOAP_TIMEOUT_MS = 4000;
    /** Asking a speaker we already know whether it is still there: fail fast. */
    private static final int QUICK_TIMEOUT_MS = 1500;
    // A large Spotify playlist/album takes the speaker well over 4s to
    // resolve and enqueue; quick queries keep the short timeout.
    private static final int ENQUEUE_TIMEOUT_MS = 15000;
    /** Rediscover at most this often; speakers do not move around much. */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private static final String TAG = "iohelperSonos";
    private static final List<Zone> cache = new ArrayList<>();
    private static volatile long cachedAt;
    /**
     * The Wi-Fi network, if the phone has one. Every Sonos socket is opened
     * through it, because a speaker only lives on the LAN: when mobile data is
     * also up (it usually is), Android sends an UNBOUND socket out over cellular
     * by default, where 192.168.x.x is unreachable - so discovery, which binds
     * its UDP socket to the Wi-Fi address, saw the speakers while every TCP
     * control call quietly timed out. Set on each discover(); null means no
     * Wi-Fi, and connections fall back to the default network.
     */
    private static volatile android.net.Network wifiNet;

    private Sonos() {
    }

    /** Find the Wi-Fi network so LAN sockets can be pinned to it. */
    private static void bindWifi(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            android.net.Network found = null;
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null
                        && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                    found = n;
                    break;
                }
            }
            wifiNet = found;
            android.util.Log.i(TAG, "bindWifi: " + (found == null ? "no wifi network" : "pinned to wifi"));
        } catch (Exception e) {
            android.util.Log.i(TAG, "bindWifi: " + e);
            wifiNet = null;
        }
    }

    /** One speaker: the room it is in, and where to reach it. */
    public static final class Zone {
        public final String room;
        public final String ip;

        Zone(String room, String ip) {
            this.room = room;
            this.ip = ip;
        }

        @Override
        public String toString() {
            return room + " (" + ip + ")";
        }
    }

    // ---- discovery ----------------------------------------------------------

    /**
     * Find speakers by SSDP. Responses come back as unicast, so this needs no
     * multicast lock - and it beats scanning 254 addresses, which is slow enough
     * on a phone to be felt in an answer.
     */
    public static synchronized List<Zone> discover(Context ctx, boolean force) {
        // Pin to Wi-Fi first, so even a cache hit leaves later control calls on
        // the LAN interface rather than cellular.
        bindWifi(ctx);
        if (!force && !cache.isEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return new ArrayList<>(cache);
        }
        // GO STRAIGHT TO A SPEAKER WE ALREADY KNOW.
        //
        // The full sweep below costs ~12s before a note plays: SSDP, a 254-address
        // scan, then a 3s timeout for every address that answered SSDP but is not
        // a Sonos (one such device on this network burns 3s of every request).
        // Any blip inside that window fails the whole command, which is what made
        // playback feel intermittent - one attempt found nothing at all, the next
        // a minute later played fine. A speaker that is still answering on its
        // remembered address is the answer, so ask it first and skip the sweep.
        if (!force) {
            List<Zone> known = cache.isEmpty() ? restore(ctx) : new ArrayList<>(cache);
            List<Zone> alive = new ArrayList<>();
            for (Zone z : known) {
                String room = roomName(z.ip, QUICK_TIMEOUT_MS);
                if (room != null) {
                    alive.add(new Zone(room, z.ip));
                }
            }
            if (!alive.isEmpty()) {
                android.util.Log.i(TAG, "known speaker still answering: " + alive);
                cache.clear();
                cache.addAll(alive);
                cachedAt = System.currentTimeMillis();
                remember(ctx, alive);
                return new ArrayList<>(alive);
            }
        }
        Map<String, String> found = new LinkedHashMap<>();     // ip -> room
        java.net.InetAddress lan = lanAddress();
        android.util.Log.i(TAG, "discover: lan=" + lan);
        DatagramSocket sock = null;
        try {
            String probe = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 1\r\n"
                    + "ST: " + ST + "\r\n\r\n";
            // Bound to the Wi-Fi address on purpose. With a VPN up - Tailscale,
            // here - the default route is the tunnel, and an unbound multicast
            // goes out of it and never reaches the house network.
            sock = lan != null ? new DatagramSocket(0, lan) : new DatagramSocket();
            sock.setSoTimeout(1200);
            byte[] out = probe.getBytes("UTF-8");
            InetAddress group = InetAddress.getByName(SSDP_ADDR);
            for (int i = 0; i < 2; i++) {          // UDP; ask twice
                sock.send(new DatagramPacket(out, out.length, group, SSDP_PORT));
            }
            byte[] buf = new byte[2048];
            long deadline = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    sock.receive(in);
                    String ip = in.getAddress().getHostAddress();
                    android.util.Log.i(TAG, "ssdp reply from " + ip);
                    if (!found.containsKey(ip)) {
                        found.put(ip, null);
                    }
                } catch (Exception timeout) {
                    break;
                }
            }
        } catch (Exception e) {
            android.util.Log.i(TAG, "ssdp failed: " + e);
            // no network, or multicast blocked - fall through with what we have
        } finally {
            if (sock != null) {
                sock.close();
            }
        }

        // SSDP is the polite way and often silently yields nothing on a phone -
        // multicast is easy to lose to a VPN or a Wi-Fi driver. Unicast to the
        // subnet always works, so fall back to knocking on port 1400 directly.
        android.util.Log.i(TAG, "ssdp found " + found.size());
        // Always scan as well, never only when SSDP came back empty: a partial
        // SSDP answer is common (one speaker replied here, not both), and
        // trusting it silently loses the other. Discovery is cached for ten
        // minutes, so the cost is paid once.
        if (lan != null) {
            long t0 = System.currentTimeMillis();
            found.putAll(scanSubnet(lan.getHostAddress()));
            android.util.Log.i(TAG, "scan found " + found.size()
                    + " in " + (System.currentTimeMillis() - t0) + "ms");
        }

        List<Zone> zones = new ArrayList<>();
        for (String ip : found.keySet()) {
            String room = roomName(ip);
            if (room != null) {
                zones.add(new Zone(room, ip));
            }
        }
        if (!zones.isEmpty()) {
            cache.clear();
            cache.addAll(zones);
            cachedAt = System.currentTimeMillis();
            remember(ctx, zones);
        } else if (cache.isEmpty()) {
            cache.addAll(restore(ctx));            // last known, for a flaky probe
        }
        return new ArrayList<>(cache);
    }

    /**
     * Room names we already know about, WITHOUT scanning: the live cache if it
     * has anything, else the last-known set persisted from a previous run.
     * This is safe to call on the answer path - discover() blocks for seconds
     * on a cold cache, this never does - so the model can be told which rooms
     * exist without slowing the reply. Warm the real cache in the background
     * (see TalkService) so this has something to return.
     */
    public static synchronized List<String> cachedRooms(Context ctx) {
        List<Zone> zones = !cache.isEmpty() ? new ArrayList<>(cache) : restore(ctx);
        List<String> rooms = new ArrayList<>();
        for (Zone z : zones) {
            if (z.room != null && !z.room.isEmpty() && !rooms.contains(z.room)) {
                rooms.add(z.room);
            }
        }
        return rooms;
    }

    /**
     * The phone's address on the real network, skipping VPN interfaces.
     *
     * With Tailscale up there are several addresses; picking the wrong one sends
     * discovery into the tunnel, where no speaker will ever answer.
     */
    private static java.net.InetAddress lanAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.US);
                if (!ni.isUp() || ni.isLoopback() || n.startsWith("tun") || n.startsWith("ppp")) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return a;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Knock on port 1400 across the /24. Bounded, parallel, ~2s in practice. */
    private static Map<String, String> scanSubnet(String selfIp) {
        Map<String, String> hits = new java.util.concurrent.ConcurrentHashMap<>();
        int dot = selfIp.lastIndexOf('.');
        if (dot < 0) {
            return hits;
        }
        String prefix = selfIp.substring(0, dot + 1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(32);
        try {
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + i;
                pool.submit(() -> {
                    android.net.Network net = wifiNet;
                    try (java.net.Socket s = net != null
                            ? net.getSocketFactory().createSocket() : new java.net.Socket()) {
                        s.connect(new java.net.InetSocketAddress(ip, PORT), 400);
                        hits.put(ip, "");
                    } catch (Exception ignored) {
                        // not a Sonos, or nothing there
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        return hits;
    }

    /** The speaker's room, straight from its own description document. */
    private static String roomName(String ip) {
        return roomName(ip, 3000);
    }

    private static String roomName(String ip, int timeoutMs) {
        try {
            String xml = http("http://" + ip + ":" + PORT + "/xml/device_description.xml",
                    null, null, timeoutMs);
            int i = xml.indexOf("<roomName>");
            if (i < 0) {
                android.util.Log.i(TAG, "roomName " + ip + ": no tag, " + xml.length() + " bytes");
                return null;
            }
            String room = xml.substring(i + 10, xml.indexOf("</roomName>", i)).trim();
            return room.isEmpty() ? null : room;
        } catch (Exception e) {
            android.util.Log.i(TAG, "roomName " + ip + " failed: " + e);
            return null;
        }
    }

    // ---- choosing which speaker --------------------------------------------

    /**
     * Match what was said to a speaker.
     *
     * A bare "sonos" means the one last played to - that is what someone means
     * when they do not name a room, and it is the only answer that stays right
     * as they move around the house.
     */
    public static Zone pick(Context ctx, String spoken) {
        List<Zone> zones = discover(ctx, false);
        if (zones.isEmpty()) {
            return null;
        }
        String low = spoken == null ? "" : spoken.toLowerCase(Locale.US);

        // an explicitly named room wins over anything remembered
        for (Zone z : zones) {
            String room = z.room.toLowerCase(Locale.US);
            if (low.contains(room)) {
                return z;
            }
            String bare = room.replace("sonos", "").trim();      // "Bedroom Sonos" -> "bedroom"
            if (!bare.isEmpty() && low.contains(bare)) {
                return z;
            }
        }
        String last = Prefs.str(ctx, Prefs.SONOS_LAST, "");
        for (Zone z : zones) {
            if (z.room.equals(last)) {
                return z;
            }
        }
        return zones.get(0);
    }

    // ---- transport ----------------------------------------------------------

    public static String control(Context ctx, Zone z, String action) {
        if (z == null) {
            return "No Sonos on this network.";
        }
        try {
            switch (action) {
                case "play":
                    av(z, "Play", "<Speed>1</Speed>");
                    break;
                case "pause":
                    av(z, "Pause", "");
                    break;
                case "stop":
                    av(z, "Stop", "");
                    break;
                case "next":
                    av(z, "Next", "");
                    break;
                case "previous":
                    av(z, "Previous", "");
                    break;
                case "louder":
                case "quieter":
                    int v = volume(z);
                    int target = Math.max(0, Math.min(100,
                            v + ("louder".equals(action) ? 8 : -8)));
                    render(z, "SetVolume",
                            "<Channel>Master</Channel><DesiredVolume>" + target + "</DesiredVolume>");
                    Prefs.put(ctx, Prefs.SONOS_LAST, z.room);
                    return (("louder".equals(action)) ? "🔊 " : "🔉 ") + z.room + " " + target + "%";
                default:
                    return null;
            }
            Prefs.put(ctx, Prefs.SONOS_LAST, z.room);
            switch (action) {
                case "next": return "⏭ " + z.room;
                case "previous": return "⏮ " + z.room;
                case "pause": return "⏸ " + z.room;
                case "stop": return "⏹ " + z.room;
                default: return "▶ " + z.room;
            }
        } catch (Exception e) {
            return offline(e, z);
        }
    }

    /** What the speaker is playing, title and artist, from its own metadata. */
    public static String nowPlaying(Context ctx, Zone z) {
        if (z == null) {
            return "No Sonos on this network.";
        }
        try {
            String r = av(z, "GetPositionInfo", "");
            String meta = between(r, "<TrackMetaData>", "</TrackMetaData>");
            String title = unescape(between(meta, "&lt;dc:title&gt;", "&lt;/dc:title&gt;"));
            String artist = unescape(between(meta, "&lt;dc:creator&gt;", "&lt;/dc:creator&gt;"));
            if (title == null || title.isEmpty()) {
                return "♪ Nothing on " + z.room + ".";
            }
            return "♪ " + title + (artist == null || artist.isEmpty() ? "" : " — " + artist)
                    + " (" + z.room + ")";
        } catch (Exception e) {
            return offline(e, z);
        }
    }

    // ---- starting a Spotify track on the speaker ----------------------------

    /**
     * Sonos needs three household-specific values to accept a Spotify track, and
     * none are documented: a service id, an account serial, and a metadata
     * token. Rather than hardcode numbers that differ per household and drift
     * across firmware, read a REAL Spotify favourite off the speaker and copy
     * the parameters out of it. Learned once, then cached.
     */
    private static String[] spotifyParams(Context ctx, Zone z) {
        String cached = Prefs.str(ctx, Prefs.SONOS_SPOTIFY, "");
        if (!cached.isEmpty()) {
            String[] p = cached.split("\\|", 3);
            if (p.length == 3) {
                return p;
            }
        }
        String sid = "12";
        String sn = "1";
        String token = "SA_RINCON3079_X_#Svc3079-0-Token";
        try {
            String xml = soap(z, "/MediaServer/ContentDirectory/Control",
                    "urn:schemas-upnp-org:service:ContentDirectory:1", "Browse",
                    "<ObjectID>FV:2</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>"
                    + "<Filter>*</Filter><StartingIndex>0</StartingIndex>"
                    + "<RequestedCount>50</RequestedCount><SortCriteria></SortCriteria>")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"");
            // The Browse response is double-escaped, so "&" can still be "&amp;"
            // after one unescape pass. Tolerate either, and do not require the
            // two parameters to be adjacent - flags sits between them and its
            // value varies by item type.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("spotify%3a[a-z]+%3a[A-Za-z0-9]+\\?sid=(\\d+)"
                            + "(?:&(?:amp;)?[a-z]+=\\d+)*?"
                            + "&(?:amp;)?sn=(\\d+)")
                    .matcher(xml);
            if (m.find()) {
                sid = m.group(1);
                sn = m.group(2);
            }
            java.util.regex.Matcher t = java.util.regex.Pattern
                    .compile("SA_RINCON\\d+_X_#Svc\\d+-0-Token").matcher(xml);
            while (t.find()) {
                if (t.group().contains("3079")) {          // Spotify's service type
                    token = t.group();
                    break;
                }
            }
            android.util.Log.i(TAG, "spotify params sid=" + sid + " sn=" + sn);
            Prefs.put(ctx, Prefs.SONOS_SPOTIFY, sid + "|" + sn + "|" + token);
        } catch (Exception ignored) {
        }
        return new String[]{sid, sn, token};
    }

    private static final Map<String, String> PREFIX = new LinkedHashMap<>();
    private static final Map<String, String> DIDL_CLASS = new LinkedHashMap<>();

    static {
        PREFIX.put("album", "1004206c");
        PREFIX.put("playlist", "1006206c");
        DIDL_CLASS.put("album", "object.container.album.musicAlbum");
        DIDL_CLASS.put("playlist", "object.container.playlistContainer");
    }

    /**
     * Play a whole album or playlist, optionally shuffled.
     *
     * A container is not a track: different URI prefix, different DIDL class,
     * and it cannot simply be handed to SetAVTransportURI. It has to be expanded
     * INTO THE QUEUE and then the queue played - and the queue belongs to a
     * specific speaker, so its uuid has to come from that speaker's own
     * description document. Verified against a real album and playlist before
     * this was written.
     */
    public static String playContainer(Context ctx, Zone z, String kind, String id,
                                       String label, boolean shuffle) {
        if (z == null) {
            return "No Sonos on this network.";
        }
        String prefix = PREFIX.get(kind);
        if (prefix == null) {
            return null;
        }
        try {
            String[] p = spotifyParams(ctx, z);
            String uri = "x-rincon-cpcontainer:" + prefix + "spotify%3a" + kind + "%3a" + id
                    + "?sid=" + p[0] + "&flags=8300&sn=" + p[1];
            String didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                    + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
                    + " xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\""
                    + " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
                    + "<item id=\"" + prefix + "spotify%3a" + kind + "%3a" + id
                    + "\" parentID=\"00020000\" restricted=\"true\">"
                    + "<dc:title>" + esc(label) + "</dc:title>"
                    + "<upnp:class>" + DIDL_CLASS.get(kind) + "</upnp:class>"
                    + "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">"
                    + p[2] + "</desc></item></DIDL-Lite>";

            av(z, "RemoveAllTracksFromQueue", "", ENQUEUE_TIMEOUT_MS);
            String r = av(z, "AddURIToQueue",
                    "<EnqueuedURI>" + esc(uri) + "</EnqueuedURI>"
                    + "<EnqueuedURIMetaData>" + esc(didl) + "</EnqueuedURIMetaData>"
                    + "<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued>"
                    + "<EnqueueAsNext>0</EnqueueAsNext>", ENQUEUE_TIMEOUT_MS);
            if (r.contains("<errorCode>")) {
                Prefs.put(ctx, Prefs.SONOS_SPOTIFY, "");
                return "Sonos refused that (" + between(r, "<errorCode>", "</errorCode>") + ")";
            }
            String uuid = uuidOf(z);
            if (uuid == null) {
                return "Couldn't address " + z.room + "'s queue.";
            }
            av(z, "SetAVTransportURI", "<CurrentURI>x-rincon-queue:" + uuid
                    + "#0</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>");
            av(z, "SetPlayMode", "<NewPlayMode>"
                    + (shuffle ? "SHUFFLE_NOREPEAT" : "NORMAL") + "</NewPlayMode>");
            av(z, "Play", "<Speed>1</Speed>");
            Prefs.put(ctx, Prefs.SONOS_LAST, z.room);
            return "▶ " + label + (shuffle ? " (shuffled, " : " (") + z.room + ")";
        } catch (Exception e) {
            return offline(e, z);
        }
    }

    /**
     * Queue several tracks and play them - what "play the band X" should do.
     *
     * A single track is not an answer to naming an artist, and a playlist named
     * after them is a stranger's guess at their catalogue. Their own top tracks
     * are neither.
     */
    public static String playTracks(Context ctx, Zone z, String[] trackIds,
                                    String label, boolean shuffle) {
        if (z == null) {
            return "No Sonos on this network.";
        }
        if (trackIds == null || trackIds.length == 0) {
            return "♪ Nothing to play for " + label + ".";
        }
        try {
            String[] p = spotifyParams(ctx, z);
            av(z, "RemoveAllTracksFromQueue", "", ENQUEUE_TIMEOUT_MS);
            int queued = 0;
            for (String id : trackIds) {
                String uri = "x-sonos-spotify:spotify%3atrack%3a" + id
                        + "?sid=" + p[0] + "&flags=8224&sn=" + p[1];
                String didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                        + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
                        + " xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\""
                        + " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
                        + "<item id=\"00032020spotify%3atrack%3a" + id
                        + "\" parentID=\"00020000spotify%3atrack%3a" + id
                        + "\" restricted=\"true\"><dc:title></dc:title>"
                        + "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
                        + "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">"
                        + p[2] + "</desc></item></DIDL-Lite>";
                String r = av(z, "AddURIToQueue",
                        "<EnqueuedURI>" + esc(uri) + "</EnqueuedURI>"
                        + "<EnqueuedURIMetaData>" + esc(didl) + "</EnqueuedURIMetaData>"
                        + "<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued>"
                        + "<EnqueueAsNext>0</EnqueueAsNext>", ENQUEUE_TIMEOUT_MS);
                if (!r.contains("<errorCode>")) {
                    queued++;
                }
            }
            if (queued == 0) {
                Prefs.put(ctx, Prefs.SONOS_SPOTIFY, "");
                return "Sonos refused those tracks.";
            }
            String uuid = uuidOf(z);
            if (uuid == null) {
                return "Couldn't address " + z.room + "'s queue.";
            }
            av(z, "SetAVTransportURI", "<CurrentURI>x-rincon-queue:" + uuid
                    + "#0</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>");
            av(z, "SetPlayMode", "<NewPlayMode>"
                    + (shuffle ? "SHUFFLE_NOREPEAT" : "NORMAL") + "</NewPlayMode>");
            av(z, "Play", "<Speed>1</Speed>");
            Prefs.put(ctx, Prefs.SONOS_LAST, z.room);
            return "▶ " + label + " — " + queued + " tracks"
                    + (shuffle ? ", shuffled (" : " (") + z.room + ")";
        } catch (Exception e) {
            return offline(e, z);
        }
    }

    /** Turn shuffle on or off on a speaker without changing what is queued. */
    public static String shuffle(Context ctx, Zone z, boolean on) {
        if (z == null) {
            return "No Sonos on this network.";
        }
        try {
            av(z, "SetPlayMode", "<NewPlayMode>"
                    + (on ? "SHUFFLE_NOREPEAT" : "NORMAL") + "</NewPlayMode>");
            Prefs.put(ctx, Prefs.SONOS_LAST, z.room);
            return (on ? "🔀 Shuffle on — " : "➡ Shuffle off — ") + z.room;
        } catch (Exception e) {
            return offline(e, z);
        }
    }

    /** The speaker's own uuid, which is what names its queue. */
    private static String uuidOf(Zone z) {
        try {
            String xml = http("http://" + z.ip + ":" + PORT + "/xml/device_description.xml",
                    null, null, 4000);
            int i = xml.indexOf("<UDN>uuid:");
            return i < 0 ? null : xml.substring(i + 10, xml.indexOf("<", i + 10)).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Start one Spotify track on a speaker. Returns the line for the glasses. */
    public static String playSpotify(Context ctx, Zone z, String trackId, String label) {
        if (z == null) {
            return "No Sonos on this network.";
        }
        try {
            String[] p = spotifyParams(ctx, z);
            String uri = "x-sonos-spotify:spotify%3atrack%3a" + trackId
                    + "?sid=" + p[0] + "&flags=8224&sn=" + p[1];
            String didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                    + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
                    + " xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\""
                    + " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
                    + "<item id=\"00032020spotify%3atrack%3a" + trackId
                    + "\" parentID=\"00020000spotify%3atrack%3a" + trackId
                    + "\" restricted=\"true\"><dc:title></dc:title>"
                    + "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
                    + "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">"
                    + p[2] + "</desc></item></DIDL-Lite>";
            String r = av(z, "SetAVTransportURI",
                    "<CurrentURI>" + esc(uri) + "</CurrentURI>"
                    + "<CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
            if (r.contains("<errorCode>")) {
                // Relearn next time: a rejected URI usually means the cached
                // account serial went stale, not that the track is unplayable.
                Prefs.put(ctx, Prefs.SONOS_SPOTIFY, "");
                return "Sonos refused that (" + between(r, "<errorCode>", "</errorCode>") + ")";
            }
            av(z, "Play", "<Speed>1</Speed>");
            Prefs.put(ctx, Prefs.SONOS_LAST, z.room);
            return "▶ " + label + " (" + z.room + ")";
        } catch (Exception e) {
            return offline(e, z);
        }
    }

    /**
     * Start an internet radio STREAM on a speaker.
     *
     * A station is not a track, and the difference is structural rather than
     * cosmetic: there is nothing to enqueue, so this hands the URI straight to
     * SetAVTransportURI instead of the RemoveAllTracks/AddURITo\"ueue/
     * x-rincon-queue sequence an album needs. The DIDL class is audioBroadcast,
     * which is what tells the speaker the stream is endless - a musicTrack
     * class on a stream shows a progress bar that never fills.
     *
     * The URI scheme is Sonos own: x-rincon-mp3radio:// replaces http:// on the
     * stream URL. An https:// stream keeps its scheme after the marker, because
     * dropping it would leave the speaker fetching plaintext from a TLS-only
     * host.
     */
    public static String playRadio(Context ctx, Zone z, String stream, String name) {
        if (z == null) {
            return "No Sonos on this network.";
        }
        if (stream == null || stream.isEmpty()) {
            return "No stream for that station.";
        }
        String label = name == null || name.isEmpty() ? "Radio" : name;
        try {
            String uri = "x-rincon-mp3radio://" + (stream.startsWith("http://")
                    ? stream.substring("http://".length()) : stream);
            String didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                    + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
                    + " xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\""
                    + " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
                    + "<item id=\"R:0/0/0\" parentID=\"R:0/0\" restricted=\"true\">"
                    + "<dc:title>" + esc(label) + "</dc:title>"
                    + "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>"
                    + "<desc id=\"cdudn\" nameSpace="
                    + "\"urn:schemas-rinconnetworks-com:metadata-1-0/\">"
                    + "SA_RINCON65031_</desc></item></DIDL-Lite>";
            String r = av(z, "SetAVTransportURI",
                    "<CurrentURI>" + esc(uri) + "</CurrentURI>"
                    + "<CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
            if (r.contains("<errorCode>") && stream.startsWith("https://")) {
                // Keeping the inner https:// is documented by exactly one
                // library and used in the field by none, so it is the sensible
                // first try but not something to bet the station on. The
                // fallback is the form every field report uses - scheme
                // dropped, which is what SoCo does unconditionally. It means
                // plaintext, so it is second, not first, and only ever a
                // recovery from a refusal.
                String alt = "x-rincon-mp3radio://" + stream.substring("https://".length());
                r = av(z, "SetAVTransportURI",
                        "<CurrentURI>" + esc(alt) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
            }
            if (r.contains("<errorCode>")) {
                return "Sonos refused that station ("
                        + between(r, "<errorCode>", "</errorCode>") + ")";
            }
            av(z, "Play", "<Speed>1</Speed>");
            Prefs.put(ctx, Prefs.SONOS_LAST, z.room);
            return "◉ " + label + " (" + z.room + ")";
        } catch (Exception e) {
            return offline(e, z);
        }
    }

    /** XML-escape a value going inside a SOAP element. */
    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static int volume(Zone z) throws Exception {
        String r = render(z, "GetVolume", "<Channel>Master</Channel>");
        String v = between(r, "<CurrentVolume>", "</CurrentVolume>");
        return v == null ? 30 : Integer.parseInt(v.trim());
    }

    // ---- SOAP ---------------------------------------------------------------

    private static String av(Zone z, String action, String args) throws Exception {
        return av(z, action, args, SOAP_TIMEOUT_MS);
    }

    private static String av(Zone z, String action, String args, int timeoutMs) throws Exception {
        return soap(z, "/MediaRenderer/AVTransport/Control",
                "urn:schemas-upnp-org:service:AVTransport:1", action, args, timeoutMs);
    }

    private static String render(Zone z, String action, String args) throws Exception {
        return soap(z, "/MediaRenderer/RenderingControl/Control",
                "urn:schemas-upnp-org:service:RenderingControl:1", action, args);
    }

    private static String soap(Zone z, String path, String service, String action, String args)
            throws Exception {
        return soap(z, path, service, action, args, SOAP_TIMEOUT_MS);
    }

    private static String soap(Zone z, String path, String service, String action, String args,
                               int timeoutMs) throws Exception {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + service + "\">"
                + "<InstanceID>0</InstanceID>" + args
                + "</u:" + action + "></s:Body></s:Envelope>";
        return http("http://" + z.ip + ":" + PORT + path, body,
                service + "#" + action, timeoutMs);
    }

    /**
     * One retry on a timeout, because the path to the speaker comes and goes.
     *
     * Measured on this network: the same request failed outright, then succeeded
     * a minute later untouched. A single blip should not lose the command, so a
     * connect/read timeout is tried once more before giving up. Only a timeout
     * is retried - a speaker that answered and refused is answered honestly.
     */
    private static String http(String url, String body, String soapAction, int timeoutMs)
            throws Exception {
        try {
            return httpOnce(url, body, soapAction, timeoutMs);
        } catch (java.net.SocketTimeoutException first) {
            android.util.Log.i(TAG, "timeout, one retry: " + url);
            return httpOnce(url, body, soapAction, timeoutMs);
        }
    }

    private static String httpOnce(String url, String body, String soapAction, int timeoutMs)
            throws Exception {
        // Open through the Wi-Fi network when there is one, so the speaker on the
        // LAN is reachable even while mobile data carries the default route.
        URL u = new URL(url);
        android.net.Network net = wifiNet;
        HttpURLConnection c = (HttpURLConnection)
                (net != null ? net.openConnection(u) : u.openConnection());
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        if (body != null) {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            if (soapAction != null) {
                c.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");
            }
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (in != null && (n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    // ---- small helpers ------------------------------------------------------

    private static String offline(Exception e, Zone z) {
        // The usual cause is being off the house network, not a broken speaker.
        return "Can't reach " + z.room + " - are you on home Wi-Fi?";
    }

    private static String between(String s, String a, String b) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf(a);
        if (i < 0) {
            return null;
        }
        int j = s.indexOf(b, i + a.length());
        return j < 0 ? null : s.substring(i + a.length(), j);
    }

    private static String unescape(String s) {
        return s == null ? null : s.replace("&amp;", "&").replace("&apos;", "'")
                .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">");
    }

    /** Remember the speakers so a failed probe does not mean "no Sonos". */
    private static void remember(Context ctx, List<Zone> zones) {
        try {
            JSONArray a = new JSONArray();
            for (Zone z : zones) {
                a.put(new JSONObject().put("room", z.room).put("ip", z.ip));
            }
            Prefs.put(ctx, Prefs.SONOS_ZONES, a.toString());
        } catch (Exception ignored) {
        }
    }

    private static List<Zone> restore(Context ctx) {
        List<Zone> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.str(ctx, Prefs.SONOS_ZONES, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Zone(o.getString("room"), o.getString("ip")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
