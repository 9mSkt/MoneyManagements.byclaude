# MoneyManagements.byclaude

Personal finance web app (Thai Baht ฿) built with Java 21, Spring Boot 3, Thymeleaf + HTMX and Apache ECharts.
The design and build plan are in [`docs/DESIGN.md`](docs/DESIGN.md).

## Run locally

Needs Java 21. Maven is not needed — use the wrapper (`mvnw` / `mvnw.cmd`).

1. Create your password hash (pick a password of 12+ characters):

   ```
   mvnw.cmd -q spring-boot:run -Dspring-boot.run.arguments="hash-password YOUR_PASSWORD"
   ```

2. Copy `.env.example` to `.env` (git-ignored) and fill in `APP_USERNAME` and the hash from step 1.
3. Start the app:

   ```
   mvnw.cmd spring-boot:run
   ```

4. Open http://localhost:8080 and sign in.

Local data is stored in an H2 file database under `data/` (git-ignored).

**Phone on the same Wi-Fi:** open `http://<your PC's IP>:8080` (find the IP with `ipconfig`) and allow Java through
Windows Firewall when asked.

## Tests

```
mvnw.cmd verify
```

Tests use an in-memory database and made-up sample data only.

## Importing your Google Sheet

Download the sheet as `.xlsx` (File → Download → Microsoft Excel) and upload it on **Settings → Import**.
The file is read in memory and never stored or committed.

## Profiles

| Profile | Database | Used for |
| --- | --- | --- |
| `local` (default) | H2 file in `data/` | Development |
| `prod` | PostgreSQL (Neon) from `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Render deployment |

Secrets come only from environment variables: `APP_USERNAME`, `APP_PASSWORD_HASH` (BCrypt) and the database settings.
