# ilo3-irc

**Standalone HP iLO 3 Java Integrated Remote Console for modern macOS (Apple Silicon native).**

The iLO 3 web interface still serves its remote console as a Java *applet*
(`com.hp.ilo2.intgapp.intgapp`) — a technology no browser has executed since
~2017. The usual workaround is a Wine/Wineskin wrapper around the Windows .NET
console, but that runs under Rosetta 2, which Apple is removing in macOS 27.

`ilo3-irc` is a single-file Java 8 launcher that talks to the iLO directly:

1. asks for host / login / password in a Swing dialog (credentials are never
   stored, cached or printed),
2. `POST /json/login_session` over the TLS 1.0/1.1 that iLO 3 firmware speaks
   (re-enabled in-process only — your JVM/system security files stay intact),
3. downloads `/html/intgapp*.jar` **from your own iLO** (the HP jar is
   proprietary and is never bundled with this project),
4. parses the applet parameters exactly as `java_irc.html` would pass them,
5. hosts the applet with an `AppletStub` and lets it build its own KVM window —
   keyboard, mouse, virtual power and the full remote console.

No Rosetta. No Wine. No browser. No Java Web Start / OpenWebStart. 100% native
ARM64 when run on an ARM JDK 8 build (e.g. Azul Zulu 8 for macOS/aarch64).

## Quick start (macOS, Apple Silicon)

```bash
# 1. Install an ARM64 Java 8 (one-time)
brew install --cask zulu@8

# 2. Get the launcher
git clone https://github.com/xpk84/ilo3-irc.git
cd ilo3-irc

# 3. Run
./ilo3-irc.sh                      # dialog asks for host + credentials
./ilo3-irc.sh 10.0.0.42          # or with the host pre-filled
```

First run downloads the applet jar from your iLO into `~/.cache/ilo3-irc/`.

### Optional: install as a proper .app

```bash
./install-app.sh                   # creates /Applications/iLO 3 Console.app
```

## Requirements

- Java 8 (applet API). Tested with Azul Zulu 8.96 on macOS 26 / Apple Silicon.
- Network access to the iLO 3 web interface (HTTPS, default port 443) and the
  remote-console port (default 17990 — taken from `INFO1`).

## How it works / gotchas learned the hard way

- **TLS**: iLO 3 negotiates TLS 1.0/1.1 with a self-signed certificate without
  SAN entries. The launcher relaxes `jdk.tls.disabledAlgorithms` *in-process*
  and installs a trust-all + hostname-verify-all handler for the JVM lifetime.
  Do not point this tool at anything but your own iLO.
- **EDT deadlock**: the applet's `init()` blocks on the KVM receiver thread.
  Calling `init()/start()` on the Swing EDT freezes every window. The launcher
  runs the applet lifecycle on a dedicated thread.
- **Proxies**: if your machine transparently redirects TCP (Proxifier &
  friends), exclude `java` or the iLO host, or the KVM stream dies silently
  after authentication.
- **Virtual Media** uses native Windows/Linux libraries inside the HP jar and
  shows "not available" on macOS — same as it ever was with the browser applet.

## Troubleshooting

- *Login failed* — the dialog fields; check you can open `https://<ilo>/` in a
  browser (self-signed cert warning is expected).
- *Window opens but no picture* — a proxy is eating the raw TCP stream to port
  17990 (see above), or the iLO has no active video session to attach to.
- *Works, then freezes after 15 min* — that is the iLO's own remote console
  inactivity timeout, configurable in the iLO web UI.

## Legal

- This project contains **no HP code**: the applet jar is downloaded from the
  user's own iLO at runtime and remains the property of Hewlett-Packard
  Enterprise.
- MIT licensed (see `LICENSE`). Not affiliated with HP/HPE.

## Alternatives

- Stay on macOS ≤ 26 and keep using a Wineskin wrapper (Rosetta still alive).
- Parallels + Windows 11 ARM (the .NET console works there via Windows' own
  x86 emulation).
- SSH + SMASH CLP for power/text-serial control (`power on`, `start /system1/oemhp_vsp`).
