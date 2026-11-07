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

Tests use made-up sample data only: an in-memory H2 database, plus one test on a real PostgreSQL that the build
starts by itself (no Docker needed) to check the app works the same way on Neon.

## Importing your Google Sheet

Download the sheet as `.xlsx` (File → Download → Microsoft Excel) and upload it on **Settings → Import**.
The file is read in memory and never stored or committed.

## Profiles

| Profile | Database | Used for |
| --- | --- | --- |
| `local` (default) | H2 file in `data/` | Development |
| `prod` | PostgreSQL (Neon) from `DATABASE_URL` | Render deployment |

## Environment variables

All secrets come from environment variables (local `.env`, or the Render dashboard). Never commit them.

| Variable | Needed | What it is |
| --- | --- | --- |
| `APP_USERNAME` | yes | Sign-in name |
| `APP_PASSWORD_HASH` | yes | BCrypt hash from `hash-password` |
| `DATABASE_URL` | online | Neon connection string as Neon shows it (`postgresql://user:password@host/db?sslmode=require`), or a `jdbc:postgresql://` URL with `DATABASE_USERNAME` and `DATABASE_PASSWORD` |
| `SPRING_PROFILES_ACTIVE` | online | `prod` (set by the Dockerfile and `render.yaml`) |
| `APP_TOTP_SECRET` | no | Turns on the two-step code at sign-in (see below) |
| `GOOGLE_COPY_SHEET_ID` | no | The separate Google Sheet that receives the daily copy |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | no | Key of the Google service account that writes the copy (the JSON as is, or base64) |

## Deploy online (Render + Neon, free)

1. **Neon (database):** sign up at https://neon.tech, create a project (region *Asia Pacific (Singapore)*), and copy
   the connection string from *Connect* (it starts with `postgresql://`).
2. **Move your local data to Neon (once):** stop the local app, then in PowerShell in the project folder:

   ```
   $env:DATABASE_URL="paste-the-neon-connection-string"
   .\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=copy-data"
   ```

   It creates the tables in Neon and copies every table from `data/money`. It refuses to overwrite a Neon database
   that already has transactions; add ` --replace` after `copy-data` to overwrite on purpose.
3. **Render (app):** sign up at https://render.com with GitHub, choose *New → Blueprint*, and pick this repository.
   Render reads `render.yaml` and asks for the secret values: `DATABASE_URL` (from step 1), `APP_USERNAME`,
   `APP_PASSWORD_HASH`, and optionally the others in the table above (leave them empty to skip).
4. Wait for the first build (5–10 minutes), then open the `https://….onrender.com` address and sign in. On a phone,
   use *Add to Home Screen*.

Every push to `main` deploys again. The free app sleeps after 15 minutes without visits; the first visit after that
takes about a minute.

## Backups

- **Neon** keeps a restore history of your database (on the free plan, a few hours back).
- **Daily Google Sheet copy** (below): all tables, once a day.
- **Reports → Download your data:** a full Excel backup or the transactions as CSV, whenever you like.

## Daily Google Sheet copy

Once a day the app copies every table into a separate Google Sheet (one tab per table). Your original money sheet is
never touched. Setup, once:

1. Create an empty Google Sheet for the copy. Its ID is the long part of the link between `/d/` and `/edit`; put it
   in `GOOGLE_COPY_SHEET_ID`.
2. In https://console.cloud.google.com create a project, enable the **Google Sheets API**, then under
   *IAM & Admin → Service accounts* create a service account and add a key (*Keys → Add key → JSON*). Paste the whole
   JSON file content into `GOOGLE_SERVICE_ACCOUNT_JSON`, then delete the downloaded file.
3. Share the copy sheet with the service account's e-mail (shown on **Settings**) as *Editor*.

**Settings → Daily copy** shows the last copy and any error, and has a *Copy now* button. Because the free app sleeps,
the copy runs the first time the app is awake each day (it checks every hour).

## Two-step sign-in (optional)

1. Create a secret: `.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=totp-secret"`
2. Add it to Google Authenticator (or a similar app) with *Enter a setup key*, as the command explains.
3. Set `APP_TOTP_SECRET` to the secret (in `.env`, or in Render) and restart. Sign-in then asks for the 6-digit code.
