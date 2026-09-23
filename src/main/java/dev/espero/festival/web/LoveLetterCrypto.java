package dev.espero.festival.web;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypts personal fields before persistence; no plaintext or key in diagnostic output. */
@Component
public class LoveLetterCrypto {
    private final byte[] key;
    private final String version;
    private final SecureRandom random = new SecureRandom();

    public LoveLetterCrypto(@Value("${festival.love-letter.key-base64:}") String encoded,
                            @Value("${festival.love-letter.key-version:v1}") String version) {
        byte[] decoded;
        try {
            decoded = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            decoded = null;
        }
        this.key = decoded != null && decoded.length == 32 ? decoded : null;
        this.version = version;
    }

    public boolean configured() { return key != null; }
    public String version() { return version; }

    public String encrypt(String input) {
        if (key == null) throw unavailable();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(ciphertext, 0, packed, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Love letter encryption failed");
        }
    }

    public String decrypt(String encoded, String keyVersion) {
        if (key == null || !version.equals(keyVersion)) throw unavailable();
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            if (packed.length < 29) throw new GeneralSecurityException("Invalid ciphertext");
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("Love letter decryption failed");
        }
    }

    public String hmac(String input) {
        if (key == null) throw unavailable();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Love letter request digest failed");
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Love letter key is not configured");
    }
}
