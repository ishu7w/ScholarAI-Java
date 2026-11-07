# Verification evidence for the live-source release

## Clean application build

Java 21 `mvn -B clean verify` passed **48 tests, zero failures/errors**. Clean packaging was checked to exclude the removed production scholarship/funding seeds and all test fixtures from the executable JAR.

Coverage includes account registration/login/logout, role/CSRF boundaries, normalized identities, institution ownership/approval, immutable local application snapshots, reviewer assessments, saved plans, private document contents and transactional rollback/deletion. Email recovery tests and routes were removed when the user explicitly removed that requirement.

New checks prove that official Apply redirects point at the stored provider HTTPS URL without creating a fake submitted application, that unknown provider terms do not produce invented numbers, and that serialized authentication/expiry/logout persist across independent JDBC session repositories. Sync checks cover successful import, date expiry, unavailable/partial source preservation, retiring missing records only after complete refresh, URL validation, distributed lease coordination and cached refresh reuse after a cold start.

The original scoring model is still compared against **13 full profiles and 156 scholarship matches**, plus resume fixtures. Those files exist only under test resources; they do not seed the production application.

## Actual provider-network evidence

The current Java adapter completed a real fetch in **64.72 seconds** and returned **61 Bristol records**: **17 open, 1 upcoming**, 18 closed and 25 whose status was not sufficiently specified. Only open/upcoming, unexpired records are eligible for the public active catalogue. The upcoming Unite Foundation cycle publishes 1 January 2027 as its opening date and no exact deadline; that deadline stays unknown.

Official provider-published application links are preserved exactly, including third-party form platforms linked from university pages. A form's year-like URL slug is not rewritten to a guessed year. Chevening timed out and Melbourne returned HTTP403 in this fetch; both are recorded as source failures rather than replaced by invented data. Cached real records are retained if a provider is temporarily unavailable.

## Release gates

The [Java CI workflow](https://github.com/ishu7w/ScholarAI-Java/actions/workflows/ci.yml) verifies H2, PostgreSQL and the Docker image. Consult the run for the release commit for its executed result. Public HTTPS deployment, authenticated browser flows and actual scheduled refresh configuration are checked against the new Vercel project before final handoff; deployment configuration alone is not proof of a live application.

Previous migration/browser/axe results are historical evidence, not a fresh claim about this changed release. See UI_PARITY.md. No unlimited-capacity or outage-free guarantee is made for free external services. Current platform/provider limits and backup/restore operations remain operational considerations.
