# ilo3-irc

Standalone Java 8 host for the HP iLO 3 Java Integrated Remote Console on
**macOS / Apple Silicon**. No Wine, Rosetta, browser plugin or OpenWebStart.

The controller's HP applet is downloaded at runtime, not distributed in this
repository. The original prototype displayed a working console on one iLO 3;
see `docs/status.md` for the repaired candidate's actual validation status.
Other firmware/macOS combinations are not a compatibility guarantee.

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

- iLO 3 serving `/html/java_irc.html` and an `intgapp*.jar` applet.
- An account with remote-console permission and the server's required license.
- A separately verified **SHA-256 fingerprint of the controller's TLS certificate**.
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

### First connection: certificate verification

The dialog asks for the controller, fingerprint, username and password.
**Do not enter a password in the terminal command line or an issue report.**

Obtain the fingerprint through a separately trusted administrator, a previously
verified certificate export or another independently authenticated management
channel. For example, calculate the fingerprint of an already trusted PEM export:

```bash
openssl x509 -in controller-certificate.pem -noout -fingerprint -sha256
```

Paste only the 64 hexadecimal digits (colon separators are accepted). Confirm
that you independently verified it. **Copying a certificate from an unverified
connection and trusting it immediately does not protect against MITM.** The app
never silently learns/trusts a certificate from the network.

For older controllers, explicitly select **Allow legacy TLS 1.0/1.1**.
Modern TLS remains the default. This changes only this Java process and retains
other JDK algorithm restrictions; installed `java.security` files are untouched.

You can test pinned HTTPS without sending credentials:

```bash
./ilo3-irc.sh --tls-check 10.0.0.42 YOUR_VERIFIED_SHA256 --legacy-tls
```

Replace the placeholder with your independently verified fingerprint. The
launcher does not accept it as a literal placeholder. Host, verified fingerprint
and TLS choice are saved after a successful login using Java user preferences;
usernames, passwords and session keys are not saved by the launcher. Changing
host/pin in the dialog requires confirmation again. Certificate rotation requires
independent verification of the replacement; there is no auto-accept fallback.

## Install a self-contained app bundle

```bash
./install-app.sh
# Creates /Applications/iLO 3 Console.app, only if the target does not exist.
```

To keep an existing working installation, build a separately named candidate:

```bash
./install-app.sh --output "$HOME/Applications/iLO 3 Console Candidate.app"
open "$HOME/Applications/iLO 3 Console Candidate.app"
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
bash tests/run-support-tests.sh
bash tests/run-security-tests.sh
bash tests/run-main-tests.sh
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
