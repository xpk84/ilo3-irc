import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;

/** Generated loopback fixtures only. A fresh parent tests JSSE initialization order. */
public final class IsolatedCertificateProbeTest {
    private static int checks;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }

    private static SecureIlo.CertificateInfo observe(String authority, boolean legacy) throws Exception {
        return SecureIlo.observeIsolatedCertificate(authority, legacy);
    }

    private static String line(BufferedReader reader) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fixture-output"); t.setDaemon(true); return t;
        });
        try { return executor.submit(() -> reader.readLine()).get(12, TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); }
    }

    private static final class Peer implements AutoCloseable {
        final Process process;
        final BufferedReader output;
        final String authority;
        Peer(String store, String protocol) throws Exception {
            process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                    System.getProperty("java.class.path"), "CertificateObservationTest", "serve", store, protocol)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            String ready;
            try { ready = line(output); }
            catch (Exception error) { process.destroyForcibly(); throw error; }
            if (ready == null || !ready.startsWith("PORT ")) {
                process.destroyForcibly(); throw new AssertionError("Fixture did not start");
            }
            authority = "127.0.0.1:" + Integer.parseInt(ready.substring(5));
        }
        void result(boolean handshake) throws Exception {
            check(("RESULT " + handshake + " 0").equals(line(output)), "handshake=" + handshake + ": zero application bytes");
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0, "fixture exits cleanly");
        }
        public void close() throws IOException {
            process.destroyForcibly(); output.close();
        }
    }

    private static void coldParent(String store) throws Exception {
        String policy = Security.getProperty("jdk.tls.disabledAlgorithms");
        for (String protocol : new String[]{"TLSv1.1", "TLSv1"}) {
            try (Peer peer = new Peer(store, protocol)) {
                try { observe(peer.authority, false); throw new AssertionError("Modern observation accepted legacy TLS"); }
                catch (IOException expected) { check(true, protocol + " modern observation fails"); }
                peer.result(false);
            }
            try (Peer peer = new Peer(store, protocol)) {
                SecureIlo.CertificateInfo info = observe(peer.authority, true);
                check(info.fingerprint.matches("(?:[A-F0-9]{2}:){31}[A-F0-9]{2}"), protocol + " legacy succeeds after modern failure in same parent");
                peer.result(true);
            }
            check(Objects.equals(policy, Security.getProperty("jdk.tls.disabledAlgorithms")), "isolated observations leave parent TLS policy unchanged");
        }
        // Must be the parent's FIRST TLS use. Success proves observation never cached modern JSSE restrictions here.
        try (Peer peer = new Peer(store, "TLSv1")) {
            SecureIlo.CertificateInfo info = SecureIlo.observeCertificate(peer.authority, true);
            check(info != null, "parent can select legacy policy after all isolated observations");
            peer.result(true);
        }
    }

    private static void warmDefaults(String store) throws Exception {
        SSLSocketFactory factory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        SSLContext context = SSLContext.getDefault();
        String policy = Security.getProperty("jdk.tls.disabledAlgorithms");
        boolean redirects = HttpURLConnection.getFollowRedirects();
        KeyStore keys = KeyStore.getInstance("JKS");
        try (InputStream input = new FileInputStream(store)) { keys.load(input, "test-only".toCharArray()); }
        X509Certificate certificate = (X509Certificate)keys.getCertificate("server");
        for (boolean legacy : new boolean[]{false, true}) {
            try (Peer peer = new Peer(store, legacy ? "TLSv1" : "TLSv1.2")) {
                SecureIlo.CertificateInfo info = observe(peer.authority, legacy);
                check(info.fingerprint.equals(SecureIlo.fingerprint(certificate)), "probe returns exact fingerprint");
                check(info.subject.equals(certificate.getSubjectX500Principal().getName()), "probe returns subject");
                check(info.issuer.equals(certificate.getIssuerX500Principal().getName()), "probe returns issuer");
                check(info.notBefore == certificate.getNotBefore().getTime() && info.notAfter == certificate.getNotAfter().getTime(), "probe returns validity times");
                peer.result(true);
            }
        }
        check(factory == HttpsURLConnection.getDefaultSSLSocketFactory(), "global HTTPS factory unchanged");
        check(verifier == HttpsURLConnection.getDefaultHostnameVerifier(), "global hostname verifier unchanged");
        check(context == SSLContext.getDefault(), "global SSLContext unchanged");
        check(redirects == HttpURLConnection.getFollowRedirects(), "HTTP redirect defaults unchanged");
        check(Objects.equals(policy, Security.getProperty("jdk.tls.disabledAlgorithms")), "global algorithm restrictions unchanged");
    }

    private static SecureIlo.CertificateInfo timedObserve(String authority) throws Exception {
        return SecureIlo.observeIsolatedCertificate(authority, false, 1500L);
    }

    private static void lifecycle(boolean interrupt) throws Exception {
        final Throwable[] failure = new Throwable[1];
        final boolean[] interrupted = new boolean[1];
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            String authority = "127.0.0.1:" + listener.getLocalPort();
            Thread worker = new Thread(() -> {
                try {
                    if (interrupt) observe(authority, false); else timedObserve(authority);
                    failure[0] = new AssertionError("Silent peer unexpectedly completed");
                } catch (Throwable error) { failure[0] = error; interrupted[0] = Thread.currentThread().isInterrupted(); }
            }, "test-probe-caller");
            long start = System.nanoTime();
            worker.start();
            try (Socket accepted = listener.accept()) {
                accepted.setSoTimeout(5000);
                // A real probe is alive and attempting TLS when we interrupt its owner.
                check(accepted.getInputStream().read() >= 0, "probe started TLS before cancellation/timeout");
                if (interrupt) worker.interrupt();
                worker.join(6000);
                check(!worker.isAlive(), "probe caller completes within its bound");
                check(failure[0] instanceof IOException, "probe failure is actionable IOException");
                check(failure[0].getMessage().length() < 512, "probe failure message is bounded");
                if (interrupt) {
                    check(failure[0] instanceof InterruptedIOException && interrupted[0], "cancellation preserves caller interrupt flag");
                } else {
                    check(failure[0].getMessage().contains("timed out"), "parent enforces subprocess deadline");
                    check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 6000, "deadline does not wait for child TLS read timeout");
                }
                while (accepted.getInputStream().read() != -1) { /* Drain ClientHello, then require EOF from killed child. */ }
                check(true, "cancelled/timed-out probe closes its live TCP socket");
            } finally { worker.interrupt(); worker.join(6000); }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean readers;
        do {
            readers = false;
            for (Thread thread : Thread.getAllStackTraces().keySet())
                if (thread.isAlive() && thread.getName().equals("ilo-certificate-probe-output")) readers = true;
            if (readers) Thread.sleep(20);
        } while (readers && System.nanoTime() < deadline);
        check(!readers, "no probe output-reader threads leak");
    }

    private static void invalidAndSurface() throws Exception {
        String policy = Security.getProperty("jdk.tls.disabledAlgorithms");
        for (String invalid : new String[]{null, "", "user:secret@localhost", "https://localhost", "localhost/path", "localhost:0", "localhost\\n"}) {
            try { observe(invalid, true); throw new AssertionError("Accepted invalid probe authority"); }
            catch (IllegalArgumentException expected) { check(true, "invalid authority rejected before probe launch"); }
        }
        check(Objects.equals(policy, Security.getProperty("jdk.tls.disabledAlgorithms")), "invalid observation leaves policy unchanged");
        for (Class<?> type : new Class<?>[]{CertificateProbe.class, SecureIlo.CertificateInfo.class}) {
            for (Field field : type.getDeclaredFields())
                check(!field.getName().toLowerCase(Locale.ROOT).matches(".*(password|credential|username|secret).*"), "probe data has no credential field: " + field.getName());
        }
        check(CertificateProbe.class.getDeclaredFields().length == 0, "probe retains no fields or credentials");
    }

    private static void faultyProtocol() throws Exception {
        String original = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", System.getProperty("probe.fixture.classpath"));
            for (String host : new String[]{"emptyerror.invalid", "controlerror.invalid", "truncated.invalid", "badmagic.invalid", "overflow.invalid", "stderr.invalid", "exit.invalid"}) {
                long start = System.nanoTime();
                try { observe(host, false); throw new AssertionError("Accepted faulty probe output"); }
                catch (IOException expected) {
                    String message = expected.getMessage();
                    check(message != null && !message.isEmpty() && message.length() < 512 && message.indexOf('\n') < 0,
                            host + ": bounded nonempty single-line error");
                    check(message.matches("(?i).*(check|retry|reinstall).*"), host + ": error offers an action");
                }
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 6000, host + ": faulty child cannot hang caller");
            }
            System.setProperty("java.class.path", System.getProperty("probe.fixture.classpath") + "/missing");
            try { observe("localhost", false); throw new AssertionError("Missing probe class accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("installation"), "missing probe reports installation error"); }
        } finally { System.setProperty("java.class.path", original); }
    }

    public static void main(String[] args) throws Exception {
        if (!"1.8".equals(System.getProperty("java.specification.version"))) throw new AssertionError("Java 8 required");
        if ("cold".equals(args[0])) coldParent(args[1]);
        else if ("defaults".equals(args[0])) warmDefaults(args[1]);
        else if ("timeout".equals(args[0])) lifecycle(false);
        else if ("cancel".equals(args[0])) lifecycle(true);
        else if ("surface".equals(args[0])) invalidAndSurface();
        else if ("faults".equals(args[0])) faultyProtocol();
        else throw new AssertionError("Unknown test mode");
        System.out.println("ISOLATED PROBE " + args[0] + ": " + checks + " PASS");
    }
}
