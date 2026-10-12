package org.simulator.iso;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Canonical, dialect independent representation of a financial message. Field values are strings:
 * digits/text as-is, binary (type B) as uppercase hex.
 */
public final class IsoMessage {

    private String dialect;
    private String header;
    private String mti;
    private final NavigableMap<Integer, String> fields = new TreeMap<>();

    public IsoMessage() {
    }

    public IsoMessage(String dialect, String mti) {
        this.dialect = dialect;
        this.mti = mti;
    }

    public IsoMessage set(int field, String value) {
        if (field < 2 || field > 192) {
            throw new IllegalArgumentException("Invalid field number " + field);
        }
        if (value == null) {
            fields.remove(field);
        } else {
            fields.put(field, value);
        }
        return this;
    }

    public String get(int field) {
        return fields.get(field);
    }

    public boolean has(int field) {
        return fields.containsKey(field);
    }

    public IsoMessage remove(int field) {
        fields.remove(field);
        return this;
    }

    public Map<Integer, String> fields() {
        return Collections.unmodifiableMap(fields);
    }

    public IsoMessage copy() {
        IsoMessage m = new IsoMessage(dialect, mti);
        m.header = header;
        m.fields.putAll(fields);
        return m;
    }

    /** Snapshot for logs/UI: plain PAN, redacted track details and removed PIN/security control data. */
    public IsoMessage redactedCopy() {
        IsoMessage m = new IsoMessage(dialect, mti).setHeader(header);
        org.simulator.common.util.Masking.maskFields(fields).forEach(m::set);
        return m;
    }

    /** Replayable snapshot retaining PAN/track data, without PIN blocks or security control data. */
    public IsoMessage replayCopy() {
        return copy().remove(52).remove(53);
    }

    /** Response MTI for a request (0200 -> 0210, 0420 -> 0430, 1100 -> 1110). */
    public static String responseMti(String requestMti) {
        char[] c = requestMti.toCharArray();
        int function = c[2] - '0';
        if (function % 2 == 0) {
            c[2] = (char) ('0' + function + 1);
        }
        return new String(c);
    }

    public boolean isRequest() {
        return mti != null && mti.length() == 4 && (mti.charAt(2) - '0') % 2 == 0;
    }

    public String getDialect() { return dialect; }
    public IsoMessage setDialect(String dialect) { this.dialect = dialect; return this; }
    public String getHeader() { return header; }
    public IsoMessage setHeader(String header) { this.header = header; return this; }
    public String getMti() { return mti; }
    public IsoMessage setMti(String mti) { this.mti = mti; return this; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[").append(dialect).append("] MTI=").append(mti);
        org.simulator.common.util.Masking.maskFields(fields).forEach((k, v) -> sb.append(" DE").append(k).append('=').append(v));
        return sb.toString();
    }
}
