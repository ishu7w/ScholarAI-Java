# Architecture

Browser → Spring Security → WebController → services → Store/JDBC → PostgreSQL
                                      ↘ Thymeleaf HTML
                                      ↘ PostgreSQL document bytes / shared sessions

One Maven application serves both pages and business workflows. Constructor injection makes dependencies visible. Services own validation and transaction boundaries. Financial-plan saves lock the user row before an initial insert; profile edits and reviewer assessments lock the same profile row before merging JSON. Administrator account changes are serialized and recheck the acting account to reject stale privileges. Store uses bound parameters; JSON preserves the original profile/scholarship domain fields while identity, ownership and relations have explicit SQL constraints and indexes.

`Matching` has no HTTP/database dependency. The original TypeScript engine generated fixtures for 13 profiles and 156 scholarship matches. Tests compare Java total scores, eligibility, match percentages, winning heuristics and resume scores against those reference outputs. The ten weights remain fixed.

## Request lifecycles

Registration validates input and hashes the password before atomically creating identity/profile/audit rows. Institution accounts start unapproved. Login uses Spring Security's form authentication and session fixation protection. Session requests reload role/disabled state and compare the login password version, so account changes revoke sessions on their next request.

Submission locks the scholarship, checks deadline/status/profile completion/eligibility, inserts a uniquely constrained application and freezes the profile, score components, match and review signals in its snapshot. Later profile edits cannot change it. Institution decisions check approved status and program ownership and create notifications/audit entries in the same database transaction. Final decisions cannot be silently overwritten.

Files use generated UUID identifiers rather than user filenames as storage keys. The owner can download/delete files. Submission shares current files through an explicit application_documents relationship; only the approved institution owning that application may also download them. Downloads force attachment disposition and `no-store`; private files are outside static resources. File bytes and metadata are committed or rolled back together in PostgreSQL.

## Boundaries

All normal mutations are POST with CSRF. GETs do not change application state. Java templates escape untrusted text. The copilot's JSON endpoint requires authentication and CSRF; it uses the same services as the pages. JavaScript only progressively enhances menus, filters and calculations.

H2 is local development convenience. PostgreSQL is the production database; Flyway applies the same portable migrations. Production sessions use Spring Session JDBC with Flyway-managed tables so scaled Vercel containers share login state. Pagination beyond the current admin audit limit and horizontal scaling require follow-up for high-volume installations.

## Live catalogue

OfficialScholarshipSources discovers programs from live provider indexes and parses only facts published by those providers. ScholarshipSync holds an expiring database lease while fetching, then atomically records each source result and its status. A complete successful source may retire missing listings; a partial/failing source cannot erase cached data. Deadline filters operate independently of provider availability. Old bundled reference rows have source_type=legacy and are not public. The daily authenticated Vercel cron complements local scheduling when containers scale to zero.

The primary application action navigates to the stored official HTTPS application URL. It never claims that clicking an outbound link submitted a provider application. Institution profile submissions remain an explicitly labelled separate operation.
