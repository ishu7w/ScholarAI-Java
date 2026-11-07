# ScholarAI — simple Java edition

ScholarAI helps students understand scholarship eligibility, improve their profiles and track applications. Institutions publish programs and review a queue ranked by an explainable model. Administrators approve institutions, manage users and inspect the audit trail.

This repository is the Java migration of [ishu7w/ScholarAI](https://github.com/ishu7w/ScholarAI). The `add-financial-aid-planner` branch was merged before migration; the complete merged original remains in Git history at `b05c58b`. The original repository was not modified.

## Run locally

Install JDK 21+ and Maven 3.9+, then:

```sh
mvn spring-boot:run
```

Open [localhost:8080](http://localhost:8080). A disk-backed local database is created under `data/`; private documents are stored transactionally inside that database. Create your own student or institution account. There are no default passwords or automatic demo sessions. Institution publishing requires administrator approval.

To create the first administrator, supply `ADMIN_EMAIL` and a strong `ADMIN_PASSWORD` through your shell's secret mechanism before starting the app. The password must contain at least 12 characters and no more than 72 UTF-8 bytes. Bootstrap creates an account only if the email does not already exist; it never promotes an existing account. Remove the bootstrap password from the environment afterward.

```sh
mvn verify
mvn package
java -jar target/scholarai-1.0.0.jar
```

The Java application needs no Node, React, Supabase or frontend build. Java renders HTML through Thymeleaf. Small browser scripts provide mobile menus, the copilot, filtering and the live funding calculation.

## Features

- Persistent registration, credential login/logout, BCrypt hashing, session expiry, CSRF and role protection.
- Student profiles; ten original scoring weights; ranked matches with eligibility explanations; saved scholarships; applications with immutable scoring snapshots; decisions and notifications.
- Institution approval, program creation/editing/closure, tenant-isolated applicant ranking and reasoned decisions.
- Private PDF/PNG/JPEG uploads with size/signature limits, readable-PDF text consistency checks, pending scan handling, owner-controlled deletion and downloads restricted to owners and reviewing institutions after application submission.
- Resume text/PDF analysis, deterministic account-grounded copilot, financial-aid calculation, account-persisted checklist and automatically refreshed official funding directory.
- Administrator user approval, enable/disable and role changes; published weight registry, evidence-recorded assessment of SOP/recommendation/conduct signals, and audit filters.
- Original landing markup, fonts, paper/vermilion design and route families; Java workspace templates preserve the same visual system and navigation pattern.

Scholarships are discovered and refreshed from official provider websites. Every imported record retains its source URL, provider application link, published status/dates and retrieval time. Unknown benefits or eligibility remain unknown; imported programs never receive fabricated match percentages, seats or selection predictions. “Apply now” opens the official provider site. Institution submissions are a separate optional workflow.

Source changes and temporary network failures are visible on the catalogue. Previously fetched real listings remain cached; known deadlines still expire naturally. No bundled scholarship seed or invented marketing statistics are loaded. Historical demonstration rows are archived from the public catalogue by a migration. Test fixtures exist only in `src/test/resources`.

## Simple code map

| File | Responsibility |
| --- | --- |
| `WebController.java` | Routes, form inputs, page models and redirects |
| `Accounts.java` | Registration, passwords and throttles |
| `SecurityConfig.java` | Authentication, CSRF, roles, session revocation and security headers |
| `Scholarships.java` | Profiles, programs, saved items, submissions and decisions |
| `Matching.java` | Original scoring, eligibility, rankings, review signals and resume heuristics |
| `Documents.java` / `DocumentChecks.java` | Private storage and PDF consistency checks |
| `Administration.java` / `FinancialAid.java` | Account management, reviewed signals and financial planning |
| `Copilot.java` | Answers grounded in the signed-in account |
| `Store.java` | JDBC and JSON persistence helpers |
| `OfficialScholarshipSources.java` / `ScholarshipSync.java` | Official provider discovery, bounded parsing, persisted refresh status and scheduled updates |
| `SharedSessionConfig.java` | Shared PostgreSQL sessions for scaled containers |
| `Bootstrap.java` | Optional administrator initialization (no scholarship seeds) |
| `src/main/resources/templates/` | Server-rendered pages |
| `src/main/resources/db/migration/` | Versioned database schema |

OOP comments identify actual encapsulation, composition, interface implementation, inheritance and method overriding. See [docs/OOP_GUIDE.md](docs/OOP_GUIDE.md) for a short reading guide.

See [the live release plan](docs/LIVE_RELEASE_PLAN.md) for the real-data and deployment acceptance checks.

## Production operation

Use PostgreSQL and HTTPS. The `prod` profile rejects missing database settings, a weak/missing refresh secret and non-HTTPS origins. Login sessions, private files and fetched scholarships live in PostgreSQL and survive container restarts. No mail provider, recovery-email service, paid AI API or frontend build is required. `.env` files are consumed by Docker Compose; Java does not automatically load them.

Vercel's Container Runtime runs the Java application. A protected daily Vercel cron refreshes real scholarship sources even when containers have been idle; active containers also refresh periodically. See [DEPLOYMENT.md](DEPLOYMENT.md) for the exact free-plan setup and [SECURITY.md](SECURITY.md) for the enforcement boundaries. Free hosting/database services have quotas and availability limits; the application does not promise unlimited capacity or a provider-independent SLA.

For a teacher presentation, load the catalogue before presenting so the persistent real-data cache is warm. Registration/login, profiles, saved programs, the budget planner and document vault operate on persisted user data. Provider application websites remain external services.
