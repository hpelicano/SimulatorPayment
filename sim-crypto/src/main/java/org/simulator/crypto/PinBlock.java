package org.simulator.crypto;

import org.simulator.common.util.Hex;

import java.security.SecureRandom;

/** ISO 9564-1 PIN blocks (formats 0, 1 and 3), clear and under a ZPK. */
public final class PinBlock {

    private static final SecureRandom RND = new SecureRandom();

    private PinBlock() {}

    public static String clear(int format, String pin, String pan) {
        if (pin == null || !pin.matches("\\d{4,12}")) throw new IllegalArgumentException("PIN debe tener 4-12 dígitos");
        StringBuilder sb = new StringBuilder().append(format).append(Integer.toHexString(pin.length()).toUpperCase()).append(pin);
        while (sb.length() < 16) {
            sb.append(switch (format) {
                case 0 -> 'F';
                case 1 -> Character.toUpperCase(Character.forDigit(RND.nextInt(16), 16));
                case 3 -> (char) ('A' + RND.nextInt(6));
                default -> throw new IllegalArgumentException("Formato de PIN block no soportado: " + format);
            });
        }
        if (format == 1) return sb.toString();
        return Hex.encode(Ciphers.xor(Hex.decode(sb.toString()), Hex.decode(panField(pan))));
    }

    public static String encrypt(byte[] zpk, int format, String pin, String pan) {
        return Hex.encode(Ciphers.tdes(zpk, Hex.decode(clear(format, pin, pan)), true));
    }

    /** Decrypts and decodes a PIN block, returning the clear PIN. */
    public static String decryptPin(byte[] zpk, String encryptedHex, String pan) {
        return decodeClear(Hex.encode(Ciphers.tdes(zpk, Hex.decode(encryptedHex), false)), pan);
    }

    public static String decodeClear(String clearHex, String pan) {
        String block = clearHex.toUpperCase();
        char f = block.charAt(0);
        if (f == '0' || f == '3') {
            if (pan == null) throw new IllegalArgumentException("PAN requerido para formato " + f);
            block = Hex.encode(Ciphers.xor(Hex.decode(block), Hex.decode(panField(pan))));
            f = block.charAt(0);
        }
        int len = Character.digit(block.charAt(1), 16);
        if ("013".indexOf(f) < 0 || len < 4 || len > 12) throw new IllegalArgumentException("PIN block inválido");
        return block.substring(2, 2 + len);
    }

    /** 0000 + 12 rightmost PAN digits excluding the check digit. */
    static String panField(String pan) {
        String digits = pan == null ? "" : pan.replaceAll("\\D", "");
        if (digits.length() < 13) throw new IllegalArgumentException("PAN inválido para PIN block");
        return "0000" + digits.substring(digits.length() - 13, digits.length() - 1);
    }
}
