# Defect repair plan

## Approved TOFU follow-up (baseline 009c323)

- [x] T1: handshake-only certificate observation and private multi-controller registry; regression tests for first use, repeat lookup, rotation, persistence and no credentials.
- [x] T2: replace mandatory manual fingerprint with first-use accept/cancel, visible table, details/rename/remove and separate explicit certificate replacement. Known match is automatic; known mismatch cannot continue.
- [x] T3a: GUI workflow tests, all existing gates, Gitleaks, independent review and separate versioned candidate.
- T3b delivery: commit and publish the reviewed source; verify the remote commit and fresh-clone build.
- [ ] T4: user-assisted live KVM acceptance; required before replacing the installed prototype.

First-use acceptance protects subsequent connections, not an already intercepted initial handshake. Independent fingerprint comparison remains optional. Never auto-trust during tests against the real controller. Keep the installed prototype intact.

## Completed repair baseline

Authorized repair; baseline 78ad66e.

- [x] M1: Java8/ARM64 detection, clean source build, self-contained bundle and safe no-clobber installer; 14 packaging tests.
- [x] M2: JSON/HTML/authority parsing, browser branches, atomic validated private cache; 19 support tests.
- [x] M3: independent certificate pinning, explicit legacy TLS, bounded downloads, applet defaults and visible errors; 16 TLS tests plus 7 launcher checks.
- [x] M4: original replacement artwork, prerequisites/security documentation, Gitleaks and independent review.
- [x] M5a: separately built signed candidate, real login-dialog/cancel smoke test, old installed app hash preservation.
- [ ] M5b: user-assisted live KVM video/input/closure with independently verified fingerprint. Required before replacing working prototype or claiming full hardware acceptance.

Scope remains repository and isolated candidate only. No controller settings,
power/reset, proxy changes or credential logging. A passed build/authentication
message is not evidence of a working KVM picture.
