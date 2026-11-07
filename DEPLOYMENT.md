# Run and deploy ScholarAI

This presentation edition keeps the application in Java and uses free hosting/database plans within their quotas. Password recovery and email delivery are intentionally omitted; there is no mail provider to configure.

## Run locally

Install JDK 21+ and Maven 3.9+, then run:

```sh
mvn verify
mvn spring-boot:run
```

Open http://localhost:8080. Local development uses a persistent H2 database under `data/`. Create your own account. To create the first administrator, supply `ADMIN_EMAIL` and a strong `ADMIN_PASSWORD` in the process environment before startup. Bootstrap never promotes an existing student account. Remove the bootstrap password afterward.

Java does not automatically load `.env` files. Use your IDE's environment settings or export variables for a local process; Docker Compose reads `.env` explicitly.

## Vercel with the Java Container Runtime

[Vercel's Container Runtime](https://vercel.com/docs/functions/runtimes/container) builds the root Dockerfile and runs the Java 21 application. It is currently a beta on all plans. `vercel.json` selects `container`, configures port 8080 and defines one daily scholarship refresh. There is no replacement Node frontend or generated mock site.

The separate project is `scholarai-java`, connected to `ishu7w/ScholarAI-Java`. Its public domain is https://scholarai-java.vercel.app. The original `scholar-ai` project and original repository remain separate. Project creation alone does not mean a release is live: confirm a successful production deployment and the checks below.

### Production variables

| Variable | Purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE=prod` | Enable production safeguards and shared database sessions |
| `JDBC_DATABASE_URL` | PostgreSQL JDBC URL with required SSL settings. This takes precedence when a managed Neon integration owns `DATABASE_URL` as a PostgreSQL URI. |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Dedicated application database credentials |
| `APP_BASE_URL` | Canonical public HTTPS origin |
| `PORT=8080` | Match Spring Boot and the image |
| `CRON_SECRET` | Generated random secret protecting official-source refresh |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Optional first administrator bootstrap |

The dedicated Neon resource was created using the marketplace's verified `free_v3` plan, and the Vercel team uses Hobby. Database credentials and the refresh secret belong in encrypted Vercel environment variables, never in Git. Do not reuse the database or credentials of an unrelated project.

Containers can scale down while idle. PostgreSQL stores accounts, applications, documents and JDBC sessions so a new instance can continue the same application state. Do not use local H2 or a container filesystem as durable production storage. The unpooled PostgreSQL connection is used for JDBC/Flyway compatibility; keep the configured pool small enough for the provider's connection allowance.

### Deploy after verification

1. Run `mvn verify` and check the H2/PostgreSQL CI results.
2. Authenticate the Vercel CLI and confirm its account with `vercel whoami`. Link the directory to the separate `scholarai-java` project with the Container framework.
3. Verify the production variables above, then run `vercel --prod`. A remote build does not require a local Docker daemon.
4. Check `/health` and the landing page over HTTPS. Register, sign in/out, edit a profile and verify data persists after an idle period or restart.
5. Confirm that “Apply now” opens each scholarship’s official HTTPS provider application page. In a non-production test database, also exercise institution publishing, optional supporting-profile submission and institution decisions. Do not publish invented award listings in the live catalogue for testing.
6. Test private document upload/download, resume analysis, funding-plan persistence and role restrictions. Uploaded files are limited to 4 MB so multipart requests fit below Vercel's 4.5 MB function payload limit. File acceptance/text consistency does not prove authenticity.
7. Verify the official-source refresh, source links and last-checked times. Keep the presentation's traffic and storage within the free plans' allowances. Before presenting, open the real scholarship catalogue and complete one normal sign-in to allow a paused instance/database to warm up; this is a readiness check, not an uptime guarantee.

## Real scholarship updates

The Vercel cron is scheduled to call `/api/internal/scholarships/refresh` daily in the `02:00–02:59 UTC` window (07:30–08:29 in India on Hobby). It authenticates using the Bearer value from `CRON_SECRET`. The endpoint must reject missing/incorrect secrets; ordinary form actions retain CSRF protection.

[Vercel cron requests](https://vercel.com/docs/schedules/cron-jobs) can wake an idle function. An in-process Spring timer alone cannot guarantee updates while a container is stopped. Imports coordinate through PostgreSQL and preserve successfully fetched official information when a provider fails. The UI should show provenance and freshness rather than inventing deadlines, award totals or live availability.

[Hobby cron limits](https://vercel.com/docs/schedules/cron-jobs/usage-and-pricing) allow one run per day with per-hour precision. The daily cache therefore is not a real-time guarantee: follow the official provider link before applying. More frequent schedules require a different eligible plan; no paid plan is purchased automatically.

## Free-plan boundaries

Free does not mean unlimited. Vercel and Neon impose usage, compute, connection and storage quotas; check the current project dashboards before presenting. Instances/databases may pause when idle and need time to start. Repeated large uploads, heavy scraping or high traffic can exhaust allowances. Use official data sources with bounded requests, cache successful results and show source failures honestly.

No hosted mail, payments, OCR service or paid AI service is required. Resume and matching guidance use the application's deterministic model. A deployment can be suitable for a teacher demonstration without claiming enterprise availability, legal compliance or guaranteed scholarship outcomes.

## Docker on another host

Copy `.env.example` to `.env`, replace the database/origin/refresh settings, then run `docker compose up --build -d`. PostgreSQL stays private to the Docker network, while the app binds host loopback port 8080. Use HTTPS in front of the production profile; secure cookies intentionally prevent an HTTP-only production login.

The container runs as UID 10001. Durable documents and sessions are in PostgreSQL, so no private-document volume is required. A non-Vercel host needs its own authenticated scheduler for refresh. Back up the database if you need to preserve presentation accounts and applications.

## Test databases

GitHub Actions runs Java 21 verification against H2 and PostgreSQL 17. For a local PostgreSQL run, set `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD` to a disposable test database. Do not run fixture tests against an unrelated live database.
