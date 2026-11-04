package com.moneymgmt.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Service;

/** Locks a username after too many wrong passwords in a row. Kept in memory (one user, one instance). */
@Service
public class LoginAttemptService {

    private record Attempts(int failures, Instant lockedUntil) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration lockout;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LoginAttemptService(AppSecurityProperties props) {
        this(props.security().maxFailedLogins(), Duration.ofMinutes(props.security().lockoutMinutes()), Clock.systemUTC());
    }

    LoginAttemptService(int maxFailures, Duration lockout, Clock clock) {
        this.maxFailures = maxFailures;
        this.lockout = lockout;
        this.clock = clock;
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        String user = key(event.getAuthentication().getName());
        attempts.compute(user, (k, a) -> {
            int failures = (a == null || isExpired(a) ? 0 : a.failures()) + 1;
            Instant until = failures >= maxFailures ? clock.instant().plus(lockout) : null;
            return new Attempts(failures, until);
        });
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        attempts.remove(key(event.getAuthentication().getName()));
    }

    public boolean isLocked(String username) {
        Attempts a = attempts.get(key(username));
        return a != null && a.lockedUntil() != null && clock.instant().isBefore(a.lockedUntil());
    }

    private boolean isExpired(Attempts a) {
        return a.lockedUntil() != null && !clock.instant().isBefore(a.lockedUntil());
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
