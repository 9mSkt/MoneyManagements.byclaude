package com.moneymgmt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MoneyApplication {

    public static void main(String[] args) {
        // Helper: "hash-password <password>" prints a BCrypt hash for APP_PASSWORD_HASH and exits.
        if (args.length >= 1 && "hash-password".equals(args[0])) {
            if (args.length != 2 || args[1].length() < 12) {
                System.err.println("Usage: hash-password <password>   (use at least 12 characters)");
                System.exit(1);
            }
            System.out.println(new BCryptPasswordEncoder(12).encode(args[1]));
            return;
        }
        SpringApplication.run(MoneyApplication.class, args);
    }
}
