# Repaired-candidate validation

Baseline: 78ad66e. Source and packaging are repaired; hardware acceptance is
separate from the offline checks below.

## Approved TOFU gates

- Certificate observation sends handshake only, no HTTP/login/JAR and changes no global HTTPS trust defaults.
- Unknown controller: accept/cancel prompt; decline produces no credential POST and no registry entry. Acceptance persists a pin before authenticated requests.
- Known matching certificate: automatic pinned connection, no repeat prompt. Changed certificate: blocked with both fingerprints, no credential POST; no acceptance callback.
- Replacement is separate from connection and requires explicit confirmation; never silently overwrites a pin. Rename/removal/details work per controller; no passwords/session keys in registry.
- Optional independently obtained fingerprint mismatch blocks first use too.
- GUI/table/select/cancel and synthetic two-certificate local-server workflows; real controller trust decisions remain the user's.

## Automated gates

```bash
export JDK8=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
bash tests/run-support-tests.sh
bash tests/run-security-tests.sh
bash tests/run-main-tests.sh
bash tests/run-trust-tests.sh
bash tests/run-registry-tests.sh
bash tests/run-observation-tests.sh
bash tests/run-tofu-ui-tests.sh
bash tests/run-migration-tests.sh --launcher
bash tests/run-probe-tests.sh
python3 tests/test-packaging.py -v
./ilo3-irc.sh --check
```

- Support: JSON controls, EMBED/PARAM/JavaScript HTML, repeated browser branches
  sharing a JAR, language fallback, authority/archive validation, atomic/cache
  interruption/corruption/symlink/size/CRC checks.
- HTTPS: generated local certificate, not real credentials. Wrong/missing pin
  rejected; exact pin accepted; redirects/external URLs/other hostnames rejected
  (including a hostname covered by the same certificate SAN); bounded response;
  legacy-only endpoint refused without opt-in, accepted with it; SSLv3 disabled.
- Main: escaped credential JSON, runtime guards, headless help/version/check/error.
- Packaging: clean build, runtime/compiler validation, standalone bundle after
  checkout rename, class integrity, existing-target refusal, original icon.

Gitleaks: full history, directory, staged diff and final commit. No suppressions.
Reports belong outside the repository.

## Local compatibility

A privately retained controller HTML page must parse, and its retained HP JAR
must pass ZIP/CRC/entry validation. Never commit either file or parameter values.
This neither executes the old unverified cache nor certifies its provenance.

## Hardware acceptance (requires user)

Build a separately named Candidate.app. Review and explicitly accept the first-use
certificate (independent verification optional), entering credentials only in the
masked app dialog. Confirm actual video,
harmless keyboard/mouse input, exactly one console window and process exit after
closing. No power/reset/controller settings changes. --check or an authenticated
log line alone does not satisfy hardware acceptance. Never overwrite the working
prototype until the user approves replacement.
