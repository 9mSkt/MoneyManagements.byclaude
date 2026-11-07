package com.moneymgmt.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Sign-in with two-step code on (made-up secret). */
@TestPropertySource(properties = "app.totp-secret=" + TwoStepLoginTest.SECRET)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TwoStepLoginTest extends AbstractWebTest {

    static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static String currentCode() {
        return TotpService.codeAt(TotpService.decode(SECRET), System.currentTimeMillis() / 1000 / 30);
    }

    @Test
    void loginPageAsksForTheCode() throws Exception {
        mvc.perform(get("/login").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("6-digit code")));
    }

    @Test
    void passwordAloneIsNotEnough() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", USER).param("password", PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("username", USER).param("password", PASSWORD).param("code", "000000"))
                .andExpect(unauthenticated());
    }

    @Test
    void passwordAndCodeLogIn() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", USER).param("password", PASSWORD)
                        .param("code", currentCode()))
                .andExpect(authenticated())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void wrongCodesCountTowardTheLockout() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login").with(csrf()).param("username", USER).param("password", PASSWORD)
                    .param("code", "000000")).andExpect(unauthenticated());
        }
        mvc.perform(post("/login").with(csrf()).param("username", USER).param("password", PASSWORD)
                        .param("code", currentCode()))
                .andExpect(redirectedUrl("/login?locked"));
    }
}
