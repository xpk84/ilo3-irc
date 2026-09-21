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
