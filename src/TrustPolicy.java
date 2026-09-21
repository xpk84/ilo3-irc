import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Trust decisions are separate from certificate observation and credential transport. */
final class TrustPolicy {
    static final class Declined extends IOException { private static final long serialVersionUID=1L; }
    static final class Changed extends IOException {
        private static final long serialVersionUID=1L;
        final String previous,current;
        Changed(String previous,String current) { super("Controller certificate changed; connection blocked");this.previous=previous;this.current=current; }
    }
    static String normalize(String pin) {
        if(pin==null) throw new IllegalArgumentException("Certificate fingerprint missing");
        String value=pin.replace(":","").replaceAll("\\s","").toUpperCase(java.util.Locale.ROOT);
        if(!value.matches("[0-9A-F]{64}"))throw new IllegalArgumentException("Expected SHA-256 certificate fingerprint");
        return value;
    }
    static String authorize(String saved,String observed,String independentlyExpected,BooleanSupplier acceptNew) throws IOException {
        observed=normalize(observed);
        if(saved!=null)saved=normalize(saved);
        if(independentlyExpected!=null && !independentlyExpected.trim().isEmpty() && !normalize(independentlyExpected).equals(observed))
            throw new IOException("Certificate does not match the independently supplied fingerprint");
        if(saved!=null && !saved.equals(observed))throw new Changed(saved,observed);
        if(saved==null && !acceptNew.getAsBoolean()) throw new Declined();
        return observed;
    }
}
