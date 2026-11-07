package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** A small piece of app state, e.g. when the daily Google Sheet copy last ran. */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "setting_key")
    private String key;

    @Column(name = "setting_value")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppSetting() {
    }

    public AppSetting(String key, String value) {
        this.key = key;
        set(value);
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void set(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }
}
