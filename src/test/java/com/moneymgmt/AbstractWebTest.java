package com.moneymgmt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Boots the whole app on an in-memory H2 database with a made-up test login. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractWebTest {

    protected static final String USER = "tester";
    protected static final String PASSWORD = "correct-horse-battery";

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        String hash = new BCryptPasswordEncoder(4).encode(PASSWORD);
        registry.add("app.username", () -> USER);
        registry.add("app.password-hash", () -> hash);
    }

    @Autowired
    protected MockMvc mvc;
}
