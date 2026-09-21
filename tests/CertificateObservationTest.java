import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

/** Loopback-only, generated certificates; no controller or real credentials. */
public final class CertificateObservationTest {
    private static int checks;
    private static final char[] PASSWORD = "test-only".toCharArray();

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    private static KeyStore load(String file) throws Exception {
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(Paths.get(file))) { store.load(in, PASSWORD); }
        return store;
    }

    private static SSLContext serverContext(KeyStore store) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())) {
            if (result.length() > 0) result.append(':');
            result.append(String.format(Locale.ROOT, "%02X", b & 255));
        }
        return result.toString();
    }

    private static SecureIlo.CertificateInfo observe(String authority, boolean legacy) throws Exception {
        return SecureIlo.observeCertificate(authority, legacy);
    }

    private static Object field(Object info, String name) throws Exception {
        Field field = info.getClass().getDeclaredField(name);
        return field.get(info);
    }

    /** Single connection fixture records decrypted application bytes, not TLS records. */
    private static final class Fixture implements Closeable {
        final SSLServerSocket listener;
        final Thread worker;
        volatile SSLSocket accepted;
        volatile Throwable failure;
        volatile int applicationBytes;
        volatile boolean handshake;
        volatile boolean clientCertificate;
        volatile List<SNIServerName> serverNames = Collections.emptyList();

        Fixture(KeyStore store, String protocol, int port) throws Exception {
            this(store, protocol, port, false);
        }

        Fixture(KeyStore store, String protocol, int port, boolean wantClientAuth) throws Exception {
            listener = (SSLServerSocket)serverContext(store).getServerSocketFactory().createServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress("127.0.0.1", port));
            listener.setSoTimeout(6000);
            listener.setEnabledProtocols(new String[]{protocol});
            listener.setWantClientAuth(wantClientAuth);
            worker = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket)listener.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    handshake = true;
                    try {
                        socket.getSession().getPeerCertificates();
                        clientCertificate = true;
                    } catch (SSLPeerUnverifiedException noClientCertificate) { /* Expected for observation. */ }
                    serverNames = ((ExtendedSSLSession)socket.getSession()).getRequestedServerNames();
                    InputStream in = socket.getInputStream();
                    int first = in.read();
                    if (first >= 0) {
                        applicationBytes++;
                        StringBuilder header = new StringBuilder().append((char)first);
                        while (!header.toString().endsWith("\r\n\r\n") && header.length() < 8192) {
                            int value = in.read();
                            if (value < 0) break;
                            applicationBytes++;
                            header.append((char)value);
                        }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes("US-ASCII"));
                        socket.getOutputStream().flush();
                    }
                } catch (Throwable error) { failure = error; }
            }, "certificate-observation-fixture");
            worker.setDaemon(true);
            worker.start();
        }

        String authority() { return "127.0.0.1:" + listener.getLocalPort(); }
        void await() throws Exception {
            worker.join(7000);
            if (worker.isAlive()) throw new AssertionError("Fixture did not finish: observation must close its socket");
        }
        void clean() throws Exception {
            await();
            if (failure != null) throw new AssertionError("Fixture failed", failure);
        }
        public void close() throws IOException {
            listener.close();
            if (accepted != null) accepted.close();
            try { worker.join(7000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            if (worker.isAlive()) throw new IOException("Fixture thread leaked");
        }
    }

    private static void invalidAuthorities(KeyStore store) throws Exception {
        try (Fixture server = new Fixture(store, "TLSv1.2", 0)) {
            String host = server.authority();
            String[] invalid = {host + "/path", "https://" + host, "user@" + host, host + "?query",
                    host + "#fragment", host + "\\path", host + " ", host + "\n", null, "", "bad_host",
                    "-bad", "bad-", "a..b", "127.0.0.999", "127.0.00.1", "127.1", "[not-ip]",
                    "[::1", "::1", "localhost:", "localhost:0", "localhost:65536", "localhost:-1"};
            String algorithms = Security.getProperty("jdk.tls.disabledAlgorithms");
            for (String authority : invalid) {
                try {
                    observe(authority, true);
                    throw new AssertionError("Invalid authority accepted: " + authority);
                } catch (IllegalArgumentException expected) {
                    check(true, "invalid authority rejected before networking: " + String.valueOf(authority).replace("\n", "\\n"));
                }
            }
            check(server.accepted == null, "invalid authorities never connect to local listener");
            check(Objects.equals(algorithms, Security.getProperty("jdk.tls.disabledAlgorithms")),
                    "invalid legacy authority cannot change TLS policy");
        }
    }

    private static final class Defaults {
        final SSLSocketFactory sockets = HttpsURLConnection.getDefaultSSLSocketFactory();
        final HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        final SSLContext context;
        final boolean redirects = HttpURLConnection.getFollowRedirects();
        Defaults() throws Exception { context = SSLContext.getDefault(); }
        void unchanged(String label) throws Exception {
            check(sockets == HttpsURLConnection.getDefaultSSLSocketFactory(), label + ": HTTPS socket factory unchanged");
            check(verifier == HttpsURLConnection.getDefaultHostnameVerifier(), label + ": hostname verifier unchanged");
            check(context == SSLContext.getDefault(), label + ": default SSLContext unchanged");
            check(redirects == HttpURLConnection.getFollowRedirects(), label + ": HTTP redirect defaults unchanged");
        }
        void restore() throws Exception {
            HttpsURLConnection.setDefaultSSLSocketFactory(sockets);
            HttpsURLConnection.setDefaultHostnameVerifier(verifier);
            SSLContext.setDefault(context);
            HttpURLConnection.setFollowRedirects(redirects);
        }
    }

    private static void immutableInfo() throws Exception {
        Class<?> type = SecureIlo.CertificateInfo.class;
        check(Modifier.isFinal(type.getModifiers()) && Modifier.isStatic(type.getModifiers()), "CertificateInfo is static and final");
        for (String name : new String[]{"fingerprint", "subject", "issuer", "notBefore", "notAfter"}) {
            Field field = type.getDeclaredField(name);
            check(Modifier.isFinal(field.getModifiers()) && !Modifier.isStatic(field.getModifiers()), name + " is a final instance field");
        }
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, String.class, long.class, long.class);
        check((constructor.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0,
                "CertificateInfo synthetic constructor has package visibility");
        SecureIlo.CertificateInfo synthetic = new SecureIlo.CertificateInfo("pin", "subject", "issuer", 17L, 29L);
        check(synthetic.fingerprint.equals("pin") && synthetic.subject.equals("subject") && synthetic.issuer.equals("issuer")
                && synthetic.notBefore == 17L && synthetic.notAfter == 29L, "synthetic constructor preserves all field values");
    }

    private static void rotation(KeyStore original, KeyStore replacement) throws Exception {
        String initialPin;
        SecureIlo pinned;
        int port;
        try (Fixture server = new Fixture(original, "TLSv1.2", 0)) {
            port = server.listener.getLocalPort();
            initialPin = observe(server.authority(), false).fingerprint;
            pinned = new SecureIlo(server.authority(), initialPin, false);
            server.clean();
            check(server.applicationBytes == 0, "initial pin observation sends no application bytes");
        }
        try (Fixture server = new Fixture(original, "TLSv1.2", port)) {
            check("ok".equals(new String(pinned.get("/ok", 128), "US-ASCII")), "original pinned transport still performs authenticated HTTP");
            server.clean();
            check(server.applicationBytes > 0, "fixture detects application bytes from genuine pinned GET");
        }
        Defaults defaults = new Defaults();
        try {
            pinned.installAppletDefaults();
            Defaults pinnedDefaults = new Defaults();
            try (Fixture server = new Fixture(replacement, "TLSv1.2", port)) {
                String rotated = observe(server.authority(), false).fingerprint;
                check(!initialPin.equals(rotated), "certificate rotation at the same authority changes observed fingerprint");
                check(fingerprint((X509Certificate)replacement.getCertificate("server")).equals(rotated), "rotation observation reports replacement leaf");
                server.clean();
                check(server.applicationBytes == 0, "replacement observation sends no application bytes");
            }
            pinnedDefaults.unchanged("observation preserves installed pin-restricted defaults");
            try (Fixture server = new Fixture(replacement, "TLSv1.2", port)) {
                try {
                    pinned.get("/ok", 128);
                    throw new AssertionError("Old pin accepted replacement certificate");
                } catch (SSLHandshakeException expected) { check(true, "old pinned transport refuses replacement after observation"); }
                server.await();
                check(!server.handshake && server.applicationBytes == 0, "old-pin rejection occurs before application bytes");
            }
            try (Fixture server = new Fixture(replacement, "TLSv1.2", port)) {
                HttpsURLConnection connection = (HttpsURLConnection)new URL("https://" + server.authority() + "/ok").openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                try {
                    connection.getResponseCode();
                    throw new AssertionError("Applet defaults accepted replacement certificate");
                } catch (SSLHandshakeException expected) { check(true, "applet defaults retain old pin after replacement observation"); }
                finally { connection.disconnect(); }
                server.await();
                check(server.applicationBytes == 0, "pin-restricted defaults send no application bytes on mismatch");
            }
        } finally { defaults.restore(); }
    }

    private static void expired(KeyStore store) throws Exception {
        X509Certificate certificate = (X509Certificate)store.getCertificate("server");
        check(certificate.getNotAfter().getTime() < System.currentTimeMillis(), "expired fixture is actually expired");
        try (Fixture server = new Fixture(store, "TLSv1.2", 0)) {
            SecureIlo.CertificateInfo info = observe(server.authority(), false);
            check(fingerprint(certificate).equals(info.fingerprint), "expired self-signed leaf remains observable");
            check(certificate.getNotBefore().getTime() == info.notBefore && certificate.getNotAfter().getTime() == info.notAfter,
                    "expired validity dates preserved without authentication claim");
            server.clean();
            check(server.applicationBytes == 0, "expired-certificate observation sends no application bytes");
        }
    }

    private static void readTimeout() throws Exception {
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            long start = System.nanoTime();
            java.util.concurrent.Future<SecureIlo.CertificateInfo> result = worker.submit(() -> observe("127.0.0.1:" + server.getLocalPort(), false));
            try (Socket silent = server.accept()) {
                try {
                    result.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    throw new AssertionError("Silent TLS peer did not time out");
                } catch (java.util.concurrent.ExecutionException expected) {
                    check(expected.getCause() instanceof SocketTimeoutException, "silent peer fails with TLS read timeout");
                }
                long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                check(elapsed >= 18000 && elapsed < 30000, "TLS handshake enforces the 20-second read timeout");
                check(silent.isConnected(), "timeout exercised an accepted local TCP connection");
            }
        } finally {
            worker.shutdownNow();
            if (!worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Timeout test thread leaked");
        }
    }

    /** Separate JVM: server legacy policy must not initialize the client's JSSE policy cache. */
    private static final class LegacyPeer implements Closeable {
        final Process process;
        final BufferedReader output;
        final String authority;
        LegacyPeer(String store, String protocol) throws Exception {
            process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                    System.getProperty("java.class.path"), CertificateObservationTest.class.getName(),
                    "serve", store, protocol).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            String ready = output.readLine();
            if (ready == null || !ready.startsWith("PORT ")) {
                process.destroyForcibly();
                throw new AssertionError("Legacy fixture failed to start: " + ready);
            }
            authority = "127.0.0.1:" + Integer.parseInt(ready.substring(5));
        }
        void result(boolean completed, String label) throws Exception {
            String result = output.readLine();
            check(("RESULT " + completed + " 0").equals(result), label + ": no application bytes");
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Legacy fixture hung");
            check(process.exitValue() == 0, label + ": fixture completed");
        }
        public void close() throws IOException {
            process.destroyForcibly();
            output.close();
        }
    }

    private static void legacy(String file) throws Exception {
        String before = Security.getProperty("jdk.tls.disabledAlgorithms");
        Defaults defaults = null;
        for (String protocol : new String[]{"TLSv1.1", "TLSv1"}) {
            // The first observation in this fresh client JVM must itself opt in.
            try (LegacyPeer server = new LegacyPeer(file, protocol)) {
                Object info = observe(server.authority, true);
                check(fingerprint((X509Certificate)load(file).getCertificate("server")).equals(field(info, "fingerprint")),
                        protocol + " observation succeeds only with explicit legacy opt-in");
                server.result(true, protocol + " opted-in observation");
            }
            if (defaults == null) defaults = new Defaults();
            else defaults.unchanged("legacy observation");
            try (LegacyPeer server = new LegacyPeer(file, protocol)) {
                try {
                    observe(server.authority, false);
                    throw new AssertionError("Legacy protocol accepted without opt-in");
                } catch (SSLHandshakeException expected) {
                    check(true, protocol + " refused without opt-in even after an opted-in observation");
                }
                server.result(false, protocol + " refused observation");
            }
        }
        List<String> preserved = new ArrayList<>();
        for (String rule : before.split(",")) {
            String value = rule.trim();
            if (!value.equalsIgnoreCase("TLSv1") && !value.equalsIgnoreCase("TLSv1.1")) preserved.add(value);
        }
        check(String.join(", ", preserved).equals(Security.getProperty("jdk.tls.disabledAlgorithms")),
                "legacy opt-in preserves every non-protocol JDK algorithm restriction");
        try (LegacyPeer server = new LegacyPeer(file, "TLSv1.2")) {
            HttpsURLConnection connection = (HttpsURLConnection)new URL("https://" + server.authority + "/ok").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            try {
                connection.getResponseCode();
                throw new AssertionError("Observation installed permissive global trust");
            } catch (SSLHandshakeException expected) { check(true, "legacy observation does not authenticate fixture for default HTTPS"); }
            finally { connection.disconnect(); }
            server.result(false, "default HTTPS remains untrusted");
        }
        System.out.println("CERTIFICATE OBSERVATION LEGACY TESTS: " + checks + " PASS");
    }

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("java.specification.version").equals("1.8"))
            throw new AssertionError("Tests require Java 8");
        if ("timeout".equals(args[0])) { readTimeout(); return; }
        if ("serve".equals(args[0])) {
            // Test-only server policy; never affects the observation JVM.
            String disabled = Security.getProperty("jdk.tls.disabledAlgorithms");
            Security.setProperty("jdk.tls.disabledAlgorithms",
                    disabled.replaceAll("(?i)(^|,)\\s*TLSv1(?:\\.1)?\\s*(?=,|$)", ""));
            try (Fixture server = new Fixture(load(args[1]), args[2], 0)) {
                System.out.println("PORT " + server.listener.getLocalPort());
                System.out.flush();
                server.await();
                if (server.failure != null && !(server.failure instanceof SSLException))
                    throw new AssertionError("Unexpected fixture failure", server.failure);
                System.out.println("RESULT " + server.handshake + " " + server.applicationBytes);
            }
            return;
        }
        if ("legacy".equals(args[0])) { legacy(args[1]); return; }
        KeyStore store = load(args[0]);
        Defaults defaults = new Defaults();
        String algorithms = Security.getProperty("jdk.tls.disabledAlgorithms");
        immutableInfo();
        invalidAuthorities(store);
        X509Certificate certificate = (X509Certificate)store.getCertificate("server");
        try (Fixture server = new Fixture(store, "TLSv1.2", 0)) {
            Object info = observe(server.authority(), false);
            check(fingerprint(certificate).equals(field(info, "fingerprint")), "SHA-256 fingerprint matches presented self-signed leaf");
            check(certificate.getSubjectX500Principal().getName().equals(field(info, "subject")), "subject matches fixture");
            check(certificate.getIssuerX500Principal().getName().equals(field(info, "issuer")), "issuer matches fixture");
            check(Long.valueOf(certificate.getNotBefore().getTime()).equals(field(info, "notBefore")), "notBefore is fixture epochMillis");
            check(Long.valueOf(certificate.getNotAfter().getTime()).equals(field(info, "notAfter")), "notAfter is fixture epochMillis");
            server.clean();
            check(server.handshake && server.applicationBytes == 0, "observation completes TLS handshake without application bytes");
            check(server.serverNames.isEmpty(), "IP authority does not send an invalid SNI hostname");
        }
        try (Fixture server = new Fixture(store, "TLSv1.2", 0)) {
            observe("localhost:" + server.listener.getLocalPort(), false);
            server.clean();
            check(server.serverNames.equals(Collections.singletonList(new SNIHostName("localhost"))),
                    "DNS authority is sent as SNI, including a single-label hostname");
            check(server.applicationBytes == 0, "SNI observation sends no application bytes");
        }
        expired(load(args[2]));
        try (Fixture server = new Fixture(store, "TLSv1.2", 0, true)) {
            observe(server.authority(), false);
            server.clean();
            check(!server.clientCertificate, "observation sends no client certificate when peer requests authentication");
            check(server.applicationBytes == 0, "client-auth-requesting peer receives no application bytes");
        }
        rotation(store, load(args[1]));
        readTimeout();
        defaults.unchanged("modern observation");
        check(Objects.equals(algorithms, Security.getProperty("jdk.tls.disabledAlgorithms")), "modern observation does not relax TLS algorithm policy");
        try (LegacyPeer server = new LegacyPeer(args[0], "TLSv1.1")) {
            try {
                observe(server.authority, false);
                throw new AssertionError("Legacy protocol accepted without prior opt-in");
            } catch (SSLHandshakeException expected) { check(true, "legacy-only peer refused in fresh non-opted-in client"); }
            server.result(false, "fresh modern refusal");
        }
        System.out.println("CERTIFICATE OBSERVATION TESTS: " + checks + " PASS");
    }
}
