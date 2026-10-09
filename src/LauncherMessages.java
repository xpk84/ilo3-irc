import java.util.ListResourceBundle;

/** English is the fallback for unsupported locales and missing translations. */
public class LauncherMessages extends ListResourceBundle {
    protected Object[][] getContents() {
        return new Object[][] {
            {"legacy", "Allow legacy TLS 1.0/1.1 (for older iLO controllers)"},
            {"status.initial", "New controllers require certificate approval before your password is sent."},
            {"column.name", "Name"}, {"column.address", "Address"},
            {"column.trust", "Trust"}, {"column.lastLogin", "Last login"},
            {"connect", "Connect"}, {"certificate", "Certificate..."},
            {"rename", "Rename..."}, {"remove", "Remove trust..."},
            {"cancel", "Cancel"}, {"ok", "OK"}, {"close", "Close"},
            {"host", "iLO address (IP/DNS and port):"},
            {"username", "Username:"}, {"password", "Password:"},
            {"advanced", "Advanced: independently verify fingerprint"},
            {"fingerprint", "Expected SHA-256 (optional):"},
            {"registry", "Known controllers - passwords are not saved"},
            {"trust.changed", "CERTIFICATE CHANGED"}, {"trust.saved", "Certificate saved"},
            {"certificate.description", "Controller: {0}\nSHA-256: {1}\n\nSubject: {2}\nIssuer: {3}\nValid from: {4}\nUntil: {5}"},
            {"error.title", "Connection failed"},
            {"status.error", "Not connected. Check the address, network route and legacy TLS setting."},
            {"first.warning", "First connection: the controller identity has not yet been verified.\nAccepting remembers this certificate and detects changes on subsequent connections.\nThis cannot rule out interception of the first connection; if unsure, cancel and verify the fingerprint independently.\nUsername/password have not been sent; Java code has not been downloaded.\n\n{0}"},
            {"accept", "Accept and remember"}, {"first.title", "New iLO certificate"},
            {"status.observing", "Obtaining certificate: TLS handshake only, without sending your password..."},
            {"trust.concurrent", "Trust was changed by another window. Connect again."},
            {"status.credentials", "Certificate saved/verified. Enter your username and password."},
            {"status.declined", "Certificate not accepted. Username and password were not sent."},
            {"changed.warning", "Connection blocked. Your password was not sent.\n\nController: {0}\nSaved SHA-256: {1}\nObserved SHA-256: {2}\n\nVerify why the certificate changed. Replacement is a separate action: select the controller > Certificate... > Replace certificate..."},
            {"changed.title", "Certificate changed"},
            {"status.changed", "Certificate changed - connection blocked pending an explicit decision."},
            {"rename.prompt", "Controller name:"},
            {"remove.warning", "Remove trust for {0}?\nThe next connection will require certificate approval again."},
            {"remove.title", "Remove trust"},
            {"replace", "Replace certificate..."},
            {"replacement.legacy", "Allow legacy TLS 1.0/1.1 for this controller"},
            {"certificate.history", "\n\nAccepted: {0}\nLast successful login: {1}"},
            {"certificate.title", "Saved certificate"},
            {"status.replacing", "Obtaining a new certificate for a separate trust replacement..."},
            {"status.same", "Certificate matches the saved certificate - no replacement needed."},
            {"replacement.warning", "This changes trust; it does NOT continue the connection.\nEnsure the certificate was changed by you/an administrator, not an interceptor.\n\nOld SHA-256: {0}\n\n{1}\n\nYour password is not sent. Afterwards, click Connect to connect."},
            {"replacement.accept", "Replace saved certificate"},
            {"replacement.title", "Confirm trust replacement"},
            {"status.replaced", "Certificate replaced with your approval. No connection was made."},
            {"startup.error", "Connection/startup failed: {0}"}
        };
    }
}
