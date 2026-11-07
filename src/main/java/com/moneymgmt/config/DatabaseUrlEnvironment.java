package com.moneymgmt.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Lets DATABASE_URL be Neon's "postgresql://user:password@host/db" string, pasted as is. */
public class DatabaseUrlEnvironment implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String url = env.getProperty("DATABASE_URL");
        if (url == null || !(url.startsWith("postgres://") || url.startsWith("postgresql://"))) {
            return;
        }
        DatabaseUrl db = DatabaseUrl.parse(url, env.getProperty("DATABASE_USERNAME"), env.getProperty("DATABASE_PASSWORD"));
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", db.jdbcUrl());
        if (db.username() != null) {
            props.put("spring.datasource.username", db.username());
        }
        if (db.password() != null) {
            props.put("spring.datasource.password", db.password());
        }
        env.getPropertySources().addFirst(new MapPropertySource("databaseUrl", props));
    }
}
