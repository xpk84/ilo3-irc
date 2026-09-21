import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLException;

/** Handshake-only disposable JVM. Arguments are authority and explicit TLS policy; no credentials. */
public final class CertificateProbe {
    private CertificateProbe() { }

    public static void main(String[] args) throws IOException {
        SecureIlo.CertificateInfo info = null;
        String failure = null;
        try {
            if (args.length != 2 || !("modern".equals(args[1]) || "legacy".equals(args[1])))
                throw new IllegalArgumentException();
            // observeCertificate validates authority and selects policy BEFORE its first JSSE use.
            info = SecureIlo.observeCertificate(args[0], "legacy".equals(args[1]));
            if (info.subject.length() > 4096 || info.issuer.length() > 4096) {
                info = null;
                failure = "Certificate metadata exceeds the probe limit; inspect the controller certificate independently.";
            }
        } catch (IllegalArgumentException error) {
            failure = "Invalid certificate probe arguments; use a bare controller hostname/IP and modern or legacy policy.";
        } catch (SocketTimeoutException error) {
            failure = "Certificate handshake timed out; check the controller address and network, then retry.";
        } catch (SSLException error) {
            failure = "Certificate TLS handshake failed; check the controller TLS settings and explicitly select legacy TLS only if required.";
        } catch (GeneralSecurityException error) {
            failure = "Certificate TLS setup failed; check the Java runtime and controller certificate algorithms.";
        } catch (IOException error) {
            failure = "Cannot reach the certificate endpoint; check the controller hostname, port and network, then retry.";
        }
        // Only fixed diagnostics and certificate metadata cross this bounded binary protocol.
        // Never serialize objects, exception text, environment values, or network response bodies.
        SecureIlo.writeProbeResult(System.out, info, failure);
    }
}
