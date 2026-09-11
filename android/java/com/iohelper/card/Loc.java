package com.iohelper.card;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;

import java.util.List;
import java.util.Locale;

/**
 * Where the phone is, for anything that answers better when it knows.
 *
 * "Weather", "how long is the drive", "coffee near me" and most local questions
 * are only as good as the location behind them, and a typed-in city is wrong the
 * moment you leave it - which is exactly what a pair of glasses is for.
 *
 * Deliberately NEVER blocks on a new fix. This runs on the assistant's answer
 * path, where a several-second wait for GPS would be felt on every question, so
 * it reads the newest fix the platform already has and moves on. If there is no
 * fix, callers fall back to the configured location and nothing breaks.
 */
public final class Loc {

    /** A fix older than this is still better than nothing, but not by much. */
    private static final long STALE_MS = 30L * 60 * 1000;
    private static final long PLACE_TTL_MS = 5L * 60 * 1000;

    private static volatile String placeCache;
    private static volatile String addressCache;
    private static volatile long placeAt;
    private static volatile double placeLat;
    private static volatile double placeLon;

    private Loc() {
    }

    public static boolean permitted(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** The newest last-known fix across providers, or null. Never blocks. */
    public static Location fix(Context ctx) {
        if (!permitted(ctx)) {
            return null;
        }
        try {
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            if (lm == null) {
                return null;
            }
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l;
                try {
                    l = lm.getLastKnownLocation(provider);
                } catch (SecurityException e) {
                    continue;
                }
                if (l == null) {
                    continue;
                }
                if (best == null || l.getTime() > best.getTime()) {
                    best = l;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** "37.804400,-122.271100" - what map and search APIs actually want. */
    public static String coords(Context ctx) {
        Location l = fix(ctx);
        if (l == null) {
            return null;
        }
        return String.format(Locale.US, "%.6f,%.6f", l.getLatitude(), l.getLongitude());
    }

    /** How stale the fix is, in minutes, or -1 when there is none. */
    public static long ageMinutes(Context ctx) {
        Location l = fix(ctx);
        return l == null ? -1 : Math.max(0, (System.currentTimeMillis() - l.getTime()) / 60000);
    }

    /**
     * "Oakland, CA" - reverse geocoded, cached, and only re-derived when the
     * phone has actually moved. Geocoding hits the network, and the answer path
     * cannot afford to do that on every question.
     */
    public static String place(Context ctx) {
        Location l = fix(ctx);
        if (l == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        boolean moved = Math.abs(l.getLatitude() - placeLat) > 0.01
                || Math.abs(l.getLongitude() - placeLon) > 0.01;
        if (placeCache != null && !moved && now - placeAt < PLACE_TTL_MS) {
            return placeCache;
        }
        try {
            Geocoder g = new Geocoder(ctx, Locale.US);
            List<Address> found = g.getFromLocation(l.getLatitude(), l.getLongitude(), 1);
            if (found != null && !found.isEmpty()) {
                Address a = found.get(0);
                String city = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
                String region = a.getAdminArea();
                String label = city == null ? region
                        : (region == null ? city : city + ", " + region);

                // Street level, because "you are in Oakland" is not an answer to
                // "where am I" when you are standing on a corner. Number + street
                // when the geocoder has them, the street alone when it does not.
                String street = a.getThoroughfare();
                String number = a.getSubThoroughfare();
                String addr = null;
                if (street != null && !street.isEmpty()) {
                    addr = (number == null || number.isEmpty() ? street : number + " " + street);
                    if (city != null && !city.isEmpty()) {
                        addr = addr + ", " + city;
                    }
                } else if (a.getAddressLine(0) != null) {
                    addr = a.getAddressLine(0);
                }
                addressCache = addr;

                if (label != null && !label.isEmpty()) {
                    placeCache = label;
                    placeAt = now;
                    placeLat = l.getLatitude();
                    placeLon = l.getLongitude();
                    return label;
                }
            }
        } catch (Throwable ignored) {
            // offline, or no geocoder backend - coordinates still work
        }
        return null;
    }

    /** "1234 Broadway, Oakland" - the street address, when one is known. */
    public static String address(Context ctx) {
        place(ctx);                       // fills addressCache as a side effect
        return addressCache;
    }

    /**
     * The location string to hand a search API: the real place when the phone
     * knows it, otherwise whatever was typed into settings.
     */
    public static String searchLocation(Context ctx) {
        String p = place(ctx);
        if (p != null && !p.isEmpty()) {
            return p;
        }
        return Prefs.str(ctx, Prefs.SEARCH_LOCATION, "");
    }

    /**
     * The origin for a route. Coordinates beat a city name here: "how long is
     * the drive" from the middle of a city is a different answer depending on
     * which side of it you are standing on.
     */
    public static String origin(Context ctx) {
        String c = coords(ctx);
        return c != null ? c : searchLocation(ctx);
    }

    /** One line of context for the model, or null when there is nothing to say. */
    public static String context(Context ctx) {
        String p = place(ctx);
        String c = coords(ctx);
        if (p == null && c == null) {
            return null;
        }
        // Lead with the street address. Asked "where am I", a model handed only
        // a city and a lat/long answers with the lat/long, which is useless to a
        // person standing on a corner.
        String street = address(ctx);
        StringBuilder sb = new StringBuilder("The user is currently at ");
        sb.append(street != null ? street : (p != null ? p : "coordinates"));
        if (street != null && p != null && !street.contains(p)) {
            sb.append(", ").append(p);
        }
        if (c != null) {
            sb.append(" (").append(c).append(")");
        }
        sb.append(". Give street-level answers; do not read out coordinates");
        long age = ageMinutes(ctx);
        if (age > 10) {
            sb.append(", fix ").append(age).append(" min old");
        }
        return sb.append(".").toString();
    }
}
