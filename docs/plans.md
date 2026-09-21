# Defect repair plan

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
