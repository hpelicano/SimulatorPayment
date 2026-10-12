package org.simulator.crypto;

import java.util.Arrays;

/** MAC algorithms used on ISO 8583 DE64/DE128 and EMV cryptograms. */
public final class Mac {

    public enum Algorithm { NONE, X919, ISO9797_ALG1, AES_CMAC }

    private Mac() {}

    public static byte[] compute(Algorithm alg, byte[] key, byte[] data) {
        return switch (alg) {
            case X919 -> retail(key, pad1(data));
            case ISO9797_ALG1 -> alg1(key, pad1(data));
            case AES_CMAC -> Arrays.copyOf(aesCmac(key, data), 8);
            case NONE -> throw new IllegalArgumentException("MAC algorithm NONE");
        };
    }

    /** ISO 9797-1 algorithm 1 with TDES as block cipher (full TDES CBC-MAC). */
    public static byte[] alg1(byte[] key, byte[] padded) {
        byte[] h = new byte[8];
        for (int i = 0; i < padded.length; i += 8) {
            h = Ciphers.tdes(key, Ciphers.xor(h, Arrays.copyOfRange(padded, i, i + 8)), true);
        }
        return h;
    }

    /** ISO 9797-1 algorithm 3 / ANSI X9.19 retail MAC: single DES CBC with K1, final D(K2) E(K1|K3). */
    public static byte[] retail(byte[] key, byte[] padded) {
        byte[] k = Ciphers.key24(key);
        byte[] k1 = Arrays.copyOfRange(k, 0, 8), k2 = Arrays.copyOfRange(k, 8, 16), k3 = Arrays.copyOfRange(k, 16, 24);
        byte[] h = new byte[8];
        for (int i = 0; i < padded.length; i += 8) {
            h = Ciphers.des(k1, Ciphers.xor(h, Arrays.copyOfRange(padded, i, i + 8)), true);
        }
        h = Ciphers.des(k2, h, false);
        return Ciphers.des(k3, h, true);
    }

    /** Padding method 1: zeros up to a multiple of 8 (at least one block). */
    public static byte[] pad1(byte[] data) {
        int len = data.length == 0 ? 8 : ((data.length + 7) / 8) * 8;
        return Arrays.copyOf(data, len);
    }

    /** Padding method 2: 0x80 followed by zeros. */
    public static byte[] pad2(byte[] data) {
        byte[] r = Arrays.copyOf(data, ((data.length + 8) / 8) * 8);
        r[data.length] = (byte) 0x80;
        return r;
    }

    /** AES-CMAC per RFC 4493 (full 16-byte tag). */
    public static byte[] aesCmac(byte[] key, byte[] data) {
        byte[] l = Ciphers.aes(key, new byte[16], true);
        byte[] k1 = shiftXor(l), k2 = shiftXor(k1);
        int n = Math.max(1, (data.length + 15) / 16);
        boolean complete = data.length > 0 && data.length % 16 == 0;
        byte[] last = new byte[16];
        int off = (n - 1) * 16;
        if (complete) {
            last = Ciphers.xor(Arrays.copyOfRange(data, off, off + 16), k1);
        } else {
            int rem = data.length - off;
            System.arraycopy(data, off, last, 0, rem);
            last[rem] = (byte) 0x80;
            last = Ciphers.xor(last, k2);
        }
        byte[] x = new byte[16];
        for (int i = 0; i < n - 1; i++) {
            x = Ciphers.aes(key, Ciphers.xor(x, Arrays.copyOfRange(data, i * 16, i * 16 + 16)), true);
        }
        return Ciphers.aes(key, Ciphers.xor(x, last), true);
    }

    private static byte[] shiftXor(byte[] in) {
        byte[] out = new byte[16];
        int carry = 0;
        for (int i = 15; i >= 0; i--) {
            int b = in[i] & 0xFF;
            out[i] = (byte) ((b << 1) | carry);
            carry = b >>> 7;
        }
        if ((in[0] & 0x80) != 0) out[15] ^= (byte) 0x87;
        return out;
    }
}
