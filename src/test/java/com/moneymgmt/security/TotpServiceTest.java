package com.moneymgmt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    /** RFC 6238 test key "12345678901234567890" (SHA-1), as base32. */
    private static final String RFC_KEY = TotpService.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfcTestVectors() {
        byte[] key = TotpService.decode(RFC_KEY);
        // RFC 6238 8-digit codes 94287082 (T=59) and 07081804 (T=1111111109): the last 6 digits.
        assertThat(TotpService.codeAt(key, 59 / 30)).isEqualTo("287082");
        assertThat(TotpService.codeAt(key, 1111111109L / 30)).isEqualTo("081804");
    }

    @Test
    void acceptsTheCurrentCodeOnceAndRejectsOthers() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1111111109L), ZoneOffset.UTC);
        TotpService totp = new TotpService(RFC_KEY, clock);
        assertThat(totp.isEnabled()).isTrue();
        assertThat(totp.verify("000000")).isFalse();
        assertThat(totp.verify("08 1804")).isTrue();
        assertThat(totp.verify("081804")).isFalse();   // used already
        assertThat(totp.verify(null)).isFalse();
        assertThat(totp.verify("abc")).isFalse();
    }

    @Test
    void offWithoutASecret() {
        TotpService totp = new TotpService("", Clock.systemUTC());
        assertThat(totp.isEnabled()).isFalse();
        assertThat(totp.verify(null)).isTrue();
    }

    @Test
    void newSecretsRoundTrip() {
        String s = TotpService.newSecret();
        assertThat(s).matches("[A-Z2-7]{32}");
        assertThat(TotpService.encode(TotpService.decode(s))).isEqualTo(s);
        assertThatThrownBy(() -> new TotpService("NOT-BASE32!", Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }
}
