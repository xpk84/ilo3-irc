# ilo3-irc

**Experimental standalone HP iLO 3 Java Integrated Remote Console launcher for macOS / Apple Silicon.**

The launcher hosts the Java applet served by your own iLO, without a browser,
Wine, Rosetta or OpenWebStart. A prototype was used successfully on one iLO 3
with macOS 26 and Azul Zulu JDK 8 for ARM64. Other firmware, macOS versions and
hardware combinations are not yet verified.

> **Prototype, not a release-ready installer.** A clean-checkout review found
> defects in the shell runner and `.app` packaging. Use the manual build/run
> commands below. See [Known issues](#known-issues) before using this tool.

## What to install on macOS first

| Component | Required? | Purpose |
| --- | --- | --- |
| **Azul Zulu JDK 8 for macOS ARM64 / AArch64** | **Yes, for the tested setup** | Includes both `java` and `javac`. The launcher builds from source and hosts a Java applet. A JRE alone is not enough to build it. |
| Git | Only to clone the repository | Alternatively download and unpack the repository ZIP from GitHub. |
| Homebrew | Optional | Convenient way to install Zulu JDK 8. The Azul installer is an alternative. |
| Xcode Command Line Tools | For the Apple Git / Homebrew installation route | Full Xcode is not needed for this Java project. |
| Wine, Rosetta, OpenWebStart, Temurin 8 | **No** | Not part of this launcher's tested runtime. Do not install them for this project. |

Use **JDK 8**, not a Java 17/21 installation selected by your shell. Those
runtimes are not the tested configuration. On Apple Silicon, select **ARM64 /
AArch64**, not x86_64, to avoid depending on Rosetta.

### Option A: install with Homebrew on Apple Silicon

If you do not have Homebrew, follow the [official Homebrew installation
instructions](https://docs.brew.sh/Installation). If requested, install Apple's
Command Line Tools with `xcode-select --install`, complete the installer and
then return to Terminal.

Use the native Homebrew installation, including when your shell runs under Rosetta:

```bash
arch -arm64 /opt/homebrew/bin/brew install --cask zulu@8
```

The [Homebrew `zulu@8` cask](https://formulae.brew.sh/cask/zulu@8) installs the
**Azul Zulu Java 8 Development Kit**. Installation may request macOS administrator
authorization; the console itself should not be run with `sudo`.

### Option B: install without Homebrew

Download **Zulu JDK 8**, **macOS**, **ARM 64-bit / AArch64** from
[Azul Downloads](https://www.azul.com/downloads/?package=jdk#zulu), then follow
[Azul's macOS installation instructions](https://docs.azul.com/core/install/macos).
Choose the **JDK**, not the JRE. The installer normally places JDK 8 in:

```text
/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
```

If you used an archive or chose another location, adjust `JDK8` in the commands
below. Changing the system-wide default Java or removing other JDKs is unnecessary.

### Verify Java before starting

```bash
JDK8="/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home"
"$JDK8/bin/java" -version
"$JDK8/bin/javac" -version
file "$JDK8/bin/java"
```

Both version commands must report **1.8.0…**; on Apple Silicon, `file` must
include **arm64**. If the executable is Intel-only, this is not the native setup.
The review used Zulu 8.96.0.205 / OpenJDK 1.8.0_504, ARM64.

## iLO and network prerequisites

- An **iLO 3** that serves `/html/java_irc.html` and its `intgapp*.jar` applet.
  Compatibility with other iLO generations is not established.
- Your own iLO account with permission to use the remote console, plus whatever
  remote-console licensing your server requires. This launcher does not bypass
  permissions or licensing.
- Connectivity to the iLO HTTPS service (normally TCP **443**) and its remote
  console service (TCP **17990** on the tested device). Do not confuse the
  virtual-media port **17988** with the KVM port.
- A trusted, isolated management network or an appropriate VPN. Do not expose
  iLO to the public internet to use this launcher.
- If a proxy or traffic redirection tool is in use, check the route to the iLO.
  Do not disable proxies globally or assume every blank screen is a proxy issue.

## Build and run from a clean checkout

```bash
git clone https://github.com/xpk84/ilo3-irc.git
cd ilo3-irc

JDK8="/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home"
mkdir -p build
"$JDK8/bin/javac" -d build src/ILO3IRC.java
"$JDK8/bin/java" -cp build ILO3IRC

# Alternatively, pre-fill the address (example only):
"$JDK8/bin/java" -cp build ILO3IRC 10.0.0.42
```

Enter the iLO address, username and password in the dialog. Do not supply the
password in command-line arguments. Use a bare hostname or IPv4 address, not a
full URL. IPv6 and non-default HTTPS port support have not been verified.

The launcher downloads the HP applet from your iLO and caches it under
`~/.cache/ilo3-irc/`. Only the original HP applet creates the KVM window; there
is no additional visible container window.

**Do not use `install-app.sh` yet:** it currently creates an incomplete `.app`.
The previously tested local prototype bundle is not evidence that this repository's
installer works. The JDK must remain installed even after future `.app` packaging
is fixed; this repository does not bundle a Java runtime.

## Security limitations — read before use

The current implementation disables certificate-chain and hostname verification
inside its Java process and relaxes TLS algorithm restrictions. It currently
creates a **TLS 1.1** context. It does not modify the installed JDK's security
files, but that does **not** make the connection authenticated or safe from MITM.

**An attacker able to impersonate the iLO could steal credentials or substitute
the downloaded applet, which executes with your local user's privileges.**
Certificate pinning / verified trust and an explicit legacy-TLS opt-in are needed
before this should be considered hardened community software. Use only with your
own trusted controller on a protected management network.

The launcher does not intentionally persist passwords or log the login response.
However, the HP applet emits its own diagnostics, including server names and
addresses. Inspect and redact logs before posting them in an issue. A clean
secret-scanner report does not certify runtime security or anonymize applet logs.

## Known issues

Found while reviewing the initial public commit:

- **First-run shell build is broken:** `ilo3-irc.sh` invokes `bin/java/javac`
  instead of `bin/javac`. The manual build/run commands above bypass this.
- **The `.app` installer is incomplete:** it copies the runner and icon, but not
  source/classes. Its runner also changes directory to `Contents/MacOS`.
- **JSON escaping is missing:** usernames/passwords containing quotes,
  backslashes or control characters can make the login request invalid. Do not
  weaken your iLO password to work around this; the serializer needs fixing.
- **Parameter parsing is partial:** `<PARAM name="…" VALUE="…">` and escaped
  `INFO1\=` forms are not handled, and an empty language value is not replaced
  by the fallback. The tested device's other parameters were sufficient, but
  this is not a firmware compatibility guarantee.
- **Cache writes are not atomic or validated:** an interrupted first download
  can leave an unusable cached JAR. Only remove the affected controller's cached
  JAR if redownloading is needed.
- **Error handling is incomplete:** some failures appear only in Terminal,
  rather than in a user-facing error dialog.
- **Virtual Media is not verified:** the tested setup logged `Media Access not
  available`. Mounting local disks/ISO images is not a supported feature here.
- **Icon provenance:** the bundled icon was derived from an HP applet resource.
  Permission to redistribute it has not been established; do not treat it as
  covered by the project's MIT license. It needs replacement with original artwork.

## How it works

1. Collect host and credentials using Swing.
2. Log in through `/json/login_session`.
3. Fetch `java_irc.html` and find the controller's applet JAR.
4. Download/cache the JAR locally; it is not included in the repository.
5. Supply an `AppletStub` / `AppletContext`, and run `init()` / `start()` outside
   the Swing event-dispatch thread. Running the lifecycle on that thread froze
   the UI in the prototype.
6. Let the applet display its own console window and exit when its `exit` flag
   is observed after the lifecycle calls return.

## Rosetta clarification

This launcher does not need Rosetta when used with an ARM64 JDK. The earlier
claim that Rosetta disappears in macOS 27 was incorrect. [Apple's documentation](https://support.apple.com/en-us/102527),
published September 14, 2026, states that Rosetta remains available through
macOS 27; from macOS 28 it is limited to certain older games. This is not a claim
that this prototype has been tested on those future/current OS releases.

## License and third-party components

The launcher source and scripts are MIT licensed; see `LICENSE`.
The HP applet JAR is downloaded from the user's own controller and is not
redistributed here. HP/HPE software and artwork remain third-party material;
the MIT license does not grant rights to those assets. See the icon issue above.
This project is not affiliated with or endorsed by HP/HPE.
