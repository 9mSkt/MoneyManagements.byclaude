# Money Management Web App

Personal finance web app for one user in Thailand (amounts in Thai Baht ฿). The full, approved design is in `docs/DESIGN.md`. Read it before starting any phase and follow it; if something in it seems wrong, ask the owner before changing direction.

## How we work

- Build in the 6 phases listed in `docs/DESIGN.md`, in order. Finish one phase, make sure it builds and runs, then stop and ask the owner to review before starting the next.
- At the end of each phase: run the tests, commit with a clear message (`Phase N: ...`), push to `main`, and tick the phase in the Progress list below.
- The owner reviews on a phone and a PC, so check every page at phone width (about 380 px) as well as desktop.

## Progress

- [x] Phase 1: setup, login, theme, EN/TH switch, layout shell, schema, sheet import, Dashboard mockup
- [x] Phase 2: transactions, fixed costs and recurring, installments, monthly incentive entry
- [x] Phase 3: dashboard charts, budget planner (actual + goal, red over-budget)
- [x] Phase 4: investment portfolio
- [x] Phase 5: yearly Thai tax planner
- [ ] Phase 6: goals, accounts, reports, daily Google Sheet copy, tests, deploy (Render + Neon)

## Tech rules

- Java 21, Spring Boot 3, Maven, Thymeleaf + HTMX, Apache ECharts, Spring Data JPA, Flyway, Spring Security.
- Money is always `BigDecimal` (scale 2, HALF_UP). Never `double` or `float` for amounts.
- Profiles: `local` uses H2 (file) for development; `prod` uses PostgreSQL (Neon) from environment variables.
- Database changes only through Flyway migrations in `src/main/resources/db/migration`.
- Every user-facing string goes through i18n: `messages_en.properties` and `messages_th.properties`. No hard-coded UI text. Category names are stored in both EN and TH.
- Colours come only from CSS variables defined once for light and dark themes (palette in `docs/DESIGN.md`). Fonts: Inter + Noto Sans Thai, tabular numbers for amounts.
- Single user: credentials come from environment variables (`APP_USERNAME`, `APP_PASSWORD_HASH`), never from code.

## Privacy and secrets (important)

This repo may be public. It must never contain the owner's financial data or secrets.

- Never commit the Google Sheet ID or link, exported sheet files, database dumps, real transaction data, passwords, API keys or service-account JSON.
- Real data is imported at runtime: the owner downloads the sheet as `.xlsx` and uploads it on the Settings → Import page (parsed with Apache POI). Any local copies go in `data/`, which is git-ignored.
- Tests use made-up sample data only.
- Secrets live in environment variables (local `.env`, git-ignored; Render dashboard in production).
