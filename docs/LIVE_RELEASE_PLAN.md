# Live, real-data release

User scope: simple Java; preserve the design; remove invented content; discover and update new/upcoming scholarships automatically; primary application actions open official provider sites; working account/profile/document/funding/admin workflows; push to GitHub and deploy on Vercel. Email/recovery is intentionally excluded by the user's later request. Use only free hosting/database plans, without claiming unlimited capacity.

## Implementation

1. Remove production seed files and demonstration marketing. Archive legacy seed rows from public access. Runtime data must come from official providers or approved real institutions; fixture data stays under test resources only.
2. Discover provider programs from official indexes, retain source and application URLs, publication status, known dates and retrieval time. Never invent unknown amounts, deadlines, eligibility thresholds or win probabilities.
3. Persist refresh state and source errors. Database lease prevents concurrent jobs. Successful complete runs may retire removed records; failures/partial runs preserve cached facts and mark untouched records stale. Known dates still expire.
4. Keep Java in Vercel's Container Runtime. Store sessions and private document bytes in PostgreSQL rather than ephemeral container disk. Protect the daily Vercel cron with CRON_SECRET.
5. Verify authenticated workflows and direct official links, provider-network fetch plus database persistence, deployed container health/TLS, shared login state, mobile layouts and no-mock public content.
6. Review changes, verify contributor identity and destination, commit/push, verify CI and the production deployment. Remove the one-time administrator bootstrap password after the real owner account exists; deliver access privately.

## Evidence needed before completion

- Current Maven suite and PostgreSQL CI pass; source parsers are tested against captured provider HTML without any runtime fixture fallback.
- Actual live refresh returns open/upcoming entries with official provenance and a persisted success/partial/error status; repeated refreshes update existing rows rather than duplicate them.
- Live public catalogue and funding directory show real source records; unknown fields remain explicitly unknown. No invented testimonials, prices, users, metrics or awarded totals.
- Each displayed Apply now link equals a provider-published HTTPS application URL; external clicks never create a fake submitted application.
- Live registration/login/logout and protected routes work; profiles, saves, budget/checklist and documents persist. Institution/admin controls retain authorization and role boundaries.
- Production database and sessions persist across container startup/scale-down. Scheduled refresh is configured on the actual Vercel project.
- HTTPS URL is reachable, correct GitHub repository contains the deployed source, and no credentials or generated user files are committed.

Free-service quotas and third-party site availability are operational constraints; cached real records and a local Java run path reduce presentation dependency on a fresh provider request. They do not establish an unlimited-service guarantee.
