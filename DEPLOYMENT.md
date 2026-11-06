# Deployment

## Local development

Use JDK 21+ and Maven 3.9+. `mvn spring-boot:run` starts the app on port 8080 with a persistent local H2 database. `mvn verify` runs the test suite. Optional `ADMIN_EMAIL`/`ADMIN_PASSWORD` create the first admin only if the email is unused. No `.env` parser is installed: export environment values or use your IDE's environment configuration.

## Docker / PostgreSQL

1. Copy `.env.example` to `.env` and replace every example value with real configuration. Set `APP_BASE_URL` to the public HTTPS origin; configure SMTP and private durable storage. Set the first administrator identity and a strong password.
2. Run `docker compose up --build -d`. PostgreSQL is private to the Docker network; the app listens only on host loopback port 8080. Put a trusted HTTPS reverse proxy in front of it.
3. Check `docker compose ps`, `docker compose logs app`, and `GET /health` locally and over the public HTTPS origin. Health checks query the database and expose only UP or an error status.
4. Sign in as administrator. Remove ADMIN_PASSWORD from deployment secrets after bootstrapping. Register and approve an institution, create a test program and submit a student application.
5. Send a recovery email to a controlled address, follow the link, reset the password and verify the previous session can no longer access the account. Confirm delivery through your SMTP provider.
6. Test upload/download/deletion, funding-plan persistence and institution decisions. Restart the container and confirm accounts, programs, files and applications remain present.
7. Take and restore a PostgreSQL backup and the document volume together. Configure monitoring, disk alerts, access controls, retention policies and log redaction.

Use a single application replica. Session state is server-local; scaling requires shared sessions and shared private storage. Keep the reverse proxy/app network private. This app does not enable generic forwarded headers; use host-specific trusted proxy configuration when needed rather than trusting arbitrary headers.

The container runs as UID 10001 and drops Linux capabilities through Compose. Ensure persistent mounts are writable by that UID. Pin tested image digests and perform dependency/image scanning in your deployment process.

## CI and checks

GitHub Actions runs `mvn verify` on Java 21 against H2 and PostgreSQL 17. The same integration tests exercise each database. For a local PostgreSQL test run, set TEST_DATABASE_URL, TEST_DATABASE_USER and TEST_DATABASE_PASSWORD to a disposable test database. Tests roll back their fixture transactions.

The production profile refuses missing required environment variables, non-PostgreSQL databases and a base URL without HTTPS. Secure cookies will intentionally prevent an HTTP-only production login. For plain local HTTP use the default development profile.

## Known limits

There is no OCR, malware scanner, payment processing, institution identity vetting service or hosted AI dependency. PDF text consistency and deterministic guidance are supported without third-party keys. Funding directory dates came from the merged branch; verify current deadlines on official sites. Existing Supabase users/data are not automatically migrated: this Java database is a new installation and password hashes must not be guessed or copied incompatibly. If an existing live deployment has real users, plan a separate audited data migration before switching it.
