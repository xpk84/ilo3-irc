# ilo3-irc

Standalone Java 8 host for the HP **iLO 3 and iLO 4** Java Integrated Remote
Console on **macOS / Apple Silicon**. No Wine, Rosetta, browser plugin or
OpenWebStart.

The controller's HP applet is downloaded at runtime, not distributed in this
repository. Live-validated on an iLO 3 (firmware shipping `intgapp3_231.jar`)
and an iLO 4 DL360e Gen8, firmware 2.81 (`intgapp4_232.jar`, TLS 1.2, session
cookie handled automatically); see `docs/status.md` for the exact validation
status. Other firmware/macOS combinations are not a compatibility guarantee.

## Install these prerequisites on macOS

| Component | Needed? |
| --- | --- |
| **Azul Zulu JDK 8, macOS ARM64 / AArch64** | **Yes** for the tested setup. Includes `java` and `javac`; a JRE alone cannot build the project. Keep Java installed after installing the `.app`. |
| Git | For cloning; alternatively download the repository ZIP. |
| Homebrew | Optional installer for Zulu. Azul's own installer works too. |
| Xcode Command Line Tools | For Apple Git/Homebrew if requested. Full Xcode is not needed. |
| Wine, Rosetta, OpenWebStart, Temurin | **Not required** for this project. |

### Homebrew route

If needed, install Homebrew following its [official instructions](https://docs.brew.sh/Installation).
If prompted for Command Line Tools, run `xcode-select --install` and finish the
installer before continuing. On Apple Silicon use native Homebrew:

```bash
arch -arm64 /opt/homebrew/bin/brew install --cask zulu@8
```

See the [official cask](https://formulae.brew.sh/cask/zulu@8). Installation may
ask for macOS administrator authorization; **do not run the console with sudo**.

### Without Homebrew

Download **Zulu JDK 8 / macOS / ARM 64-bit (AArch64)** from
[Azul](https://www.azul.com/downloads/?package=jdk#zulu) and follow
[Azul's installation instructions](https://docs.azul.com/core/install/macos).
Choose **JDK**, not JRE or an Intel build. Its normal location is:

```text
/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
```

Verify the installation:

```bash
JDK8="/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home"
"$JDK8/bin/java" -version
"$JDK8/bin/javac" -version
file "$JDK8/bin/java"
```

Both versions must be **1.8.0…** and the executable must include **arm64**.
The development setup uses Zulu 8.96.0.205 / OpenJDK 1.8.0_504. Other installed
JDKs can remain; you do not need to change global `JAVA_HOME`.
For a nonstandard JDK location use `export JDK8="/your/jdk8/Contents/Home"`.

## Controller and network requirements

- iLO 3 or iLO 4 serving `/html/java_irc.html` and an `intgapp*.jar` applet
  (tested: iLO 3 `intgapp3_231.jar`; iLO 4 `intgapp4_232.jar`).
  On iLO 4 the launcher sends the login session cookie when fetching console
  pages, matching the browser's realm-authorized flow.
- An account with remote-console permission and the server's required license.
- Explicit first-use certificate acceptance; independent fingerprint verification is optional.
- Network access to HTTPS (usually TCP 443) and the controller's KVM service
  (TCP 17990 on the tested device). Virtual-media port 17988 is not the KVM port.
- A trusted management network / VPN. Do not expose an old iLO to the internet.

A proxy may need a controller-specific route. The launcher does not rewrite
Proxifier rules or globally disable proxy settings. A blank screen by itself
is not proof of a proxy fault.

## Start from source

```bash
git clone https://github.com/xpk84/ilo3-irc.git
cd ilo3-irc
./ilo3-irc.sh --check    # validate Java, compile, no GUI or network
./ilo3-irc.sh           # connection dialog
# Or pre-fill an example controller address:
./ilo3-irc.sh 10.0.0.42
```

The runner compiles all Java source files. To build manually:

```bash
JDK8="/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home"
mkdir -p build
"$JDK8/bin/javac" -d build src/*.java
"$JDK8/bin/java" -cp build ILO3IRC
```

### First connection: accept and remember (TOFU)

Enter the controller address, username and password in the app. **Never enter
passwords in terminal arguments or issue reports.** Before sending credentials
or downloading HP code, the launcher performs only a TLS handshake and shows
an unknown controller's certificate, validity dates and SHA-256 fingerprint.
Choose **Accept and remember** or **Cancel** (the safe default).

This is trust on first use (TOFU): it detects later certificate changes, but
**cannot exclude interception of the first connection**. If uncertain, cancel
and compare the fingerprint through a separately trusted administrator or
previously verified certificate export. The optional advanced fingerprint field
blocks a mismatch before acceptance. For an already trusted PEM export:

```bash
openssl x509 -in controller-certificate.pem -noout -fingerprint -sha256
```

The table below the form lists known controllers, aliases, saved trust and the
last successful login. Selecting a row fills the address and TLS choice. You can
view certificate details, rename entries or remove their trust; credentials are
not stored. A matching saved certificate needs no repeat confirmation.

A changed certificate **blocks connection** and displays old/new fingerprints.
After verifying the reason for rotation, select the saved server, open its
certificate details and explicitly choose **Replace certificate**. Replacement
does not log in; connect again separately. There is no silent replacement.

For older controllers, explicitly enable **legacy TLS 1.0/1.1**. Modern TLS is
the default; installed JDK security files remain untouched. Certificate observation
runs in a disposable Java subprocess, so selecting legacy TLS after a failed
modern attempt works without restarting the dialog. No credentials go to this
subprocess. The authenticated connection still checks the accepted exact pin.

The registry lives at:

```text
~/Library/Application Support/ilo3-irc/known-controllers.properties
```

Writes are atomic and process-locked, with private POSIX permissions. Corrupt
registries fail closed, rather than being silently reset. Previously verified
1.0.1 pins are imported for the last controller and lazily for other entered
addresses before observation. Old hashed keys cannot reveal arbitrary historical
spellings: if a controller was entered with unusual letter case, enter that same
spelling to recover its pin. Supported canonical/default-port aliases are checked;
conflicting legacy pins fail closed. Removing trust writes a persistent per-host
tombstone so the old pin is not imported again. Passwords are not stored.

Pinned HTTPS can also be checked without credentials:

```bash
./ilo3-irc.sh --tls-check 10.0.0.42 YOUR_VERIFIED_SHA256 --legacy-tls
```

Replace the placeholder with a real independently verified fingerprint.

## Install a self-contained app bundle

```bash
./install-app.sh
# Creates /Applications/iLO 3-4 Console.app, only if the target does not exist.
```

To keep an existing working installation, build a separately named candidate:

```bash
./install-app.sh --output "$HOME/Applications/iLO 3-4 Console Candidate.app"
open "$HOME/Applications/iLO 3-4 Console Candidate.app"
```

The bundle contains its compiled classes and resources and does not depend on
the source checkout. **Zulu Java 8 is still an external prerequisite.** The
installer refuses an existing target rather than overwriting it. Candidate
bundles use local ad-hoc signing where available, not Apple notarization.
Do not disable Gatekeeper globally to install this tool.

## Security model and remaining limits

- HTTPS verifies the exact configured leaf-certificate SHA-256 fingerprint and
  controller hostname. This deliberately supports a pinned self-signed certificate
  without matching SAN entries. It uses **pin-based trust**, not public-CA trust;
  it does not enforce certificate expiry once that exact certificate is pinned.
- Wrong/missing pins fail closed before an authenticated HTTP request. Launcher
  downloads reject redirects, unsafe paths and oversized responses. Applet HTTPS
  defaults are also pin-restricted, never trust-all.
- Applet JARs are cached atomically and ZIP-validated under
  `~/.cache/ilo3-irc/pinned-v1/`, separated by controller certificate and address.
  The old prototype's unverified cache is never reused. JAR structure validation
  is **not** independent HPE publisher-signature verification.
- The downloaded HP applet executes with your local user's privileges. You still
  trust the controller firmware and the local account/filesystem. Pinning does
  not make a compromised controller safe or modernize the old KVM protocol.
- The applet can perform its own network access and produce diagnostics. Some
  legacy resources use HTTP and the KVM channel uses the HP protocol, not this
  HTTPS transport. Use an isolated management network even with a verified pin.
- The launcher does not log the login response or persist credentials. **HP applet
  diagnostics can contain infrastructure information**; inspect/redact logs before
  sharing them. Never assume a clean Gitleaks result sanitizes runtime logs.
- Virtual Media is not supported/verified on this setup (`Media Access not available`
  was observed). Power/reset commands are not part of automated testing.

## Tests

Tests use generated local TLS certificates, synthetic HTML and synthetic ZIP
fixtures, not real credentials or redistributed HP code:

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
# See docs/test-plan.md for packaging checks and acceptance gates.
```

`--check` verifies runtime/build only. It is not a live KVM test. A hardware
acceptance test must confirm actual video, harmless keyboard/mouse input and
window/process shutdown. Authentication messages alone are insufficient.

## Rosetta

This launcher needs no Rosetta with an ARM64 JDK. The old claim that macOS 27
removes Rosetta was incorrect. [Apple's support page](https://support.apple.com/en-us/102527)
(published September 14, 2026) states general availability through macOS 27,
with functionality limited to certain older games from macOS 28. This does not
claim the launcher has been tested on every such macOS release.

## License

Launcher code, scripts and original replacement artwork are MIT licensed;
see `LICENSE` and `assets/`. The HP applet remains third-party software, fetched
from your controller and not bundled. Older repository history contains the
prototype HP-derived icon; that historical asset is not relicensed under MIT.
No HP/HPE endorsement or affiliation is claimed.

"HP", "HPE" and "iLO" are trademarks of Hewlett Packard Enterprise Company.
This project is not affiliated with or endorsed by HPE.
