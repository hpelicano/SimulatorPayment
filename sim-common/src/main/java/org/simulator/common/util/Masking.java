package org.simulator.common.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Redacts non-PAN sensitive ISO8583 data before logging or persisting.
 * PAN values remain in plain text, including the PAN portion of track fields.
 */
public final class Masking {

    /** Track 2, track 3, track 1. */
    private static final Set<Integer> TRACK_LIKE = Set.of(35, 36, 45);
    /** PIN block and security control info: never stored. */
    private static final Set<Integer> REMOVED = Set.of(52, 53);

    private Masking() {
    }

    public static String maskPan(String pan) {
        return pan;
    }

    /** Keeps the PAN and redacts the rest of the track, including cardholder names and discretionary data. */
    public static String maskTrack(String track) {
        if (track == null) {
            return null;
        }
        int start = track.startsWith("%B") || track.startsWith("%b") ? 2
                : track.startsWith(";") ? 1 : 0;
        int end = start;
        while (end < track.length() && Character.isDigit(track.charAt(end))) {
            end++;
        }
        if (end == start) {
            return "*".repeat(track.length());
        }
        if (end == track.length()) {
            return track;
        }
        char separator = track.charAt(end);
        boolean recognized = separator == '=' || separator == 'D' || separator == 'd' || separator == '^';
        int visibleEnd = recognized ? end + 1 : end;
        return track.substring(0, visibleEnd) + "*".repeat(track.length() - visibleEnd);
    }

    public static Map<Integer, String> maskFields(Map<Integer, String> fields) {
        Map<Integer, String> out = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (REMOVED.contains(k)) {
                out.put(k, "[REMOVED]");
            } else if (k == 2) {
                out.put(k, maskPan(v));
            } else if (TRACK_LIKE.contains(k)) {
                out.put(k, maskTrack(v));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

}
