package org.simulator.crypto;

import org.simulator.common.util.Hex;

import java.util.Arrays;

/** Visa CVV / CVV2 / iCVV computation (same algorithm, different service code/expiry inputs). */
public final class Cvv {

    private Cvv() {}

    /**
     * @param cvk     KA||KB (16 bytes)
     * @param expiry  YYMM
     * @param service 3-digit service code ("000" for CVV2, "999" for iCVV)
     */
    public static String compute(byte[] cvk, String pan, String expiry, String service) {
        StringBuilder data = new StringBuilder(pan).append(expiry).append(service);
        while (data.length() < 32) data.append('0');
        byte[] b1 = Hex.decode(data.substring(0, 16));
        byte[] b2 = Hex.decode(data.substring(16, 32));
        byte[] ka = Arrays.copyOfRange(cvk, 0, 8);
        byte[] r = Ciphers.des(ka, b1, true);
        r = Ciphers.tdes(Arrays.copyOf(cvk, 16), Ciphers.xor(r, b2), true);
        String hex = Hex.encode(r).toUpperCase();
        StringBuilder out = new StringBuilder();
        for (char c : hex.toCharArray()) if (Character.isDigit(c)) out.append(c);
        for (char c : hex.toCharArray()) if (!Character.isDigit(c)) out.append((char) ('0' + (c - 'A')));
        return out.substring(0, 3);
    }
}
