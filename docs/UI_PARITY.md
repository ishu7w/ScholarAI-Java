# UI preservation and verification

## Preserved direction

The landing page uses the original exported markup and styles, local Familjen Grotesk and Spline Sans Mono fonts, paper ground `#e8e6df`, square geometry, and vermilion markers `#c4442c`. The Java workspace retains the paper/ink visual system, desktop sidebar and the original student, institution, administration, scholarship, resume, and financial-aid route families.

Native HTML details, forms, a dialog and small browser scripts replace React interaction handlers. Java remains responsible for authentication, persistence, scoring and workflow authorization. The ten scoring weights and original scholarship/funding content are preserved separately from the UI implementation.

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

The original brochure's marketing statistics, testimonials, prices and organization claims are demonstration content, not independently validated deployment results. The page and project documentation identify their demonstration origin. Provider deadlines are preserved reference content and must be checked with the official provider.
