# Architecture

Browser → Spring Security → WebController → services → Store/JDBC → PostgreSQL
                                      ↘ Thymeleaf HTML
                                      ↘ private files / SMTP

One Maven application serves both pages and business workflows. Constructor injection makes dependencies visible. Services own validation and transaction boundaries. Financial-plan saves lock the user row before an initial insert; profile edits and reviewer assessments lock the same profile row before merging JSON. Administrator account changes are serialized and recheck the acting account to reject stale privileges. Store uses bound parameters; JSON preserves the original profile/scholarship domain fields while identity, ownership and relations have explicit SQL constraints and indexes.

`Matching` has no HTTP/database dependency. The original TypeScript engine generated fixtures for 13 profiles and 156 scholarship matches. Tests compare Java total scores, eligibility, match percentages, winning heuristics and resume scores against those reference outputs. The ten weights remain fixed.

## Request lifecycles

Registration validates input and hashes the password before atomically creating identity/profile/audit rows. Institution accounts start unapproved. Login uses Spring Security's form authentication and session fixation protection. Session requests reload role/disabled state and compare the login password version, so account changes revoke sessions on their next request.

Submission locks the scholarship, checks deadline/status/profile completion/eligibility, inserts a uniquely constrained application and freezes the profile, score components, match and review signals in its snapshot. Later profile edits cannot change it. Institution decisions check approved status and program ownership and create notifications/audit entries in the same database transaction. Final decisions cannot be silently overwritten.

Files use generated UUID paths rather than user names. The owner can download/delete files. Submission shares current files through an explicit application_documents relationship; only the approved institution owning that application may also download them. Downloads force attachment disposition and `no-store`; private files are outside static resources. Database/file storage are not a distributed transaction: operators should reconcile orphan files after interrupted writes and back up database/files consistently.

## Boundaries

All normal mutations are POST with CSRF. GETs do not change application state. Java templates escape untrusted text. The copilot's JSON endpoint requires authentication and CSRF; it uses the same services as the pages. JavaScript only progressively enhances menus, filters and calculations.

H2 is local development convenience. PostgreSQL is the production database; Flyway applies the same portable migrations. Sessions are local to one server, intentionally avoiding a Redis/session cluster until there is a concrete requirement. Pagination beyond the current admin audit limit and horizontal scaling require follow-up for high-volume installations.
