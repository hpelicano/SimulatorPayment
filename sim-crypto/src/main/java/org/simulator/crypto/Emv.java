package org.simulator.crypto;

import org.simulator.common.util.Hex;
import org.simulator.iso.codec.Tlv;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** EMV key derivation and application cryptograms (ARQC / ARPC method 1). */
public final class Emv {

    public enum SessionKey { NONE, EMV_CSK }
    public enum IadMode { CVR, FULL, NONE }

    /** Tags concatenated (in order) to build the ARQC input, per EMV Book 2 recommended minimum. */
    public static final List<String> ARQC_TAGS = List.of("9F02", "9F03", "9F1A", "95", "5F2A", "9A", "9C", "9F37", "82", "9F36");

    private Emv() {}

    /** ICC master key derivation, option A (EMV Book 2 A1.4.1). */
    public static byte[] deriveIccMasterKey(byte[] imk, String pan, String psn) {
        String y = (pan.replaceAll("\\D", "") + (psn == null || psn.isBlank() ? "00" : psn));
        y = y.length() >= 16 ? y.substring(y.length() - 16) : "0".repeat(16 - y.length()) + y;
        byte[] yb = Hex.decode(y);
        byte[] zl = Ciphers.tdes(imk, yb, true);
        byte[] inv = new byte[8];
        for (int i = 0; i < 8; i++) inv[i] = (byte) ~yb[i];
        byte[] zr = Ciphers.tdes(imk, inv, true);
        byte[] mk = new byte[16];
        System.arraycopy(zl, 0, mk, 0, 8);
        System.arraycopy(zr, 0, mk, 8, 8);
        return Ciphers.oddParity(mk);
    }

    /** EMV common session key derivation (EMV Book 2 A1.3) using the ATC. */
    public static byte[] sessionKey(byte[] mk, String atcHex) {
        byte[] atc = Hex.decode(atcHex);
        byte[] r = new byte[8];
        System.arraycopy(atc, 0, r, 0, 2);
        r[2] = (byte) 0xF0;
        byte[] left = Ciphers.tdes(mk, r, true);
        r[2] = 0x0F;
        byte[] right = Ciphers.tdes(mk, r, true);
        byte[] sk = new byte[16];
        System.arraycopy(left, 0, sk, 0, 8);
        System.arraycopy(right, 0, sk, 8, 8);
        return Ciphers.oddParity(sk);
    }

    /** Builds the ARQC input from DE55 TLV data. */
    public static byte[] arqcData(Map<String, String> tags, IadMode iad) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String t : ARQC_TAGS) {
            String v = tags.get(t);
            if (v != null) out.writeBytes(Hex.decode(v));
        }
        String iadHex = tags.get("9F10");
        if (iadHex != null && iad != IadMode.NONE) {
            byte[] b = Hex.decode(iadHex);
            out.writeBytes(iad == IadMode.FULL || b.length < 7 ? b : Arrays.copyOfRange(b, 3, 7));
        }
        return out.toByteArray();
    }

    public static byte[] cardKey(byte[] imk, String pan, String psn, String atc, SessionKey mode) {
        byte[] mk = deriveIccMasterKey(imk, pan, psn);
        return mode == SessionKey.EMV_CSK ? sessionKey(mk, atc) : mk;
    }

    public static String arqc(byte[] key, Map<String, String> tags, SessionKey mode, IadMode iad) {
        byte[] data = arqcData(tags, iad);
        byte[] padded = mode == SessionKey.EMV_CSK ? Mac.pad2(data) : Mac.pad1(data);
        return Hex.encode(Mac.retail(key, padded));
    }

    /** ARPC method 1: TDES(key, ARQC XOR (ARC || 00..00)). */
    public static String arpc(byte[] key, String arqcHex, String arcHex) {
        byte[] y = Hex.decode(arqcHex);
        byte[] arc = Hex.decode(arcHex);
        for (int i = 0; i < arc.length && i < 8; i++) y[i] ^= arc[i];
        return Hex.encode(Ciphers.tdes(key, y, true));
    }

    public static Map<String, String> tags(String de55Hex) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Tlv.Entry e : Tlv.parse(de55Hex)) m.put(e.tag().toUpperCase(), e.value());
        return m;
    }
}
