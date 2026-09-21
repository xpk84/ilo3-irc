# Version 1.2.0 — iLO 3/4, live-validated

Implemented first-use acceptance, a visible registry, known-pin reuse, changed-pin blocking and separate explicit replacement. Independent review identified three defects: incomplete legacy-pin migration, late legacy-TLS opt-in after JSSE initialization, and replacement ignoring the selected TLS policy. All three fixes are integrated and their regression tests pass. Final focused independent rereview passed with no security or logic blockers.

Verified locally on Zulu Java 8 ARM64:
- Observation: 74 modern/security + 20 legacy assertions.
- Registry: 20 tests, including cross-process locking and update preservation.
- Trust policy: 7 tests.
- Lazy migration: 8 tests with launcher integration; UI separately verifies raw-host migration before observation and deletion tombstones before registry removal.
- Isolated certificate probe: 85 assertions, including modern-to-legacy retry in one parent, cold parent policy selection, no application bytes, bounded malformed output and cancellation/timeout cleanup.
- Swing: table/masked-password/cancel smoke, plus synthetic acceptance, decline, reuse and changed-certificate flows. These tests do not send real credentials or contact controllers.
- Existing support 19, pinned TLS 16, launcher 7 and packaging 14 checks pass.
- Gitleaks full history and worktree: zero findings, no suppressions.
- Separate `iLO 3 Console 1.1.0 Candidate.app` built; bundled `--check` and ad-hoc signature verification pass. Installed working prototype not replaced.

Final full run: all 12 check groups passed (support, TLS, launcher, trust, registry, observation, migration, probe, Swing UI, packaging, build, whitespace). History/worktree/staged Gitleaks scans returned zero findings.

Remaining acceptance gate: closed. Live video, harmless keyboard/mouse input and clean closure confirmed by the user on both an iLO 3 (192.168.11.12) and an iLO 4 DL360e Gen8 fw 2.81 (192.168.11.13).

## Version 1.2.0 — iLO 4 support

- After a pinned login the transport sends the controller session cookie when
  fetching console pages. iLO 4 serves `/html/java_irc.html` and `intgapp4_232.jar`
  behind realm authorization; iLO 3 ignores the cookie. Same HP applet entry point
  (`com.hp.ilo2.intgapp.intgapp`) and the same trust/registry model cover both.
- Live-validated by the user on both controllers; the iLO 4 JAR was downloaded over
  pinned TLS 1.2 into the per-certificate cache and the console window worked.
- Regression: realm page without a session cookie rejected; cookie unlocks it;
  cookie sent verbatim including quoting; cookie can be cleared (SecureIloTest,
  20 TLS checks). All 12 check groups pass.
- Branding: app/display name "iLO 3/4 Console", version 1.2.0.
- Production observation uses a disposable JVM, avoiding parent JSSE policy caching.

## Previous 1.0.1 candidate validation

Baseline: 78ad66e. The defects identified in the initial review are repaired in
the current source tree. This is a testable candidate, not a claim of completed
hardware acceptance across controllers.

## Verified

- 56 automated checks with Zulu Java 8 ARM64, exit 0: support 19, pinned HTTPS 16,
  launcher unit/CLI 7, packaging 14.
- Fresh source build and standalone sandbox .app after moving the checkout away;
  Java/compiler/architecture diagnostics, class integrity and no-clobber install.
- Independent review: no remaining confirmed security/logic blockers under the
  documented pin-based trust model. The plist version suggestion was applied.
- Real saved controller HTML parsed (including repeated browser branches); saved
  HP JAR passed ZIP/CRC/entry validation without executing or publishing it.
- Candidate bundle ad-hoc signature verified (`codesign --verify --deep --strict`).
  Not notarized. Bundled main class displayed its real Swing login dialog, masked
  password and SHA-256 field; cancellation exited the JVM with code 0. No credential
  entry or controller requests in that GUI smoke check.
- Existing installed prototype files remained byte-for-byte unchanged.
- Gitleaks history, directory and staged scans returned zero findings without
  suppressions. Tests use synthetic credentials/archives and generated TLS fixtures.

## Still requires user acceptance

The 1.1.0 candidate has NOT yet completed a live KVM connection. Review and accept
the first-use certificate (independent verification remains optional), enter
credentials in the app, then confirm
actual video, harmless keyboard/mouse input and clean closure. Do not replace the
working prototype before this check. No power/reset or controller configuration
changes are authorized by the repair test.

Current artwork is original; the earlier HP-derived icon remains in historical
commits. History was not force-rewritten. See README for licensing/trust limits.
