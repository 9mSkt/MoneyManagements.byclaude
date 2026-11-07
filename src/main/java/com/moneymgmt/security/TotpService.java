package com.moneymgmt.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Optional two-step sign-in: a 6-digit code from an authenticator app (RFC 6238 TOTP, SHA-1, 30 s).
 * On only when APP_TOTP_SECRET (base32) is set. A code can be used once.
 */
@Service
public class TotpService {

    private static final int STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final byte[] key;
    private final Clock clock;
    private final AtomicLong lastUsedStep = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public TotpService(@Value("${app.totp-secret:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    TotpService(String secret, Clock clock) {
        String s = secret == null ? "" : secret.replace(" ", "").trim();
        this.key = s.isEmpty() ? null : decode(s);
        if (key != null && key.length < 10) {
            throw new IllegalStateException("APP_TOTP_SECRET is too short. Create one with: totp-secret");
        }
        this.clock = clock;
    }

    public boolean isEnabled() {
        return key != null;
    }

    /** True for the current code (or the one just before/after it, for a clock a little off), once. */
    public boolean verify(String code) {
        if (!isEnabled()) {
            return true;
        }
        if (code == null) {
            return false;
        }
        String c = code.replace(" ", "").trim();
        if (!c.matches("\\d{6}")) {
            return false;
        }
        long now = clock.millis() / 1000 / STEP_SECONDS;
        for (long step = now - 1; step <= now + 1; step++) {
            if (MessageDigest.isEqual(codeAt(key, step).getBytes(), c.getBytes())) {
                long last = lastUsedStep.get();
                if (step <= last || !lastUsedStep.compareAndSet(last, step)) {
                    return false;   // already used (replay)
                }
                return true;
            }
        }
        return false;
    }

    static String codeAt(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new random secret (20 bytes, base32) for APP_TOTP_SECRET. */
    public static String newSecret() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        return encode(b);
    }

    static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte d : data) {
            buffer = (buffer << 8) | (d & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    static byte[] decode(String text) {
        String s = text.toUpperCase().replace("=", "");
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char ch : s.toCharArray()) {
            int v = BASE32.indexOf(ch);
            if (v < 0) {
                throw new IllegalStateException("APP_TOTP_SECRET is not valid base32 (letters A-Z and digits 2-7).");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        byte[] b = new byte[out.position()];
        out.flip();
        out.get(b);
        return b;
    }
}
