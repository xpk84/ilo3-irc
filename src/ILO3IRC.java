/*
 * ilo3-irc — native Java 8 host for the HP iLO 3 console applet.
 * MIT licensed launcher; HP code is downloaded from the authenticated controller,
 * never bundled. See README for independent certificate-pin verification.
 */
import javax.swing.*;
import javax.swing.event.*;
import java.applet.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.regex.*;

public final class ILO3IRC {
    static final String VERSION = "1.0.1";
    private static final Preferences SETTINGS = Preferences.userRoot().node("io/github/xpk84/ilo3irc");

    public static void main(String[] args) {
        try {
            if (args.length==1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
                System.out.println("Usage: ilo3-irc.sh [HOST]\n  --check: validate runtime/build (no GUI/network)\n  --version\n  --tls-check HOST SHA256 [--legacy-tls]: verify pinned HTTPS without login\nFirst connection requires an independently verified SHA-256 certificate fingerprint.");
                return;
            }
            if(args.length==1 && "--version".equals(args[0])) {System.out.println("ilo3-irc "+VERSION);return;}
            checkRuntime();
            if(args.length==1 && "--check".equals(args[0])) {System.out.println("ilo3-irc runtime/classes OK: Java "+System.getProperty("java.version")+", "+System.getProperty("os.arch"));return;}
            if(args.length>=1 && "--tls-check".equals(args[0])) {
                if(args.length<3 || args.length>4 || (args.length==4 && !"--legacy-tls".equals(args[3]))) throw new IllegalArgumentException("Usage: --tls-check HOST SHA256 [--legacy-tls]");
                SecureIlo transport=new SecureIlo(IloSupport.validateHost(args[1]),args[2],args.length==4);
                transport.get("/",1024*1024);
                System.out.println("Pinned HTTPS verification succeeded. No login was attempted.");return;
            }
            if(args.length>1 || (args.length==1 && args[0].startsWith("-"))) throw new IllegalArgumentException("Unknown arguments; use --help");
            String initial=args.length==1 ? IloSupport.validateHost(args[0]) : SETTINGS.get("lastHost","");
            Credentials credentials=credentials(initial);
            if(credentials==null) return;
            try { connect(credentials); }
            finally { Arrays.fill(credentials.password,'\0'); }
        } catch(Exception ex) {
            String message=ex.getMessage();
            if(message==null || message.trim().isEmpty()) message=ex.getClass().getSimpleName();
            final String error="Connection/startup failed: "+message;
            System.err.println("[ilo3-irc] "+error);
            if(!GraphicsEnvironment.isHeadless()) {
                try { SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,error,"iLO 3 Console",JOptionPane.ERROR_MESSAGE)); }
                catch(Exception ignored) { /* stderr remains available */ }
            }
            System.exit(2);
        }
    }

    static void checkRuntime() {
        if(!System.getProperty("java.specification.version","").equals("1.8")) throw new IllegalArgumentException("Java 8 is required; install Azul Zulu JDK 8 ARM64");
        // Scripts additionally compare executable slices with the physical Mac architecture.
        String arch=System.getProperty("os.arch","");
        if(System.getProperty("os.name","").equals("Mac OS X") && !arch.equals("aarch64") && !arch.equals("arm64")) {
            throw new IllegalArgumentException("This macOS launcher requires ARM64 Java 8, not an Intel/Rosetta JVM");
        }
    }

    private static final class Credentials {
        final String host,user,pin; final char[] password; final boolean legacy;
        Credentials(String h,String u,char[] p,String pin,boolean legacy) {host=h;user=u;password=p;this.pin=pin;this.legacy=legacy;}
    }

    private static Credentials credentials(String initial) throws Exception {
        final Credentials[] result=new Credentials[1];
        SwingUtilities.invokeAndWait(() -> {
            JTextField host=new JTextField(initial,24);
            JTextField user=new JTextField(24);
            JPasswordField pass=new JPasswordField(24);
            JTextField fingerprint=new JTextField(SETTINGS.get("pin_"+key(initial),""),48);
            JCheckBox legacy=new JCheckBox("Allow legacy TLS 1.0/1.1 for this controller",SETTINGS.getBoolean("legacy_"+key(initial),false));
            JCheckBox verified=new JCheckBox("I independently verified this certificate fingerprint",!fingerprint.getText().isEmpty());
            JPanel fields=new JPanel(new GridLayout(4,2,8,8));
            fields.add(new JLabel("iLO hostname / IP:"));fields.add(host);
            fields.add(new JLabel("SHA-256 certificate fingerprint:"));fields.add(fingerprint);
            fields.add(new JLabel("Username:"));fields.add(user);
            fields.add(new JLabel("Password:"));fields.add(pass);
            JPanel panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));
            panel.add(new JLabel("<html>Use a fingerprint from a separately trusted administrator/certificate export.<br>Do not trust a fingerprint merely because this connection presented it.</html>"));
            panel.add(Box.createVerticalStrut(10));panel.add(fields);panel.add(verified);panel.add(legacy);
            panel.add(new JLabel("Only host, verified pin and TLS choice are saved; never your login/password."));
            DocumentListener reset=new DocumentListener() {
                public void insertUpdate(DocumentEvent e){verified.setSelected(false);}
                public void removeUpdate(DocumentEvent e){verified.setSelected(false);}
                public void changedUpdate(DocumentEvent e){verified.setSelected(false);}
            };
            host.getDocument().addDocumentListener(reset);fingerprint.getDocument().addDocumentListener(reset);
            while(true) {
                int choice=JOptionPane.showConfirmDialog(null,panel,"iLO 3 Console",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
                if(choice!=JOptionPane.OK_OPTION) {pass.setText("");return;}
                char[] password=pass.getPassword();
                try {
                    String authority=IloSupport.validateHost(host.getText().trim());
                    SecureIlo.parseFingerprint(fingerprint.getText());
                    if(!verified.isSelected()) throw new IllegalArgumentException("Verify the fingerprint independently and confirm the checkbox before sending credentials");
                    if(user.getText().trim().isEmpty() || password.length==0) throw new IllegalArgumentException("Username and password are required");
                    String pin=fingerprint.getText().replace(":","").replaceAll("\\s","").toUpperCase(Locale.ROOT);
                    result[0]=new Credentials(authority,user.getText().trim(),password,pin,legacy.isSelected());
                    pass.setText(""); return;
                } catch(Exception ex) {
                    Arrays.fill(password,'\0');
                    JOptionPane.showMessageDialog(null,ex.getMessage(),"Check connection settings",JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        return result[0];
    }

    private static String key(String value) {
        try {
            StringBuilder s=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) s.append(String.format(Locale.ROOT,"%02x",b & 255));
            return s.toString();
        } catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }

    static String loginBody(String user,String password) {
        return "{\"method\":\"login\",\"user_login\":"+IloSupport.jsonString(user)+",\"password\":"+IloSupport.jsonString(password)+"}";
    }

    private static void connect(Credentials c) throws Exception {
        SecureIlo transport=new SecureIlo(c.host,c.pin,c.legacy);
        String body=loginBody(c.user,new String(c.password));
        String response;
        try { response=new String(transport.postJson("/json/login_session",body),StandardCharsets.UTF_8); }
        finally { Arrays.fill(c.password,'\0'); body=null; }
        Matcher session=Pattern.compile("\"session_key\"\\s*:\\s*\"([0-9a-fA-F]{16,128})\"").matcher(response);
        if(!session.find()) throw new IOException("Login rejected or unexpected iLO response; check account permissions");
        String sessionKey=session.group(1);
        SETTINGS.put("lastHost",c.host);SETTINGS.put("pin_"+key(c.host),c.pin);SETTINGS.putBoolean("legacy_"+key(c.host),c.legacy);
        // Fetch applet metadata with authenticated TLS. Session response is never logged.
        String html=new String(transport.get("/html/java_irc.html",2*1024*1024),StandardCharsets.UTF_8);
        String jarPath=IloSupport.jarPath(html);
        // Never reuse JARs downloaded by the old trust-all prototype or by another certificate.
        Path root=Paths.get(System.getProperty("user.home"),".cache","ilo3-irc","pinned-v1",c.pin.toLowerCase(Locale.ROOT));
        Path cached=IloSupport.cachedJar(root,c.host,jarPath,() -> transport.get(jarPath,16*1024*1024));
        Map<String,String> params=IloSupport.parseAppletParams(html);
        params.put("RCINFO1",sessionKey);
        Matcher port=Pattern.compile("\"remote_port\"\\s*:\\s*(\\d+)").matcher(response);
        String browserPort=port.find() ? port.group(1) : params.get("INFO1");
        // Preserve browser's legacy auxiliary argument; applet discovers actual KVM rc_port itself.
        params.put("RCINFO6",browserPort==null || browserPort.isEmpty() ? "17988" : browserPort);
        transport.installAppletDefaults();
        URL jarUrl=cached.toUri().toURL();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{jarUrl},ILO3IRC.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Applet applet=(Applet)loader.loadClass("com.hp.ilo2.intgapp.intgapp").getDeclaredConstructor().newInstance();
            URL codeBase=new URL("https://"+c.host+"/html/");
            AppletContext context=appletContext(transport,codeBase,jarUrl);
            applet.setStub(new AppletStub() {
                public boolean isActive(){return true;}
                public URL getDocumentBase(){return codeBase;}
                public URL getCodeBase(){return codeBase;}
                public String getParameter(String name){return params.get(name);}
                public AppletContext getAppletContext(){return context;}
                public void appletResize(int w,int h){}
            });
            System.out.println("[ilo3-irc] Starting controller applet; its diagnostics may contain infrastructure details.");
            // Main is not Swing EDT. Keep blocking HP lifecycle off the event queue.
            applet.init();
            applet.start();
            java.lang.reflect.Field exit=applet.getClass().getField("exit");
            while(!exit.getBoolean(applet)) Thread.sleep(250);
            params.clear();
        }
        System.out.println("[ilo3-irc] Console closed.");
        System.exit(0); // HP applet may retain non-daemon native/UI threads after stop().
    }

    private static AppletContext appletContext(SecureIlo transport,URL origin,URL jar) {
        return new AppletContext() {
            public AudioClip getAudioClip(URL url){return null;}
            public Image getImage(URL url) {
                try {
                    byte[] bytes;
                    if((url.getProtocol().equals("https") || url.getProtocol().equals("http")) && url.getHost().equalsIgnoreCase(origin.getHost()) &&
                       (url.getPort()==-1 || url.getPort()==origin.getPort())) {
                        bytes=transport.get(url.getFile(),4*1024*1024);
                    } else if(url.toExternalForm().startsWith("jar:"+jar.toExternalForm()+"!/")) {
                        try(InputStream in=url.openStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                            byte[] b=new byte[8192];int n;
                            while((n=in.read(b))!=-1){if(n>4*1024*1024-out.size())throw new IOException("Image too large");out.write(b,0,n);}
                            bytes=out.toByteArray();
                        }
                    } else throw new IOException("Image is outside trusted controller/applet");
                    return Toolkit.getDefaultToolkit().createImage(bytes);
                } catch(IOException ex) {System.err.println("[ilo3-irc] Applet image unavailable");return null;}
            }
            public Applet getApplet(String name){return null;}
            public Enumeration<Applet> getApplets(){return Collections.emptyEnumeration();}
            public void showDocument(URL url){}
            public void showDocument(URL url,String target){}
            public void showStatus(String status){} // Do not echo untrusted session-bearing messages.
            public void setStream(String key,InputStream stream){}
            public InputStream getStream(String key){return null;}
            public Iterator<String> getStreamKeys(){return Collections.emptyIterator();}
        };
    }
}
