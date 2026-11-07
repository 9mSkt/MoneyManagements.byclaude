package com.moneymgmt;

import com.moneymgmt.config.DataCopyTool;
import com.moneymgmt.config.DatabaseUrl;
import com.moneymgmt.security.TotpService;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
@ConfigurationPropertiesScan
@org.springframework.scheduling.annotation.EnableScheduling
public class MoneyApplication {

    static final String LOCAL_DB = "jdbc:h2:file:./data/money;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    public static void main(String[] args) throws Exception {
        String command = args.length >= 1 ? args[0] : "";
        switch (command) {
            // "hash-password <password>" prints a BCrypt hash for APP_PASSWORD_HASH.
            case "hash-password" -> {
                if (args.length != 2 || args[1].length() < 12) {
                    System.err.println("Usage: hash-password <password>   (use at least 12 characters)");
                    System.exit(1);
                }
                System.out.println(new BCryptPasswordEncoder(12).encode(args[1]));
            }
            // "totp-secret" prints a new secret for APP_TOTP_SECRET (two-step sign-in) and how to add it to an app.
            case "totp-secret" -> {
                String secret = TotpService.newSecret();
                System.out.println("APP_TOTP_SECRET=" + secret);
                System.out.println();
                System.out.println("In Google Authenticator (or similar): Add a code > Enter a setup key,");
                System.out.println("account name: Money Management, key: " + secret + ", type: Time based.");
                System.out.println("Or open this link on the phone: otpauth://totp/Money%20Management?secret=" + secret
                        + "&issuer=Money%20Management");
            }
            // "copy-data [--replace]" copies the local H2 data to the database in DATABASE_URL (Neon).
            case "copy-data" -> System.exit(copyData(Arrays.copyOfRange(args, 1, args.length)));
            default -> SpringApplication.run(MoneyApplication.class, args);
        }
    }

    private static int copyData(String[] args) {
        boolean replace = Arrays.asList(args).contains("--replace");
        String from = Arrays.stream(args).filter(a -> a.startsWith("--from=")).map(a -> a.substring(7))
                .findFirst().orElse(LOCAL_DB);
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) {
            System.err.println("Set DATABASE_URL first (the Neon connection string), then run copy-data again.");
            return 1;
        }
        try {
            DatabaseUrl target = DatabaseUrl.parse(url, System.getenv("DATABASE_USERNAME"), System.getenv("DATABASE_PASSWORD"));
            System.out.println("Copying " + from.replaceAll(";.*", "") + " to " + target.jdbcUrl().replaceAll("\\?.*", ""));
            int rows = new DataCopyTool(System.out).copy(new DataCopyTool.Db(from, "sa", ""),
                    new DataCopyTool.Db(target.jdbcUrl(), target.username(), target.password()), replace);
            System.out.println("Done: " + rows + " rows copied.");
            return 0;
        } catch (Exception e) {
            System.err.println("Copy failed: " + e.getMessage());
            return 1;
        }
    }
}
