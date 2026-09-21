/*
 * ilo3-irc — Standalone HP iLO 3 Java Integrated Remote Console for modern macOS.
 *
 * The iLO 3 web UI still serves a Java *applet* (com.hp.ilo2.intgapp.intgapp),
 * which no browser has run for years. The usual workaround — a Wineskin/Wine
 * wrapper around the Windows .NET console — dies with Rosetta on macOS 27.
 * This launcher talks to the iLO directly:
 *
 *   1. asks for host/credentials in a Swing dialog (never stored or printed),
 *   2. POSTs /json/login_session over the TLS 1.1 the iLO 3 firmware speaks,
 *   3. fetches /html/intgapp*.jar (HP proprietary — downloaded, never bundled),
 *   4. parses the applet parameters exactly as java_irc.html would pass them,
 *   5. hosts the applet with an AppletStub and lets it build its own KVM window.
 *
 * Runs natively on Apple Silicon (Azul Zulu JDK 8 aarch64). No Rosetta,
 * no Wine, no browser, no Java Web Start / OpenWebStart.
 *
 * Usage:
 *   java -jar ilo3-irc.jar [host]
 *   java -cp . ILO3IRC [host]
 *
 * Requires Java 8 (applet API). TLS 1.0/1.1 is re-enabled in-process only —
 * the JVM-wide java.security file is never touched.
 *
 * Copyright 2026 ilo3-irc contributors. MIT license.
 * The applet jar remains property of Hewlett-Packard Enterprise and is
 * downloaded from your own iLO at runtime.
 */
import javax.swing.*;
import javax.net.ssl.*;
import java.applet.Applet;
import java.applet.AppletContext;
import java.applet.AppletStub;
import java.applet.AudioClip;
import java.awt.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ILO3IRC {

    static final String VERSION = "1.0.0";
    static final Pattern RE_JAR = Pattern.compile("archive=(/html/intgapp[\\w.]*\\.jar)");
    static final Pattern RE_RCINFO = Pattern.compile(
            "(?:document\\.writeln\\(\")?<(?:param|embed)[^>]*name=\\\"?(\\w+)\\\"?[^>]*value=\\\"([^\\\"]*)\\\"",
            Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) throws Exception {
        final String hostArg = args.length > 0 ? args[0] : "";

        // --- 1. credentials dialog (masked; never logged) ---
        final String[] creds = new String[3];
        SwingUtilities.invokeAndWait(() -> {
            JPanel p = new JPanel(new GridLayout(3, 2, 6, 6));
            JTextField hostF = new JTextField(hostArg, 16);
            JTextField userF = new JTextField(16);
            JPasswordField passF = new JPasswordField(16);
            p.add(new JLabel("iLO address:")); p.add(hostF);
            p.add(new JLabel("Login:"));       p.add(userF);
            p.add(new JLabel("Password:"));    p.add(passF);
            int ok = JOptionPane.showConfirmDialog(null, p,
                    "iLO 3 Remote Console", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) System.exit(0);
            creds[0] = hostF.getText().trim();
            creds[1] = userF.getText().trim();
            creds[2] = new String(passF.getPassword());
        });
        if (creds[0].isEmpty() || creds[1].isEmpty() || creds[2].isEmpty()) {
            fail("Host, login and password are required.");
        }
        String host = creds[0];

        relaxTls();

        // --- 2. login ---
        Map<String, String> login = login(host, creds[1], creds[2]);
        String sessionKey = login.get("session_key");
        if (sessionKey == null) fail("Login failed — check credentials and address.");
        System.out.println("[ilo3-irc] session established (key length "
                + sessionKey.length() + "), remote port " + login.get("remote_port"));

        // --- 3. fetch applet jar + page from the iLO itself ---
        String ircHtml = httpGet("https://" + host + "/html/java_irc.html");
        Matcher jarM = RE_JAR.matcher(ircHtml);
        if (!jarM.find()) fail("Could not find intgapp jar reference in /html/java_irc.html — is this an iLO 3?");
        String jarPath = jarM.group(1);
        System.out.println("[ilo3-irc] applet jar: " + jarPath);

        File cache = new File(System.getProperty("user.home"),
                ".cache/ilo3-irc/" + host + jarPath.substring(jarPath.lastIndexOf('/')));
        cache.getParentFile().mkdirs();
        if (!cache.exists()) {
            System.out.println("[ilo3-irc] downloading applet jar (first run only)...");
            byte[] jar = httpGetBytes("https://" + host + jarPath);
            try (OutputStream out = new FileOutputStream(cache)) { out.write(jar); }
        }
        // Always add the runtime-downloaded jar first; bundled copy (if any) second.
        URL jarUrl = cache.toURI().toURL();
        URLClassLoader cl = new URLClassLoader(new URL[]{ jarUrl },
                ILO3IRC.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);

        // --- 4. parse applet params exactly as java_irc.html passes them ---
        Map<String, String> params = parseAppletParams(ircHtml);
        params.put("RCINFO1", sessionKey);                       // session key
        // RCINFO6 (KVM port): normally filled by the browser from window.name;
        // the login response carries it as remote_port, INFO1 is the static default.
        if (login.get("remote_port") != null) params.put("RCINFO6", login.get("remote_port"));
        else if (params.get("INFO1") != null) params.put("RCINFO6", params.get("INFO1"));
        else params.put("RCINFO6", "17988");
        if (!params.containsKey("RCINFOLANG")) params.put("RCINFOLANG", Locale.getDefault().getLanguage());

        final Applet applet = (Applet) cl.loadClass("com.hp.ilo2.intgapp.intgapp").newInstance();
        final URL codeBase = new URL("https://" + host + "/html/");
        applet.setStub(new AppletStub() {
            public boolean isActive() { return true; }
            public URL getDocumentBase() { return codeBase; }
            public URL getCodeBase() { return codeBase; }
            public String getParameter(String name) { return params.get(name); }
            public AppletContext getAppletContext() { return CTX; }
            public void appletResize(int w, int h) {}
        });

        // --- 5. run the applet OFF the EDT (it blocks on the KVM receiver) ---
        System.out.println("[ilo3-irc] starting applet...");
        Thread t = new Thread(() -> {
            try {
                applet.init();
                applet.start();
                System.out.println("[ilo3-irc] console window should be visible now");
                // The applet sets its public `exit` field when its window closes.
                Field exitF = applet.getClass().getField("exit");
                while (!exitF.getBoolean(applet)) Thread.sleep(500);
                System.out.println("[ilo3-irc] window closed — exiting");
                System.exit(0);
            } catch (Throwable e) {
                e.printStackTrace();
                System.exit(3);
            }
        }, "applet-main");
        t.start();
        t.join();
    }

    // ------------------------------------------------------------------

    /** Minimal AppletContext: images via Toolkit, no browser around. */
    static final AppletContext CTX = new AppletContext() {
        public AudioClip getAudioClip(URL u) { return null; }
        public Image getImage(URL u) {
            try {
                URLConnection c = u.openConnection();
                c.setConnectTimeout(5000);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                    return Toolkit.getDefaultToolkit().createImage(bo.toByteArray());
                }
            } catch (Exception e) {
                System.out.println("[ilo3-irc] getImage(" + u + ") failed: " + e);
                return null;
            }
        }
        public Applet getApplet(String n) { return null; }
        public Enumeration<Applet> getApplets() { return Collections.emptyEnumeration(); }
        public void showDocument(URL u) {}
        public void showDocument(URL u, String t) {}
        public void showStatus(String s) { System.out.println("[status] " + s); }
        public void setStream(String k, InputStream v) {}
        public InputStream getStream(String k) { return null; }
        public Iterator<String> getStreamKeys() {
            return Collections.<String>emptyIterator();
        }
    };

    /** Pull RCINFO / INFO applet params out of java_irc.html (embed + param forms). */
    static Map<String, String> parseAppletParams(String html) {
        Map<String, String> m = new LinkedHashMap<>();
        // java_irc.html builds the applet via document.writeln("... RCINFOx=\"value\" ...")
        // in escaped-JavaScript form: RCINFO0=\"value\". Match both raw and escaped quotes.
        Matcher emb = Pattern.compile("(\\w+)=\\\\?\"([^\\\\\"]*)\\\\?\"").matcher(html);
        while (emb.find()) {
            String k = emb.group(1), v = emb.group(2);
            if (k.startsWith("RCINFO") || k.startsWith("INFO") || k.startsWith("INTG")) m.put(k, v);
        }
        System.out.println("[ilo3-irc] applet params from iLO: " + m.keySet());
        return m;
    }

    /** iLO 3 speaks TLS 1.0/1.1 with self-signed certs — relax in-process only. */
    static void relaxTls() throws Exception {
        Security.setProperty("jdk.tls.disabledAlgorithms",
                "SSLv3, RC4, DES, MD5withRSA, DH keySize < 768");
        SSLContext ctx = SSLContext.getInstance("TLSv1.1");
        ctx.init(null, new TrustManager[]{ new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String t) {}
            public void checkServerTrusted(X509Certificate[] c, String t) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String h, SSLSession s) { return true; }
        });
    }

    /** POST /json/login_session; returns session_key / remote_port. */
    static Map<String, String> login(String host, String user, String pass) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection)
                new URL("https://" + host + "/json/login_session").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Content-Type", "application/json");
        String body = "{\"method\":\"login\",\"user_login\":\"" + user + "\",\"password\":\"" + pass + "\"}";
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }

        int code = c.getResponseCode();
        String resp = slurp(code >= 400 ? c.getErrorStream() : c.getInputStream());
        System.out.println("[ilo3-irc] login HTTP " + code); // response contains the session key — do not log it
        Map<String, String> m = new HashMap<>();
        Matcher k = Pattern.compile("\"session_key\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(resp);
        if (k.find()) m.put("session_key", k.group(1));
        Matcher p = Pattern.compile("\"remote_port\"\\s*:\\s*(\\d+)").matcher(resp);
        if (p.find()) m.put("remote_port", p.group(1));
        return m;
    }

    static String httpGet(String u) throws Exception { return new String(httpGetBytes(u), "UTF-8"); }
    static byte[] httpGetBytes(String u) throws Exception {
        URLConnection c = new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    static String slurp(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    static void fail(String msg) {
        System.err.println("[ilo3-irc] " + msg);
        JOptionPane.showMessageDialog(null, msg, "ilo3-irc",
                JOptionPane.ERROR_MESSAGE);
        System.exit(2);
    }
}
