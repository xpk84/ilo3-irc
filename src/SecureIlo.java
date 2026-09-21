import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Single-controller HTTPS transport. Trust is an independently verified leaf-certificate SHA-256 pin. */
final class SecureIlo {
    private final String authority;
    private final String hostname;
    private final byte[] pin;
    private final SSLSocketFactory sockets;
    private final HostnameVerifier verifier;

    SecureIlo(String authority, String fingerprint, boolean legacyTls) throws GeneralSecurityException, IOException {
        URI origin;
        try { origin = new URI("https://" + authority); }
        catch (URISyntaxException ex) { throw new IOException("Invalid iLO address", ex); }
        if (origin.getHost() == null || origin.getRawUserInfo() != null ||
            !"".equals(origin.getRawPath()) || origin.getRawQuery() != null || origin.getRawFragment() != null ||
            origin.getPort() == 0 || origin.getPort() > 65535 || authority.indexOf('\\') >= 0) {
            throw new IOException("Use a bare iLO hostname/IP and optional port, not a URL");
        }
        this.authority = authority;
        this.hostname = unbracket(origin.getHost());
        this.pin = parseFingerprint(fingerprint);
        if (legacyTls) enableLegacyTls();
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new PinTrust(pin,hostname)}, new SecureRandom());
        sockets = new ProtocolFactory(ctx.getSocketFactory(), legacyTls);
        // The exact certificate pin substitutes for SAN/CA identity, but only for this host.
        verifier = (host, session) -> hostname.equalsIgnoreCase(unbracket(host));
    }

    /** Unauthenticated observation only; not a trust decision or permission to send credentials. */
    static final class CertificateInfo {
        final String fingerprint;
        final String subject;
        final String issuer;
        final long notBefore;
        final long notAfter;

        CertificateInfo(String fingerprint, String subject, String issuer, long notBefore, long notAfter) {
            this.fingerprint = fingerprint;
            this.subject = subject;
            this.issuer = issuer;
            this.notBefore = notBefore;
            this.notAfter = notAfter;
        }
    }

    private static final int PROBE_MAGIC = 0x494c4f31; // ILO1, not Java object serialization
    private static final int PROBE_LIMIT = 32768;

    /**
     * Observe in a fresh JVM so changing the legacy selection never encounters
     * cached JSSE algorithm restrictions. This method does not initialize TLS in
     * the caller, change trust defaults, or pass credentials to the child.
     */
    static CertificateInfo observeIsolatedCertificate(String authority, boolean legacyTls)
            throws IOException, GeneralSecurityException {
        return observeIsolatedCertificate(authority, legacyTls, 35000L);
    }

    /** Package-scoped shorter deadline for deterministic lifecycle tests; cannot extend the production bound. */
    static CertificateInfo observeIsolatedCertificate(String authority, boolean legacyTls, long timeoutMillis)
            throws IOException, GeneralSecurityException {
        String canonical = IloSupport.validateHost(authority);
        if (timeoutMillis < 1 || timeoutMillis > 35000L)
            throw new IllegalArgumentException("Certificate probe timeout must be between 1 and 35000 milliseconds");
        String executable = new File(new File(System.getProperty("java.home"), "bin"),
                File.separatorChar == '\\' ? "java.exe" : "java").getPath();
        ProcessBuilder builder = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                "CertificateProbe", canonical, legacyTls ? "legacy" : "modern");
        // Do not inherit JVM option injection (agents, TLS debug, client keys).
        for (String key : new String[]{"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"})
            builder.environment().remove(key);
        final Process process;
        try { process = builder.start(); }
        catch (IOException error) { throw new IOException("Cannot start certificate probe; check this Java runtime and application installation.", error); }
        java.util.concurrent.ExecutorService readers = java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "ilo-certificate-probe-output");
            thread.setDaemon(true);
            return thread;
        });
        java.util.concurrent.Future<byte[]> output = readers.submit(() -> readProbeOutput(process.getInputStream(), process));
        // Drain stderr independently; it may contain launcher diagnostics, never protocol data.
        java.util.concurrent.Future<byte[]> errors = readers.submit(() -> readProbeOutput(process.getErrorStream(), process));
        try {
            process.getOutputStream().close(); // The probe has no stdin/credential protocol.
            if (!process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS))
                throw new IOException("Certificate probe timed out; check the controller address and network, then retry.");
            byte[] bytes = output.get(2, java.util.concurrent.TimeUnit.SECONDS);
            errors.get(2, java.util.concurrent.TimeUnit.SECONDS);
            if (process.exitValue() != 0)
                throw new IOException("Certificate probe failed; check this Java runtime and application installation.");
            return decodeProbe(bytes);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Certificate observation cancelled; retry when ready.");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new IOException("Certificate probe output failed; check the application installation and retry.", error);
        } finally {
            process.destroyForcibly();
            closeProbeStream(process.getInputStream());
            closeProbeStream(process.getErrorStream());
            closeProbeStream(process.getOutputStream());
            readers.shutdownNow();
        }
    }

    private static void closeProbeStream(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { /* Child is already being terminated. */ }
    }

    private static byte[] readProbeOutput(InputStream input, Process process) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[2048];
            int count;
            while ((count = stream.read(block)) != -1) {
                if (count > PROBE_LIMIT - output.size()) {
                    process.destroyForcibly();
                    throw new IOException("Certificate probe exceeded its output limit.");
                }
                output.write(block, 0, count);
            }
            return output.toByteArray();
        }
    }

    static void writeProbeResult(OutputStream stream, CertificateInfo info, String failure) throws IOException {
        DataOutputStream output = new DataOutputStream(stream);
        output.writeInt(PROBE_MAGIC);
        output.writeBoolean(info != null);
        if (info == null) {
            output.writeUTF(failure);
        } else {
            output.writeUTF(info.fingerprint);
            output.writeUTF(info.subject);
            output.writeUTF(info.issuer);
            output.writeLong(info.notBefore);
            output.writeLong(info.notAfter);
        }
        output.flush();
    }

    private static CertificateInfo decodeProbe(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != PROBE_MAGIC) throw new IOException("Invalid certificate probe protocol; reinstall the application.");
            boolean success = input.readBoolean();
            String first = input.readUTF();
            if (!success) {
                if (first.isEmpty() || first.length() > 512 || !first.matches("[\\x20-\\x7e]+") || input.available() != 0)
                    throw new IOException("Invalid certificate probe error; reinstall the application.");
                throw new IOException(first);
            }
            String subject = input.readUTF();
            String issuer = input.readUTF();
            long notBefore = input.readLong();
            long notAfter = input.readLong();
            if (!first.matches("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}") || subject.length() > 4096 ||
                    issuer.length() > 4096 || input.available() != 0)
                throw new IOException("Invalid certificate probe response; reinstall the application.");
            return new CertificateInfo(first, subject, issuer, notBefore, notAfter);
        } catch (EOFException | UTFDataFormatException error) {
            throw new IOException("Incomplete certificate probe response; check the application installation and retry.", error);
        }
    }

    /**
     * Observe the peer's leaf using a TLS handshake only. The returned identity is
     * UNAUTHENTICATED until explicitly accepted or compared with a saved pin.
     * No HTTP, client credentials, persistence, or global HTTPS trust changes.
     */
    static CertificateInfo observeCertificate(String authority, boolean legacyTls)
            throws IOException, GeneralSecurityException {
        // Validate before DNS, TLS-policy changes, or socket creation.
        String canonical = IloSupport.validateHost(authority);
        final URI origin;
        try { origin = new URI("https://" + canonical); }
        catch (URISyntaxException ex) { throw new IOException("Invalid iLO address", ex); }
        String host = unbracket(origin.getHost());
        int port = origin.getPort() < 0 ? 443 : origin.getPort();
        // Same explicit protocol-only opt-in as the pinned transport. This may
        // relax the process TLS protocol policy, never its HTTPS trust defaults.
        if (legacyTls) enableLegacyTls();
        SSLContext context = SSLContext.getInstance("TLS");
        // This permissive manager is confined to this non-application socket.
        // Empty key managers also prevent client-certificate authentication.
        context.init(new KeyManager[0], new TrustManager[]{new X509TrustManager() {
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Server certificate missing");
                // Deliberately no CA, hostname or validity-time authentication.
            }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client authentication unsupported");
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        SSLSocketFactory factory = new ProtocolFactory(context.getSocketFactory(), legacyTls);
        try (Socket tcp = new Socket()) {
            tcp.connect(new InetSocketAddress(host, port), 10000);
            try (SSLSocket tls = (SSLSocket)factory.createSocket(tcp, host, port, true)) {
                tls.setSoTimeout(20000);
                SSLParameters parameters = tls.getSSLParameters();
                // IP literals are not legal SNI host_name values. Strip a DNS
                // root dot for SNI, while retaining the original connect host.
                if (host.indexOf(':') < 0 && !host.matches("[0-9.]+")) {
                    String sni = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
                    parameters.setServerNames(Collections.singletonList(new SNIHostName(sni)));
                } else {
                    parameters.setServerNames(Collections.emptyList());
                }
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                X509Certificate leaf = (X509Certificate)tls.getSession().getPeerCertificates()[0];
                return new CertificateInfo(fingerprint(leaf), leaf.getSubjectX500Principal().getName(),
                        leaf.getIssuerX500Principal().getName(), leaf.getNotBefore().getTime(), leaf.getNotAfter().getTime());
            }
        }
    }

    static byte[] parseFingerprint(String value) {
        if (value == null) throw new IllegalArgumentException("An independently verified SHA-256 certificate fingerprint is required");
        String normalized = value.replace(":", "").replaceAll("\\s", "");
        if (!normalized.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("SHA-256 fingerprint must contain exactly 64 hexadecimal digits");
        byte[] result = new byte[32];
        for (int i=0; i<result.length; i++) result[i]=(byte)Integer.parseInt(normalized.substring(i*2,i*2+2),16);
        return result;
    }

    static String fingerprint(X509Certificate cert) throws GeneralSecurityException {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())) {
            if (s.length()>0) s.append(':');
            s.append(String.format(Locale.ROOT,"%02X", b & 255));
        }
        return s.toString();
    }

    /** Re-enable only explicitly selected legacy protocols; preserve all other JDK restrictions. */
    static void enableLegacyTls() {
        String disabled = Security.getProperty("jdk.tls.disabledAlgorithms");
        if (disabled == null) return;
        List<String> retained = new ArrayList<>();
        for (String item : disabled.split(",")) {
            String value=item.trim();
            if (!value.equalsIgnoreCase("TLSv1") && !value.equalsIgnoreCase("TLSv1.1")) retained.add(value);
        }
        Security.setProperty("jdk.tls.disabledAlgorithms", String.join(", ",retained));
    }

    private static String unbracket(String value) {
        if (value==null) return "";
        return value.startsWith("[") && value.endsWith("]") ? value.substring(1,value.length()-1) : value;
    }

    private URL url(String path) throws IOException {
        if (path==null || !path.startsWith("/") || path.startsWith("//") || path.indexOf('\\')>=0 ||
            path.indexOf('#')>=0 || path.matches(".*[\\x00-\\x20\\x7f].*")) throw new IOException("Invalid controller resource path");
        return new URL("https://" + authority + path);
    }

    private String sessionCookie; // controller session, set only after a pinned login

    /** iLO 4 realm pages require the browser session cookie; iLO 3 ignores it. */
    void setSessionCookie(String value) {
        if (value != null && (value.isEmpty() || value.matches("[\\x00-\\x1f\\x7f]*") || value.length() > 512))
            throw new IllegalArgumentException("Invalid controller session cookie");
        sessionCookie = value;
    }

    byte[] get(String path, int limit) throws IOException { return request("GET",path,null,limit); }
    byte[] postJson(String path, String json) throws IOException { return request("POST",path,json.getBytes("UTF-8"),1024*1024); }

    private byte[] request(String method, String path, byte[] body, int limit) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection)url(path).openConnection();
        c.setSSLSocketFactory(sockets);
        c.setHostnameVerifier(verifier);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setRequestMethod(method);
        if (sessionCookie != null) c.setRequestProperty("Cookie","sessionKey=" + sessionCookie);
        try {
            if (body!=null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
                c.setFixedLengthStreamingMode(body.length);
                try(OutputStream out=c.getOutputStream()){out.write(body);}
            }
            int status=c.getResponseCode();
            if (status<200 || status>=300) throw new IOException("iLO returned HTTP " + status + "; redirects are not allowed");
            if (c.getContentLengthLong()>limit) throw new IOException("iLO response exceeds size limit");
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] block=new byte[8192]; int n;
                while((n=in.read(block))!=-1){
                    if(n>limit-out.size()) throw new IOException("iLO response exceeds size limit");
                    out.write(block,0,n);
                }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    /** HP applet uses HttpsURLConnection directly. Defaults remain pin-restricted, never trust-all. */
    void installAppletDefaults() {
        HttpsURLConnection.setDefaultSSLSocketFactory(sockets);
        HttpsURLConnection.setDefaultHostnameVerifier(verifier);
        HttpURLConnection.setFollowRedirects(false);
    }

    private static final class PinTrust extends X509ExtendedTrustManager {
        private final byte[] expected;
        private final String host;
        PinTrust(byte[] pin,String host) { expected=pin.clone();this.host=host; }
        private void verifyPeer(String peer) throws CertificateException {
            if(!host.equalsIgnoreCase(unbracket(peer))) throw new CertificateException("TLS peer is outside the configured controller");
        }
        private void verify(X509Certificate[] chain) throws CertificateException {
            if(chain==null || chain.length==0) throw new CertificateException("Server certificate missing");
            try {
                byte[] actual=MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if(!MessageDigest.isEqual(expected,actual)) throw new CertificateException("iLO certificate fingerprint mismatch; connection refused");
            } catch(NoSuchAlgorithmException ex) { throw new CertificateException(ex); }
            // Pin authenticates the exact configured leaf, including old self-signed certificates.
            // No CA/SAN or validity-time claim is made. Rotation requires independent re-verification.
        }
        public void checkServerTrusted(X509Certificate[] c,String t) throws CertificateException {verify(c);}
        public void checkServerTrusted(X509Certificate[] c,String t,Socket s) throws CertificateException {
            if(!(s instanceof SSLSocket)) throw new CertificateException("TLS peer context missing");
            SSLSession session=((SSLSocket)s).getHandshakeSession();
            if(session==null) throw new CertificateException("TLS handshake context missing");
            verifyPeer(session.getPeerHost());verify(c);
        }
        public void checkServerTrusted(X509Certificate[] c,String t,SSLEngine e) throws CertificateException {
            if(e==null) throw new CertificateException("TLS peer context missing");
            verifyPeer(e.getPeerHost());verify(c);
        }
        public void checkClientTrusted(X509Certificate[] c,String t) throws CertificateException {throw new CertificateException("Client authentication unsupported");}
        public void checkClientTrusted(X509Certificate[] c,String t,Socket s) throws CertificateException {checkClientTrusted(c,t);}
        public void checkClientTrusted(X509Certificate[] c,String t,SSLEngine e) throws CertificateException {checkClientTrusted(c,t);}
        public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
    }

    private static final class ProtocolFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final boolean legacy;
        ProtocolFactory(SSLSocketFactory delegate,boolean legacy){this.delegate=delegate;this.legacy=legacy;}
        private Socket configure(Socket s) {
            if(s instanceof SSLSocket) {
                SSLSocket tls=(SSLSocket)s;
                List<String> selected=new ArrayList<>();
                for(String p:tls.getSupportedProtocols()) {
                    if(p.equals("TLSv1.2") || p.equals("TLSv1.3") || (legacy && (p.equals("TLSv1")||p.equals("TLSv1.1")))) selected.add(p);
                }
                tls.setEnabledProtocols(selected.toArray(new String[0]));
            }
            return s;
        }
        public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
        public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
        public Socket createSocket() throws IOException{return configure(delegate.createSocket());}
        public Socket createSocket(Socket s,String h,int p,boolean close) throws IOException{return configure(delegate.createSocket(s,h,p,close));}
        public Socket createSocket(String h,int p) throws IOException{return configure(delegate.createSocket(h,p));}
        public Socket createSocket(String h,int p,InetAddress a,int lp) throws IOException{return configure(delegate.createSocket(h,p,a,lp));}
        public Socket createSocket(InetAddress h,int p) throws IOException{return configure(delegate.createSocket(h,p));}
        public Socket createSocket(InetAddress h,int p,InetAddress a,int lp) throws IOException{return configure(delegate.createSocket(h,p,a,lp));}
    }
}
