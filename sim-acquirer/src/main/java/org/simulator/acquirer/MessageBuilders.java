package org.simulator.acquirer;

import org.simulator.iso.IsoMessage;

/**
 * Builds reversal (04xx) and network management (08xx) messages.
 */
public final class MessageBuilders {

    private MessageBuilders() {
    }

    /**
     * Builds a reversal for {@code original}. DE90 (original data elements) is built as
     * MTI(4) + STAN(6) + transmission date/time(10) + acquirer id(11) + forwarding id(11).
     *
     * @param mti        e.g. "0420" (Base24 reversal advice) or "0400"
     * @param response   optional original response (supplies DE38/DE39 if present)
     * @param newStan    STAN for the reversal itself
     * @param reasonCode optional DE39 reversal reason (e.g. "68" timeout), null to omit
     */
    public static IsoMessage reversal(IsoMessage original, IsoMessage response, String mti, String newStan,
                                      String reasonCode) {
        IsoMessage rev = original.copy();
        rev.setMti(mti);
        rev.remove(52).remove(53).remove(55).remove(35).remove(45).remove(64).remove(128);
        String origStan = nz(original.get(11), 6);
        String origDt = nz(original.get(7), 10);
        String acq = leftPadZero(original.get(32), 11);
        String fwd = leftPadZero(original.get(33), 11);
        rev.set(90, original.getMti() + origStan + origDt + acq + fwd);
        if (newStan != null) {
            rev.set(11, newStan);
        }
        if (response != null) {
            if (response.has(38)) {
                rev.set(38, response.get(38));
            }
        }
        if (reasonCode != null) {
            rev.set(39, reasonCode);
        } else {
            rev.remove(39);
        }
        return rev;
    }

    /** Builds a network management request (0800) with DE70 (001 sign-on, 002 sign-off, 301 echo). */
    public static IsoMessage networkManagement(String dialect, String code, String stan, String transmissionDateTime) {
        IsoMessage m = new IsoMessage(dialect, "0800");
        m.set(7, transmissionDateTime);
        m.set(11, stan);
        m.set(70, code);
        return m;
    }

    private static String nz(String v, int len) {
        return v == null ? "0".repeat(len) : v;
    }

    private static String leftPadZero(String v, int len) {
        String s = v == null ? "" : v;
        if (s.length() >= len) {
            return s.substring(s.length() - len);
        }
        return "0".repeat(len - s.length()) + s;
    }
}
