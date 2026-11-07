# UI preservation and verification

## Preserved direction

The landing page uses the original exported markup and styles, local Familjen Grotesk and Spline Sans Mono fonts, paper ground `#e8e6df`, square geometry, and vermilion markers `#c4442c`. The Java workspace retains the paper/ink visual system, desktop sidebar and the original student, institution, administration, scholarship, resume, and financial-aid route families.

Native HTML details, forms, a dialog and small browser scripts replace React interaction handlers. Java remains responsible for authentication, persistence, scoring and workflow authorization. The ten scoring weights remain independently testable. The production catalogue and funding directory now use actual official-source imports and institution-published records; invented legacy programmes and the static funding directory are removed.

## Material fixes from the UI review

- Removed three nested buttons from landing-page links while keeping their existing button styling. Each call to action is now a single keyboard-focusable link.
- Fixed the 375px landing-page overflow caused by the `120,000+` counter. A scoped mobile rule reduces stats-card padding and number size; desktop presentation is unchanged.
- Added skip-to-content links and visible focus treatment, marked sidebar icon glyphs decorative, and kept desktop navigation scrollable on short screens.
- Retained `#c4442c` markers while using the original darker ink-red `#a53a24` for small accent text and filled workspace buttons that otherwise failed AA contrast against paper. Muted landing text now uses readable ink gray.
- Made mobile workspace navigation available without JavaScript; JavaScript progressively adds the collapsed-menu behavior. The landing menu closes with Escape, and the copilot dialog focuses the question input.
- Admin role selectors now select each account's current role rather than silently defaulting every account to Student.
- Added an explicit document-sharing explanation next to application submission and aligned document-vault copy with the application's reviewed-access rules.
- Replaced raw internal criterion keys with readable eligibility labels. Student and institution score breakdowns display the exact-input component explanation when available.
- Added scholarship empty-state feedback, live filter result feedback, accessible authentication notices and password-length guidance.

## Verification performed

An isolated local Java server on port 8081 used its own H2 database and document directory. No existing user accounts or authentication sessions were accessed.

- Browser-rendered seven public routes at **375, 768 and 1280px**: landing, login, register, password recovery, reset-password, catalogue and scholarship detail. All **21 checks returned HTTP 200**, had no root horizontal overflow, no nested link/button controls and no unlabelled visible form controls.
- Tested the mobile landing menu opening/closing with Escape, native FAQ expansion, copilot dialog opening, question-field focus and closing with Escape.
- Recorded **zero browser JavaScript errors** in those checks.
- Ran axe checks tagged WCAG 2 A, WCAG 2 AA and WCAG 2.1 AA on landing, login, register, catalogue and scholarship detail at the mobile viewport. After the small brand-text contrast correction, these five scans reported **zero violations**.
- Ran syntax checks for `app.js` and `home.js`, and `git diff --check` for the owned resource changes.

Authenticated end-to-end verification, Java tests and packaged-image verification are coordinated by the primary agent. Automated accessibility scans are useful evidence, not certification; assistive-technology and full keyboard/manual testing remain deployment acceptance work.

## Honest parity boundaries

This is a Java-rendered migration, not a pixel-diff certification of every original screen. The workspaces use new Thymeleaf templates in the existing visual system. The original React/GSAP motion choreography, pointer effects and animated counters have been simplified to stable native/browser behavior. The original multi-step registration presentation is a single validated account form. Real account authentication and institution approval deliberately replace simulated demo success.

## Removal of invented content and live-source contracts

The original brochure's invented marketing numbers, named testimonials, subscription prices, pilot periods and unsupported enterprise promises have been removed rather than labelled as examples. Their card/grid structure now presents actual role workflows and working navigation. Hero chips describe implemented capabilities rather than displaying invented personal scores or risk results.

Home counters use real `platformStats` values: `activeScholarships`, `approvedInstitutions`, `registeredStudents` and `decisionsRecorded`. If a model value is unavailable the template shows an em dash, never an invented count.

The catalogue and funding directory share imported provider records. The UI distinguishes `sourceType=official` from institution-managed programmes. `applicationUrl` powers the primary external application link; if absent, `sourceUrl` offers a clearly labelled provider-process link. Institution-managed submissions are a separate secondary action. Official records without structured numeric requirements show provider terms rather than zero amounts/seats, invented eligibility gates, match percentages or winning estimates. Missing deadlines and unconfirmed application windows are explicit. Closed provider windows are labelled closed and do not receive an Apply now label.

Provider refresh status uses actual `sourceStatuses` records (`name`, `status`, `lastFetchedAt`, `count`, `sourceUrl`). The template presents success, partial and error outcomes without exposing internal exception text. Records marked `stale` identify cached provider information and direct users to current official terms. The static `funding.json` production resource was removed after its Java reference was replaced by the live catalogue.

The student workspace also withholds a personal score and component values until the profile is complete, rather than presenting a score derived from missing income/profile inputs as an assessed result.

The live-source UI update passed a static content audit: no invented testimonials, prices or demonstration metrics remain in the landing text; every local landing-page section link points to an existing section; no nested link/button controls remain; JavaScript syntax and resource diff checks pass. Runtime verification of the latest live importer/model integration is coordinated by the primary agent after all parallel changes land. The earlier 21 viewport checks and five axe scans above describe the pre-live-source UI revision and must not be represented as a fresh scan of the final live-source revision.
