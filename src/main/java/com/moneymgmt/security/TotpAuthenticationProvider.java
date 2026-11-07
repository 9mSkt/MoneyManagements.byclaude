package com.moneymgmt.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/** Password check, then (when two-step sign-in is on) the 6-digit code typed on the login page. */
public class TotpAuthenticationProvider extends DaoAuthenticationProvider {

    /** Login request details plus the "code" field. */
    public static class Details extends WebAuthenticationDetails {
        private final String code;

        public Details(HttpServletRequest request) {
            super(request);
            this.code = request.getParameter("code");
        }

        public String getCode() {
            return code;
        }
    }

    public static final AuthenticationDetailsSource<HttpServletRequest, Details> DETAILS = Details::new;

    private final TotpService totp;

    public TotpAuthenticationProvider(UserDetailsService users, PasswordEncoder encoder, TotpService totp) {
        super(users);
        setPasswordEncoder(encoder);
        this.totp = totp;
    }

    @Override
    protected void additionalAuthenticationChecks(UserDetails user, UsernamePasswordAuthenticationToken auth)
            throws AuthenticationException {
        super.additionalAuthenticationChecks(user, auth);   // the password first
        if (totp.isEnabled()) {
            String code = auth.getDetails() instanceof Details d ? d.getCode() : null;
            if (!totp.verify(code)) {
                // Same answer as a wrong password, and it counts toward the lockout.
                throw new BadCredentialsException("Wrong code");
            }
        }
    }
}
