package com.moneymgmt.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LoginFlowTest extends AbstractWebTest {

    @Test
    void pagesRequireLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void loginPageIsPublicAndTranslated() throws Exception {
        mvc.perform(get("/login").param("lang", "th"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("เข้าสู่ระบบ")));
    }

    @Test
    void correctPasswordLogsIn() throws Exception {
        mvc.perform(formLogin("/login").user(USER).password(PASSWORD))
                .andExpect(authenticated())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(formLogin("/login").user(USER).password("nope"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void fiveWrongPasswordsLockTheAccount() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(formLogin("/login").user(USER).password("wrong-" + i)).andExpect(unauthenticated());
        }
        mvc.perform(formLogin("/login").user(USER).password(PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?locked"));
    }
}
