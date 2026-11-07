# Security model and operational limits

- BCrypt cost 12; password inputs require at least 12 characters and at most 72 UTF-8 bytes for newly set passwords.
- Session cookies are HttpOnly/SameSite=Lax and Secure in production. Spring protects login against session fixation and POSTs against CSRF. Logout destroys the session.
- Public registration accepts student/institution only. Publishing requires institution approval. Institution decisions/edits require program ownership. Admin endpoints require the administrator role; administrators cannot change their own account through the user control screen.
- Login throttles use normalized email buckets; registration use direct peer address buckets. Buckets are hashed, stored in the database and expire. Sessions are shared in PostgreSQL across container instances. Add proxy-level IP limits, request/body limits and trusted forwarding configuration appropriate to your host.
- Changes to disabled state, role or password revoke existing sessions on their next request.
- Email delivery and email-based password recovery are intentionally omitted. There is no mail API dependency or recovery token endpoint.
- SQL values are parameter-bound. Identity and duplicate application constraints live in the database. Validation rejects invalid/non-finite numeric input and oversized text.
- Templates escape text; a self-only Content Security Policy blocks third-party scripts/inline execution. Spring adds standard frame/content-type/referrer-related defenses.
- Documents are private attachments, accessible to owners and the approved institution reviewing a submitted application, capped at 4 MB and checked by byte signature. PDF parsing is capped at 50 pages and 200,000 extracted characters. Parsed results are consistency checks only, not issuer verification or OCR. Unreadable files remain pending. Deploy file scanning and isolated parsing if the threat model requires handling hostile documents at scale.
- Profile achievements/income/academics remain self-reported. Scoring is a transparent heuristic, not calibrated ML or a fraud finding. Fixed demographic gates only apply where the program explicitly targets a group. An authorized human must assess claims and make decisions.

No code change can demonstrate a production host's TLS setup, access policies, backup restoration or legal compliance. Configure and test those systems before opening registration publicly. Production pages do not contain invented metrics, testimonials, awards or application deadlines.

## Reporting

Report a suspected vulnerability privately to the repository owner. Do not put credentials, uploaded documents, reset links or student personal data into public issues or logs.

## Official-source refresh

Only fixed HTTPS provider hosts are fetched, with response/time/page limits, certificate validation and controlled redirects. Unknown provider facts are not inferred. A database lease coordinates refreshes; partial/unavailable sources retain cached records with freshness notices, while known expired deadlines are excluded from active results. Cron requires a strong bearer secret; admin-triggered refresh requires an administrator session and CSRF.

Uploads and metadata share the same database transaction. Private bytes are served only after owner/reviewing-institution authorization and force attachment/no-store responses. Production sessions use Spring Session JDBC, so scale-to-zero does not replace durable authentication with local memory or a mock user.
