package org.simulator.crypto;

import org.simulator.common.util.Hex;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Low level DES/TDES/AES block operations (ECB, no padding) plus helpers. */
public final class Ciphers {

    private Ciphers() {}

    public static byte[] des(byte[] key8, byte[] data, boolean encrypt) {
        return run("DES/ECB/NoPadding", "DES", Arrays.copyOf(key8, 8), data, encrypt);
    }

    public static byte[] tdes(byte[] key, byte[] data, boolean encrypt) {
        return run("DESede/ECB/NoPadding", "DESede", key24(key), data, encrypt);
    }

    public static byte[] aes(byte[] key, byte[] data, boolean encrypt) {
        return run("AES/ECB/NoPadding", "AES", key, data, encrypt);
    }

    /** Expands a 2-key TDES key (K1K2) to 3-key form (K1K2K1) as required by JCE. */
    public static byte[] key24(byte[] key) {
        if (key.length == 24) return key;
        if (key.length == 16) {
            byte[] k = Arrays.copyOf(key, 24);
            System.arraycopy(key, 0, k, 16, 8);
            return k;
        }
        if (key.length == 8) {
            byte[] k = new byte[24];
            for (int i = 0; i < 3; i++) System.arraycopy(key, 0, k, i * 8, 8);
            return k;
        }
        throw new IllegalArgumentException("Invalid TDES key length: " + key.length + " bytes");
    }

    public static byte[] xor(byte[] a, byte[] b) {
        byte[] r = new byte[Math.min(a.length, b.length)];
        for (int i = 0; i < r.length; i++) r[i] = (byte) (a[i] ^ b[i]);
        return r;
    }

    /** Key check value: first 3 bytes of the key encrypting a zero block (6 hex chars). */
    public static String kcv(byte[] key, boolean aes) {
        byte[] enc = aes ? aes(key, new byte[16], true) : tdes(key, new byte[8], true);
        return Hex.encode(Arrays.copyOf(enc, 3));
    }

    /** Forces odd parity on every byte of a DES key. */
    public static byte[] oddParity(byte[] key) {
        byte[] r = key.clone();
        for (int i = 0; i < r.length; i++) {
            int b = r[i] & 0xFE;
            r[i] = (byte) (Integer.bitCount(b) % 2 == 0 ? b | 1 : b);
        }
        return r;
    }

    public static byte[] parseKey(String hex, String name) {
        if (hex == null || hex.isBlank()) throw new IllegalStateException("Clave '" + name + "' no configurada");
        String h = hex.replace(" ", "").trim();
        if (!h.matches("[0-9A-Fa-f]+") || !(h.length() == 16 || h.length() == 32 || h.length() == 48 || h.length() == 64)) {
            throw new IllegalArgumentException("Clave '" + name + "' inválida: se esperan 16/32/48/64 hex");
        }
        return Hex.decode(h);
    }

    private static byte[] run(String transformation, String alg, byte[] key, byte[] data, boolean encrypt) {
        try {
            Cipher c = Cipher.getInstance(transformation);
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, alg));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(transformation + " failed: " + e.getMessage(), e);
        }
    }
}
