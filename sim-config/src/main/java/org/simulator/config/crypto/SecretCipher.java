package org.simulator.config.crypto;

import org.simulator.common.SimulatorException;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts configuration secrets with AES-256-GCM using a key derived from a master password
 * (PBKDF2-HMAC-SHA256). Encrypted values are stored as {@code ENC(base64(salt|iv|ciphertext+tag))}.
 */
public final class SecretCipher {

    private static final String PREFIX = "ENC(";
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int ITERATIONS = 310_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final char[] masterPassword;

    public SecretCipher(char[] masterPassword) {
        if (masterPassword == null || masterPassword.length < 8) {
            throw new SimulatorException("Master password must have at least 8 characters");
        }
        this.masterPassword = masterPassword.clone();
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX) && value.endsWith(")");
    }

    public String encrypt(String plain) {
        try {
            byte[] salt = random(SALT_LEN);
            byte[] iv = random(IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(SALT_LEN + IV_LEN + ct.length).put(salt).put(iv).put(ct);
            return PREFIX + Base64.getEncoder().encodeToString(buf.array()) + ")";
        } catch (Exception e) {
            throw new SimulatorException("Cannot encrypt secret", e);
        }
    }

    /** Returns the plain value; non encrypted values are returned unchanged. */
    public String decrypt(String value) {
        if (!isEncrypted(value)) {
            return value;
        }
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length(), value.length() - 1));
            byte[] salt = Arrays.copyOfRange(all, 0, SALT_LEN);
            byte[] iv = Arrays.copyOfRange(all, SALT_LEN, SALT_LEN + IV_LEN);
            byte[] ct = Arrays.copyOfRange(all, SALT_LEN + IV_LEN, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new SimulatorException("Invalid master password or tampered secret");
        } catch (Exception e) {
            throw new SimulatorException("Cannot decrypt secret", e);
        }
    }

    private SecretKeySpec deriveKey(byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(masterPassword, salt, ITERATIONS, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] random(int len) {
        byte[] b = new byte[len];
        RANDOM.nextBytes(b);
        return b;
    }
}
