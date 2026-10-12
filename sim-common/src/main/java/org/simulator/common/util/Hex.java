package org.simulator.common.util;

import java.util.HexFormat;

public final class Hex {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private Hex() {
    }

    public static String encode(byte[] data) {
        return data == null ? "" : HEX.formatHex(data);
    }

    public static byte[] decode(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        String clean = hex.replaceAll("\\s", "");
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length: " + clean.length());
        }
        return HEX.parseHex(clean);
    }

    /** Classic 16-bytes-per-line hex dump with ASCII column. */
    public static String dump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int offset = 0; offset < data.length; offset += 16) {
            sb.append(String.format("%04X  ", offset));
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (offset + i < data.length) {
                    int b = data[offset + i] & 0xFF;
                    sb.append(String.format("%02X ", b));
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
                if (i == 7) {
                    sb.append(' ');
                }
            }
            sb.append(" |").append(ascii).append("|\n");
        }
        return sb.toString();
    }
}
