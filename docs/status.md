# Repair status — 1.0.1 candidate

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

The repaired candidate has NOT yet completed a live KVM connection. Independently
verify the controller fingerprint, enter credentials in the app, then confirm
actual video, harmless keyboard/mouse input and clean closure. Do not replace the
working prototype before this check. No power/reset or controller configuration
changes are authorized by the repair test.

Current artwork is original; the earlier HP-derived icon remains in historical
commits. History was not force-rewritten. See README for licensing/trust limits.
