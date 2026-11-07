# Money Management Web App – Design & Schedule

Approved Nov 4, 2026. Copied from the Claude Docs design doc; this file is now the source of truth for the build.

**Status: all 6 phases built. Live since Nov 7, 2026 at https://money-management-9bne.onrender.com**
(Render free plan, HTTPS, data in Neon PostgreSQL, Singapore). Sign-in required; one user.
What changed from the approved design while building is listed in [As built](#as-built-nov-7-2026) at the end.

## Overview

A Java (Spring Boot) web app that replaces the owner's "Money Management" Google Sheet: monthly income and costs, budgets, saving goals, investments and yearly Thai tax, with dashboards in Thai Baht (฿). Built in 6 phases from Wed Nov 4 to Sat Nov 7, 2026, with a review after each phase.

What the app takes from the sheet (the Renovate tab is left out):

| Tab | What it holds | Becomes in the app |
| --- | --- | --- |
| Jan26 … Nov26 | Income (salary, rent, phone, incentive), fixed costs, and 4 spending groups (Gas, Necessary, Shopping, Eat/Lifestyle), with installment plans (e.g. iPhone 1/20) and a 40/40/20 budget plan | Transactions, Installments, Monthly Budget, and a monthly Incentive entry typed in by hand (KPI %, incentive %, amount, optional bonus) |
| Summary Report | Year-by-month income vs outcome, budget scope after fixed cost (Deposit 10%, Investment 15%, Gasoline 15%, Necessary 15%, Shopping 15%, Eat 30%), saving account items, a DCA compound-growth estimate | Dashboard, Yearly Report, Budget Rules, Saving Goals, Investment Projection |
| Investment | Mutual fund (SCBS&P500, NAV + units), US ETFs (VGT, GLD) in USD with exchange rate, fees, dividends and withholding tax, plus a glossary of financial terms | Investment Portfolio |
| Tax | Salary + bonus, deductions (expenses, personal, social security, PVD, life insurance, RMF) and the Thai progressive brackets 0–35% | Yearly Tax Planner |

## Features

| # | Module | What the user can do | Source |
| --- | --- | --- | --- |
| 1 | Dashboard | This month at a glance: income, outcome, remaining, saving rate; budget used per group; 12-month income vs outcome trend; spending by category donut; upcoming installments and bills | Summary Report |
| 2 | Transactions | Add income or expense in under 10 seconds (quick-add form), edit, delete, search, filter by month, type, group and category; tags and notes. Each month has an Incentive entry filled in by hand (KPI %, incentive %, amount, optional bonus); no automatic calculation | Month tabs |
| 3 | Fixed costs and recurring | Define monthly items once (rent, car 9,897, insurance, bills, Mom) and the app posts them each month; the user adjusts amounts like the electric bill | Fix cost blocks |
| 4 | Installments (ผ่อน) | Record a purchase with full price, months and 0% or interest rate; show "3/6" progress, the monthly charge, and the remaining debt | Period column |
| 5 | Budget planner | Two budgets per month, both on income after fixed cost: the **actual** budget (Eat 40, Shopping 40, Gas 20) and the **goal** budget (Deposit 10, Investment 15, Gas 15, Necessary 15, Shopping 15, Eat 30). Each month's budget is copied from last month; edit it by % or fixed baht for that month only or for all later months. Bars turn amber at 80% and red over 100%, and transactions that push a group over budget are highlighted red | Budget Scope, Plan/Actual |
| 6 | Investment portfolio | Mutual funds (NAV, units) and stocks/ETFs (USD, FX rate, shares, fees); buy/sell history, average cost, current value, profit %, dividends and withholding tax; allocation chart; DCA projection (7,000/month at 10%). Prices (NAV, VGT, GLD, USD/THB) are typed in by the user | Investment, Investment Estimate |
| 7 | Yearly tax planner | Thai PIT calculation: income (salary × 12 + bonus), deductions (expense 100,000, personal 60,000, social security, PVD, life insurance, RMF/SSF/ThaiESG), bracket chart, tax due, and "how much more RMF to buy to save X baht" | Tax |
| 8 | Saving goals | Goals like Japan trip and Car maintain, each with target, saved amount, deadline and a progress bar | Saving Account |
| 9 | Accounts and net worth | Wallet, saving, investment and debt balances; net worth over time | Wallet, Saving rows |
| 10 | Reports and data | Yearly report, month-to-month compare, CSV/Excel export, one-time import from the Google Sheet, a daily copy of all data to a new Google Sheet (the original stays untouched) | New |

Also included:

- Secure login for one user only: HTTPS, strong password, optional two-step code (TOTP authenticator app), lockout after repeated wrong passwords, auto-logout when idle.
- Daily database backups.
- "Add to Home Screen" (PWA manifest) so it opens like an app on a phone.
- EN / TH switch in the top bar that changes every menu, label, message and category name; the choice is remembered.
- Dark/light theme and a phone-friendly layout.

## Visual design

A calm, modern look: soft off-white or deep slate backgrounds, muted sage-teal as the main colour, no pure black, so long sessions stay easy on the eyes. Each colour has a fixed meaning across every page and chart.

| Role | Light theme | Dark theme | Used for |
| --- | --- | --- | --- |
| Background | #F6F5F1 warm off-white | #15191E deep slate | Page |
| Card surface | #FFFFFF with soft shadow | #1E242B | Cards, tables |
| Text | #2A2F36 | #E3E6EA | Body text |
| Primary | #3E8E7E sage teal | #5BB3A1 | Buttons, active menu |
| Income | #4E9F76 soft green | #6CC096 | Income amounts, bars |
| Expense | #D9776A muted coral | #E8968A | Outcome amounts, bars |
| Investment | #6F7FD6 soft indigo | #8E9BE6 | Portfolio |
| Tax and warnings | #D4A24C amber | #E2B865 | Tax, budget at 80% to 100% |
| Over budget | #C9544B soft red | #EF7D73 | Budget over 100%, red row highlight on transactions that exceed it |

**Type and layout:** Inter for English and Noto Sans Thai for Thai, numbers in tabular figures so amounts line up. Left sidebar menu, rounded cards (12 px corners), generous spacing. On a phone the sidebar becomes a bottom tab bar and a floating "+" button opens quick-add.

**Pages:**

1. Dashboard
2. Transactions (with quick-add drawer)
3. Fixed costs and installments
4. Budget
5. Investments (portfolio list and one page per fund or ETF)
6. Tax planner
7. Goals
8. Reports
9. Settings (categories, budget rules, accounts, import/export, theme, language)

**Charts:** Apache ECharts. Planned charts: money-flow Sankey (income → fixed cost, spending groups, saving, investment), 12-month income vs outcome bars with a remaining line, category donut, budget progress bars, daily spending calendar heatmap, portfolio allocation donut and value-over-time line, DCA projection area chart, and a tax bracket bar showing where the income falls.

## Architecture and data model

One Spring Boot app with server-rendered pages. It runs free on Render (Docker, HTTPS), deployed from this GitHub repo, with data in a free Neon PostgreSQL database (0.5 GB) and a daily copy to a Google Sheet. After 15 minutes idle the free Render app sleeps, so the first open after a break takes about a minute.

```
Phone or PC browser            Spring Boot on Render (free)        Neon PostgreSQL (free, 0.5 GB)
 Thymeleaf + HTMX   --HTTPS-->  Controllers + JSON API    --JPA-->  Flyway migrations
 ECharts dashboards             Services: budget, tax,              Daily copy -> Google Sheet
 Light/dark, EN/TH              portfolio, reports
                                Spring Security login
                                Excel/CSV import and export
```

Pages load in under 1 second: totals are computed by database queries (grouped by month and category) and dashboards fetch chart data as small JSON calls.

**Stack:** Java 21, Spring Boot 3, Spring Data JPA, Spring Security, Thymeleaf + HTMX, Apache ECharts, Apache POI (Excel), Maven, Docker. Neon PostgreSQL online, H2 only for local testing, Google Sheets API for the daily copy. Amounts use BigDecimal, never floating point.

**Main tables:**

| Table | Key fields |
| --- | --- |
| transaction | date, type (income/expense/transfer), amount, category, account, note, tags, installment link; incentive rows also keep the KPI % and incentive % typed in |
| category | name (EN/TH), type, budget group (Fixed, Gas, Necessary, Shopping, Eat/Lifestyle, Saving, Investment) |
| recurring_item | name, amount, day of month, category, active |
| installment | item, full price, months, interest %, start month, paid count |
| budget_rule | month, kind (actual or goal), group, percent or fixed amount |
| account | name, type (wallet, bank, investment, credit), opening balance |
| goal | name, target, saved, deadline |
| asset | name, type (mutual fund, ETF, stock, gold), currency, current price |
| asset_trade | date, buy/sell, amount, FX rate, price per unit, units, fee |
| dividend | asset, date, amount, withholding tax |
| tax_year | year, salary, bonus, other income, deductions list |
| tax_bracket | from, to, rate (editable if the law changes) |

## Importing the sheet

Import Jan26 to Nov26, fixed costs, installments, investments, tax inputs and saving goals as starter data (not the Renovate tab; each month's incentive comes in as the recorded amount). Issues found in the sheet that the app fixes:

- **Summary Report shows #REF! for Aug to Dec.** The app computes every month from transactions, so it cannot break this way.
- **"Actual" budget rows show #N/A or #REF!** in Jan26 and Oct26. The app compares plan vs actual automatically.
- **Month tabs have no dates per item,** only the month. Imported rows get the 1st of the month; new entries have real dates (needed for the daily heatmap).
- **Two budget plans exist:** 40/40/20 (Eat/Shopping/Gas) in the month tabs is the actual budget, and 10/15/15/15/15/30 in Summary Report is the monthly goal. The app keeps both and compares spending against each.
- **GLD row mixes currencies** (exchange rate and Port written as $). The app stores each trade's currency and FX rate separately.
- **Tax sheet salary is 38,000/month** while Oct26 salary is 32,800. The tax planner uses actual income recorded so far plus an estimate for the remaining months.

## Schedule

Six phases, two a day, each ending with a working build the owner runs and reviews within half a day.

| Phase | Scope | Target |
| --- | --- | --- |
| 1 | Project setup, login, theme, EN/TH switch, layout shell, database schema, import from the sheet, Dashboard look-and-feel mockup | Wed Nov 4, evening |
| 2 | Transactions (quick-add, filters, search), fixed costs and recurring items, installments, monthly incentive entry | Thu Nov 5, morning |
| 3 | Dashboard (all charts) and budget planner (actual and goal budgets, red over-budget highlight) | Thu Nov 5, afternoon |
| 4 | Investment portfolio (funds, ETFs, FX, dividends, DCA projection) | Fri Nov 6, morning |
| 5 | Yearly Thai tax planner | Fri Nov 6, afternoon |
| 6 | Saving goals, accounts and net worth, reports and exports, daily Google Sheet copy, tests, deploy to Render + Neon | Sat Nov 7, morning |
| — | Handover | Sat Nov 7 |

## Decisions (all answered Nov 4, 2026)

- [x] Budget plans: 40/40/20 from the month tabs is the actual budget; 10/15/15/15/15/30 from Summary Report is the monthly goal. Both are kept, and spending over budget shows in red.
- [x] Users: one user, one login.
- [x] Hosting: online, usable from a phone anywhere.
- [x] Host and cost: zero cost. Render free tier for the app, Neon free PostgreSQL for data, daily copy to a new Google Sheet. The owner creates the free Render and Neon accounts.
- [x] Investment prices: typed in by the user; automatic fetch can be added later.
- [x] Language: switchable English and Thai.
- [x] Start: Phase 1 starts tonight (Wed Nov 4), built in Claude Code from this repo.

## As built (Nov 7, 2026)

**Live app:** https://money-management-9bne.onrender.com — Render free web service (Docker, region Singapore, auto
deploy on every push to `main`) and a free Neon PostgreSQL database (Singapore). The free app sleeps after 15 minutes
without visits; the first visit after that takes about a minute. The local H2 database was copied to Neon once with
the `copy-data` command; the online database is the real one since then.

### Pages

| Page | What it does |
| --- | --- |
| Dashboard | This month at a glance, 12-month trend, money-flow Sankey, category donut, daily spending heatmap, upcoming bills and installments |
| Transactions | Quick add, search and filters, monthly incentive entry. Shown **by group**: one card per budget group in its colour (Income, Fixed cost, Gasoline, Necessary, Shopping, Eat / Lifestyle, Saving, Investment) with total, share and latest entries; on a phone the cards start closed. **By date** shows the full list. Entries that pushed a group over budget are red |
| Transactions → Pay in installments | Credit card 0 % campaigns: tick a month's purchases and each becomes its own plan (default 3 months, payment 1 in that month). Plans started in the month can be ticked and cancelled together, or changed one by one |
| Fixed costs & installments | Recurring items posted each month; installment plans with schedule. A plan can **change months** (payments in the app get the new amount) or be **cancelled** (back to one purchase at the full price) |
| Budget | Actual budget (40/40/20) and goal budget (10/15/15/15/15/30) on income after fixed cost; edit by % or baht for one month or all later months; amber at 80 %, red over 100 % (actual budget only; saving and investing are targets) |
| Investments | Funds and ETFs, buy/sell, average cost, FX, dividends with withholding, allocation, value over time, DCA projection; prices typed in |
| Tax planner | Thai PIT for the year: income recorded plus estimate, deductions, bracket chart, tax due, RMF/SSF room |
| Goals | Saving goals typed in by hand (the sheet's Saving Account block is not imported): target, saved, deadline, progress, amount per month to reach it; add or take out money, optionally also recorded as a Deposit |
| Accounts & net worth | Wallet, bank, investment cash and credit card balances typed in from time to time (history kept). Net worth = accounts + portfolio − credit cards − installment debt, with a 12-month chart |
| Reports | Yearly report by month and budget group with chart, month-to-month compare, downloads |
| Settings | Theme, EN/TH, categories (EN/TH names, budget group, hide), import from the sheet, daily Google Sheet copy status, sign-in security |

### Downloads (Reports → Download your data)

- **Transactions by month (Excel)** for a chosen year: a Summary sheet (one row per month by budget group), then one
  sheet per month with its entries and totals; labels follow EN/TH.
- **Transactions (CSV)** for a year or all years (UTF-8, Thai and English category names).
- **Full backup (Excel)**: every table on its own sheet.

An automatic yearly export to Google Drive was built and then **removed at the owner's request**.

### Rules agreed while building

- **Payday:** salary is paid on the 28th (or the last weekday before it) and counts in the month it is received.
  Before payday, budgets use last month's income after fixed cost as an estimate. Imported income is dated on its
  month's payday.
- **Installments:** the amount typed with "1/X" is the full price; an optional credit card fee per payment is added
  on top. Each 0 % campaign purchase is its own plan.
- **Investments:** average-cost method; cost = amount + fee; USD cost at the trade rate, value at the latest USD/THB;
  dividends net of withholding; DCA compounds monthly at annual ÷ 12 (defaults 7,000 / month, 10 %, 20 years).
- **Tax:** every income category is employment income 40(1) (the 8,000 housing allowance too); the yearly bonus is the
  expected total; tax for year Y is paid on filing in Jan–Jun of Y+1; social security and PVD come from their fixed
  costs.

### Data model additions

| Table | Key fields |
| --- | --- |
| asset_price, fx_rate | prices and USD/THB rates typed in, kept as history for charts |
| account_balance | account, date, balance (for a credit card: amount owed) |
| app_setting | small app state, e.g. when the daily Google Sheet copy last ran |

`transaction` also links to its recurring item and keeps an optional installment fee. All changes are Flyway
migrations V1–V8 (V6 and V7 are Java migrations that moved imported income to payday).

### Security and operations

- One user; username and BCrypt hash only from environment variables. 5 wrong tries lock sign-in for 15 minutes;
  idle sign-out after 30 minutes; HTTPS by Render.
- Optional **two-step code** (authenticator app): on when `APP_TOTP_SECRET` is set (create it with `totp-secret`).
- Optional **daily copy to a Google Sheet** (separate from the original sheet): on when `GOOGLE_COPY_SHEET_ID` and
  `GOOGLE_SERVICE_ACCOUNT_JSON` are set. It runs the first time the app is awake each day.
- Backups: Neon restore history (a few hours on the free plan) and the downloads above.
- Environment variables on Render: `SPRING_PROFILES_ACTIVE=prod`, `DATABASE_URL` (Neon's string as shown),
  `APP_USERNAME`, `APP_PASSWORD_HASH`, and the optional ones above. Setup steps are in the README.
- Tests: 127 automated tests with made-up data, including the whole app and the data copy on a real PostgreSQL.
