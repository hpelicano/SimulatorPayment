package org.simulator.common.util;

/**
 * Packed BCD helpers. Supports hex nibbles (A-F) so that track data separators ('=' mapped to 'D') can be packed.
 */
public final class Bcd {

    private Bcd() {
    }

    /** Packs digits into BCD; odd-length values are left padded with a zero nibble. */
    public static byte[] encode(String digits) {
        String value = digits.length() % 2 == 0 ? digits : "0" + digits;
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = nibble(value.charAt(2 * i));
            int lo = nibble(value.charAt(2 * i + 1));
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Packs digits into BCD right padding with {@code pad} nibble when odd (track 2 / Z fields). */
    public static byte[] encodeRightPadded(String digits, char pad) {
        return encode(digits.length() % 2 == 0 ? digits : digits + pad);
    }

    /** Unpacks {@code digitCount} digits from BCD bytes; when odd the leading nibble is discarded. */
    public static String decode(byte[] data, int offset, int digitCount) {
        String full = decodeAll(data, offset, byteLength(digitCount));
        return digitCount % 2 == 0 ? full : full.substring(1);
    }

    /** Unpacks digits that were right padded (odd length keeps the leading digits). */
    public static String decodeRightPadded(byte[] data, int offset, int digitCount) {
        return decodeAll(data, offset, byteLength(digitCount)).substring(0, digitCount);
    }

    public static int byteLength(int digitCount) {
        return (digitCount + 1) / 2;
    }

    private static String decodeAll(byte[] data, int offset, int byteLen) {
        if (offset + byteLen > data.length) {
            throw new IllegalArgumentException("BCD data truncated at offset " + offset);
        }
        StringBuilder sb = new StringBuilder(byteLen * 2);
        for (int i = 0; i < byteLen; i++) {
            int b = data[offset + i] & 0xFF;
            sb.append(Character.toUpperCase(Character.forDigit(b >> 4, 16)));
            sb.append(Character.toUpperCase(Character.forDigit(b & 0x0F, 16)));
        }
        return sb.toString();
    }

    private static int nibble(char c) {
        if (c == '=') {
            return 0x0D;
        }
        int v = Character.digit(c, 16);
        if (v < 0) {
            throw new IllegalArgumentException("Invalid BCD character: '" + c + "'");
        }
        return v;
    }
}
