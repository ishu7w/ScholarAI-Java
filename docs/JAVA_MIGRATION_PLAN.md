# Historical migration plan

This records the original Java migration. The current real-data, mail-free Vercel release is specified in [LIVE_RELEASE_PLAN.md](LIVE_RELEASE_PLAN.md); its later user requirements supersede the email and seed-data portions below.

# ScholarAI Java migration

## Scope and preservation contract
Migrate the merged `main` + `add-financial-aid-planner` behavior to one Java MVC application. Preserve the paper ground (#e8e6df), vermilion (#c4442c), square corners, local Familjen Grotesk and Spline Sans Mono fonts, scholarship content, ten scoring weights, three roles, funding links, and route families. Browser pages still require HTML/CSS; Java owns rendering, authentication, data access, and domain logic. No React/Node runtime is required for the Java application. The original implementation is preserved in Git history at b05c58b and a separate local reference checkout. The new repository has only the Java runtime.

## Prioritized implementation and acceptance
1. Merge development branch; preserve upstream history; publish separate private repository. Verify remote/account and merge ancestry.
2. Java 21 bytecode, Spring Boot 4.1.2, MVC/Thymeleaf, Spring Security session authentication, JDBC/Flyway. Use PostgreSQL for production and disk-backed H2 for local development. Verify Maven build and both SQL dialects where available.
3. Registration/login/logout, password recovery via SMTP, persistent users, BCrypt 12, CSRF, role and object ownership, throttling. Verify invalid credentials, duplicate users, forbidden roles, session invalidation, one-use reset tokens.
4. Profiles and exact scoring/eligibility rules, scholarship catalogue/detail/save/apply, immutable submission snapshots, notifications. Verify boundaries, duplicate submissions, deadlines and ownership.
5. Institution scholarship CRUD and ranked decisions; admin user controls and audit records. Verify tenant isolation, approved institution access, and database writes.
6. Private documents with size/type validation, honest pending review, owner-only downloads; resume heuristics, deterministic copilot, funding calculator/checklist. Never label heuristic review as authenticity verification.
7. Rebuild templates within existing design; verify 375/768/1280px layouts and key navigation.
8. Docker, production config, CI, operational documentation. Run tests/package/diff checks, inspect rendered pages, report limitations, commit/push to new repository.

## Architecture
Browser → Spring Security → MVC controllers → services → JDBC → PostgreSQL
                                            ↘ Thymeleaf templates
                                            ↘ private document storage
                                            ↘ SMTP recovery email

Use server sessions rather than a separate JWT/refresh-token system. All mutations are POST with CSRF. Registration creates students or unverified institutions; an administrator must approve institutions. Administrator bootstrap requires explicit environment configuration. No seeded public credentials and no simulated successful authentication.

## Screens and flow
Landing → register/login → role dashboard. Students edit profiles, inspect ranked matches and explanations, save/apply, manage documents, read decisions, analyze resumes, plan funding. Institutions manage their own scholarships and decide their own applications. Admins approve/disable accounts and read the audit trail. Shared header/navigation, message panel, cards, table, form labels, and footer. Responsive CSS wraps navigation and collapses grids.

## Storage and endpoints
`users` identity/role/approval/disabled/password; `student_profiles` validated JSON payload; `scholarships` owner/status/domain JSON; `applications` student/scholarship/status/frozen JSON/reason; `saved_scholarships` unique student/program; `documents` owner/private file metadata/review; `notifications`; `audit_log`; hashed expiring `password_resets`; persisted `aid_plans`; expiring `rate_limits`.

Public GET /, /login, /register, /forgot-password, /reset-password, /catalogue, /scholarships, /scholarships/{id}. Auth POST /login, /register, /logout, /forgot-password, /reset-password. Student GET/POST /dashboard/student/profile, /dashboard/student/documents; POST /scholarships/{id}/apply and /save. Institution GET /dashboard/institution, /dashboard/institution/scholarships; GET/POST /dashboard/institution/scholarships/new and /{id}; POST /applications/{id}/decision. Admin GET /dashboard/admin, /users, /audit; POST /dashboard/admin/users/{id}. Shared authenticated GET /documents/{id}, /financial-aid, /resume-analyzer; POST /financial-aid, /resume-analyzer, /chat, /notifications/{id}/read. Forms redirect after mutation, validation errors return a readable page with an appropriate status; HTML is the primary API.

## Verification and release limits
Production readiness requires configured PostgreSQL, SMTP, durable private storage, TLS, backups, monitoring and a real deployment smoke test. Local tests cannot establish deliverability, restore reliability, or current validity of third-party funding calls. These must be reported, never inferred as passing.
