package com.moneymgmt.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.util.StringUtils;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/vendor/**", "/icons/**",
                        "/manifest.webmanifest", "/favicon.ico", "/error").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .failureHandler((request, response, ex) -> {
                    String reason = ex instanceof org.springframework.security.authentication.LockedException
                            ? "locked" : "error";
                    new SimpleUrlAuthenticationFailureHandler("/login?" + reason)
                            .onAuthenticationFailure(request, response, ex);
                })
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .deleteCookies("JSESSIONID"))
            .sessionManagement(session -> session
                .invalidSessionUrl("/login?timeout"))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                        + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                        + "font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; "
                        + "frame-ancestors 'none'; form-action 'self'"))
                .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService userDetailsService(AppSecurityProperties props, LoginAttemptService attempts) {
        if (!StringUtils.hasText(props.username()) || !StringUtils.hasText(props.passwordHash())) {
            throw new IllegalStateException(
                    "Set APP_USERNAME and APP_PASSWORD_HASH (environment variables or a local .env file). "
                    + "Create the hash with: hash-password <your password>");
        }
        if (!props.passwordHash().startsWith("$2")) {
            throw new IllegalStateException("APP_PASSWORD_HASH must be a BCrypt hash (starts with $2).");
        }
        return username -> {
            if (!props.username().equalsIgnoreCase(username == null ? "" : username.trim())) {
                throw new UsernameNotFoundException("Unknown user");
            }
            return User.withUsername(props.username())
                    .password(props.passwordHash())
                    .roles("OWNER")
                    .accountLocked(attempts.isLocked(username))
                    .build();
        };
    }
}
