# Verification evidence

## Local release checks

- Java 21 `mvn -B verify`: **32 tests passed, zero failures/errors**. This compiles and packages the executable application.
- Original-engine equivalence: **13 profiles and 156 scholarship matches** compare score totals, eligibility, match percentages and probability heuristics against outputs captured from the merged original TypeScript code. Resume score fixtures are also compared.
- Integration coverage includes public/authenticated page rendering, valid/invalid login, CSRF and role boundaries, registration normalization, institution approval, frozen application snapshots, cross-institution decisions, saved funding plans, upload validation/ownership/sharing, expiring one-use recovery links and session revocation.
- Security/service regressions cover concurrent first funding saves, preservation of reviewer-assessed profile fields, applicant-count preservation, integer seats, stale administrator privileges, bootstrap identity, file rollback/commit consistency, bounded PDF text extraction and role-specific copilot answers.
- Packaged-JAR browser journey: **passed**, with **zero browser JavaScript errors**. Tested new student and institution accounts, administrator approval, profile editing, funding-plan persistence, resume analysis, a private upload, program publication, saved scholarships, eligible submission, institution approval and the resulting student decision/notification.
- Browser layouts checked at **375, 768 and 1280px**, including authenticated dashboard/profile/catalogue/funding/resume pages. No root horizontal overflow was observed in those checks.
- UI review separately passed **21 public-route/viewport checks** and **five axe WCAG A/AA scans** with zero reported violations. See UI_PARITY.md for precise scope and remaining parity boundaries.
- `git diff --check` and staged-diff checks passed. Generated data, uploads, test credentials, dependencies and build directories are excluded from Git.

## Continuous integration

The [Java CI workflow](https://github.com/ishu7w/ScholarAI-Java/actions/workflows/ci.yml) runs Java 21 verification against H2 and PostgreSQL 17, then builds and smoke-tests the Docker image. Consult the run associated with the current commit for its actual result; this document does not substitute a configured workflow for an executed check.

## Checks requiring deployment configuration

Live SMTP delivery, public HTTPS and cookie behavior, durable storage, monitoring, backups and restore testing require a configured deployment. Local SMTP is mocked in recovery tests; browser accounts and data are synthetic. Docker was unavailable on the local machine, so image verification is assigned to CI. Automated accessibility testing is not full manual accessibility certification. External award deadlines and original demonstration marketing claims were not independently revalidated.
