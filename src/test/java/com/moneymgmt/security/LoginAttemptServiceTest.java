package com.moneymgmt.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;

class LoginAttemptServiceTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService service = new LoginAttemptService(3, Duration.ofMinutes(15), clock);

    private void fail() {
        service.onFailure(new AuthenticationFailureBadCredentialsEvent(
                new TestingAuthenticationToken("Owner", "x"), new BadCredentialsException("bad")));
    }

    @Test
    void locksAfterLimitAndUnlocksAfterLockoutTime() {
        fail();
        fail();
        assertThat(service.isLocked("owner")).isFalse();
        fail();
        assertThat(service.isLocked("owner")).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertThat(service.isLocked("owner")).isFalse();
        fail();
        assertThat(service.isLocked("owner")).as("counter restarts after a lockout ends").isFalse();
    }

    @Test
    void successResetsTheCounter() {
        fail();
        fail();
        service.onSuccess(new AuthenticationSuccessEvent(new TestingAuthenticationToken("owner", "x")));
        fail();
        assertThat(service.isLocked("owner")).isFalse();
    }
}
