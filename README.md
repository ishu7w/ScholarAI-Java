# ScholarAI — simple Java edition

ScholarAI helps students understand scholarship eligibility, improve their profiles and track applications. Institutions publish programs and review a queue ranked by an explainable model. Administrators approve institutions, manage users and inspect the audit trail.

This repository is the Java migration of [ishu7w/ScholarAI](https://github.com/ishu7w/ScholarAI). The `add-financial-aid-planner` branch was merged before migration; the complete merged original remains in Git history at `b05c58b`. The original repository was not modified.

## Run locally

Install JDK 21+ and Maven 3.9+, then:

```sh
mvn spring-boot:run
```

Open [localhost:8080](http://localhost:8080). A disk-backed local database and private document directory are created under `data/`. Create your own student or institution account. There are no default passwords or automatic demo sessions. Institution publishing requires administrator approval.

To create the first administrator, supply `ADMIN_EMAIL` and a strong `ADMIN_PASSWORD` through your shell's secret mechanism before starting the app. The password must contain at least 12 characters and no more than 72 UTF-8 bytes. Bootstrap creates an account only if the email does not already exist; it never promotes an existing account. Remove the bootstrap password from the environment afterward.

```sh
mvn verify
mvn package
java -jar target/scholarai-1.0.0.jar
```

The Java application needs no Node, React, Supabase or frontend build. Java renders HTML through Thymeleaf. Small browser scripts provide mobile menus, the copilot, filtering and the live funding calculation.

## Features

- Persistent registration, credential login/logout, expiring one-use recovery links sent over SMTP, BCrypt hashing, session expiry, CSRF and role protection.
- Student profiles; ten original scoring weights; ranked matches with eligibility explanations; saved scholarships; applications with immutable scoring snapshots; decisions and notifications.
- Institution approval, program creation/editing/closure, tenant-isolated applicant ranking and reasoned decisions.
- Private PDF/PNG/JPEG uploads with size/signature limits, readable-PDF text consistency checks, pending scan handling, owner-controlled deletion and downloads restricted to owners and reviewing institutions after application submission.
- Resume text/PDF analysis, deterministic account-grounded copilot, financial-aid calculation, account-persisted checklist and original funding directory.
- Administrator user approval, enable/disable and role changes; published weight registry, evidence-recorded assessment of SOP/recommendation/conduct signals, and audit filters.
- Original landing markup, fonts, paper/vermilion design and route families; Java workspace templates preserve the same visual system and navigation pattern.

The bundled scholarship catalogue and landing marketing figures/testimonials originate from the original demonstration. They are not independently validated awards or deployment statistics. Reference scholarship dates are preserved and expired awards cannot accept submissions. Live institutions publish their own real programs. External funding calls must be confirmed on provider websites.

## Simple code map

| File | Responsibility |
| --- | --- |
| `WebController.java` | Routes, form inputs, page models and redirects |
| `Accounts.java` | Registration, passwords, throttles and recovery |
| `SecurityConfig.java` | Authentication, CSRF, roles, session revocation and security headers |
| `Scholarships.java` | Profiles, programs, saved items, submissions and decisions |
| `Matching.java` | Original scoring, eligibility, rankings, review signals and resume heuristics |
| `Documents.java` / `DocumentChecks.java` | Private storage and PDF consistency checks |
| `Administration.java` / `FinancialAid.java` | Account management, reviewed signals and financial planning |
| `Copilot.java` | Answers grounded in the signed-in account |
| `Store.java` | JDBC and JSON persistence helpers |
| `Bootstrap.java` | Reference data and optional administrator initialization |
| `src/main/resources/templates/` | Server-rendered pages |
| `src/main/resources/db/migration/` | Versioned database schema |

OOP comments identify actual encapsulation, composition, interface implementation, inheritance and method overriding. See [docs/OOP_GUIDE.md](docs/OOP_GUIDE.md) for a short reading guide.

## Production operation

Use PostgreSQL, an SMTP provider, HTTPS, a durable private storage volume and managed backups. The `prod` profile rejects missing infrastructure settings, development databases and non-HTTPS origins. Secure cookies require HTTPS. `.env` files are consumed by Docker Compose; Java does not automatically load them.

See [DEPLOYMENT.md](DEPLOYMENT.md), [SECURITY.md](SECURITY.md), [ARCHITECTURE.md](ARCHITECTURE.md) and the [migration plan](docs/JAVA_MIGRATION_PLAN.md). Deployment infrastructure and credentials must be configured before a real release. This is a single-instance application; clustered sessions, object storage, OCR and calibrated ML selection predictions are outside the current implementation.
