package com.moneymgmt.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void neonStringBecomesJdbc() {
        // Made-up host and password, in the shape Neon shows them.
        var db = DatabaseUrl.parse("postgresql://money_owner:p%40ss-word@ep-sample-123.ap-southeast-1.aws.neon.tech/neondb"
                + "?sslmode=require&channel_binding=require", null, null);
        assertThat(db.jdbcUrl()).isEqualTo("jdbc:postgresql://ep-sample-123.ap-southeast-1.aws.neon.tech/neondb?sslmode=require");
        assertThat(db.username()).isEqualTo("money_owner");
        assertThat(db.password()).isEqualTo("p@ss-word");
    }

    @Test
    void jdbcUrlIsKeptWithSeparateCredentials() {
        var db = DatabaseUrl.parse("jdbc:postgresql://localhost:5432/money", "u", "p");
        assertThat(db.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/money");
        assertThat(db.username()).isEqualTo("u");
        assertThat(db.password()).isEqualTo("p");
    }

    @Test
    void portAndNoQuery() {
        var db = DatabaseUrl.parse("postgres://a:b@host:6543/db", null, null);
        assertThat(db.jdbcUrl()).isEqualTo("jdbc:postgresql://host:6543/db");
    }

    @Test
    void otherSchemesAreRefused() {
        assertThatThrownBy(() -> DatabaseUrl.parse("mysql://x/y", null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
