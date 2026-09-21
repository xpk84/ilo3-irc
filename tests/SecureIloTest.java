import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real local TLS fixture only. No controller or credentials involved. */
public class SecureIloTest {
    static int checks;
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    interface Task { void run() throws Exception; }
    static void rejected(Task action, String label) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; System.out.println("PASS " + label); return; }
        throw new AssertionError(label + " was accepted");
    }
    static String fingerprint(X509Certificate cert) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder s = new StringBuilder();
        for (byte b : digest) s.append(String.format("%02X", b & 255));
        return s.toString();
    }
    static Object transport(String authority, String pin, boolean legacy) throws Exception {
        return Class.forName("SecureIlo").getDeclaredConstructor(String.class,String.class,boolean.class)
            .newInstance(authority,pin,legacy);
    }
    static byte[] get(Object t, String path) throws Exception {
        return (byte[])t.getClass().getDeclaredMethod("get",String.class,int.class).invoke(t,path,4096);
    }
    public static void main(String[] args) throws Exception {
        // Initialize test-only legacy TLS capability before constructing fixture JSSE.
        String original = Security.getProperty("jdk.tls.disabledAlgorithms");
        Security.setProperty("jdk.tls.disabledAlgorithms", original.replaceAll("(?i)(^|,)\\s*TLSv1(?:\\.1)?\\s*(?=,|$)", ""));
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream in=Files.newInputStream(Paths.get(args[0]))) { store.load(in,"test-only".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(store,"test-only".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(km.getKeyManagers(),null,null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        AtomicInteger hits=new AtomicInteger();
        server.createContext("/ok", x -> { hits.incrementAndGet(); byte[] b="fixture-ok".getBytes("UTF-8"); x.sendResponseHeaders(200,b.length); try(OutputStream o=x.getResponseBody()){o.write(b);} });
        server.createContext("/redirect", x -> { x.getResponseHeaders().set("Location","https://example.invalid/stolen"); x.sendResponseHeaders(302,-1); x.close(); });
        server.createContext("/large", x -> { byte[] b=new byte[8192]; x.sendResponseHeaders(200,b.length); try(OutputStream o=x.getResponseBody()){o.write(b);} });
        server.createContext("/json", x -> { byte[] b="{\"ok\":true}".getBytes("UTF-8"); x.sendResponseHeaders(200,b.length); try(OutputStream o=x.getResponseBody()){o.write(b);} });
        server.start();
        try {
            String host="127.0.0.1:"+server.getAddress().getPort();
            if (args.length>1 && "baseline".equals(args[1])) {
                Class.forName("ILO3IRC").getDeclaredMethod("relaxTls").invoke(null);
                rejected(() -> { try(InputStream in=new URL("https://"+host+"/ok").openStream()){in.read();} },"unconfigured certificate must be rejected");
                return;
            }
            String pin=fingerprint((X509Certificate)store.getCertificate("server"));
            rejected(() -> transport(host,"",false),"empty pin rejected before connection");
            rejected(() -> transport(host,"not-a-sha256",false),"malformed pin rejected");
            Object good=transport(host,pin,false);
            check("fixture-ok".equals(new String(get(good,"/ok"),"UTF-8")),"exact certificate pin permits self-signed endpoint with nonmatching SAN");
            Object wrong=transport(host,String.join("",java.util.Collections.nCopies(64,"0")),false);
            rejected(() -> get(wrong,"/ok"),"mismatched pin blocks handshake");
            rejected(() -> get(good,"/redirect"),"redirect is not followed");
            rejected(() -> get(good,"https://example.invalid/ok"),"absolute URL rejected");
            rejected(() -> get(good,"//example.invalid/ok"),"scheme-relative escape rejected");
            rejected(() -> get(good,"/large"),"bounded response size enforced");
            rejected(() -> transport("user@127.0.0.1",pin,false),"userinfo rejected");
            check(hits.get()==1,"only correct pin delivered application request");
            // The applet receives process-wide defaults, but trust is still exact-pin-only.
            good.getClass().getDeclaredMethod("installAppletDefaults").invoke(good);
            try(InputStream in=new URL("https://"+host+"/ok").openStream()) { check(in.read()>=0,"applet HTTPS defaults can read pinned endpoint"); }
            HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+host+"/ok").openConnection();
            check(!c.getHostnameVerifier().verify("other.invalid",null),"applet hostname verifier rejects other hosts");
            rejected(() -> { try(InputStream in=new URL("https://localhost:"+server.getAddress().getPort()+"/ok").openStream()){in.read();} },"applet rejects other hostname even if SAN matches and certificate is pinned");
            HttpsServer old=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
            old.setHttpsConfigurator(new HttpsConfigurator(ssl) {
                public void configure(HttpsParameters p) {
                    SSLParameters parameters=getSSLContext().getDefaultSSLParameters();
                    parameters.setProtocols(new String[]{"TLSv1.1"}); p.setSSLParameters(parameters);
                }
            });
            old.createContext("/ok", x -> {byte[] b="old".getBytes("UTF-8"); x.sendResponseHeaders(200,b.length);try(OutputStream o=x.getResponseBody()){o.write(b);}});
            old.start();
            try {
                String oldHost="127.0.0.1:"+old.getAddress().getPort();
                rejected(() -> get(transport(oldHost,pin,false),"/ok"),"legacy protocol refused without opt-in");
                check("old".equals(new String(get(transport(oldHost,pin,true),"/ok"),"UTF-8")),"legacy opt-in permits pinned TLS 1.1");
                check(Security.getProperty("jdk.tls.disabledAlgorithms").contains("SSLv3"),"legacy opt-in keeps SSLv3 disabled");
            } finally {old.stop(0);}
            System.out.println("SECURE TLS TESTS: "+checks+" PASS");
        } finally { server.stop(0); }
    }
}
