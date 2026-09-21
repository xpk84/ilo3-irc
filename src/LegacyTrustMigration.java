import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** Lazy import of pre-registry verified pins. No network or certificate observation. */
public final class LegacyTrustMigration {
    private LegacyTrustMigration() { }

    /**
     * Call with the raw entered host before certificate observation/authorization.
     * Imports never consume old pins or write completion flags: failed disk updates
     * remain retryable and an old global migration flag cannot hide other hosts.
     */
    public static synchronized void migrate(KnownControllers registry, Preferences settings, String host) throws IOException {
        String authority = KnownControllers.canonicalAuthority(host);
        if (settings.getBoolean("forgotten_" + key(authority), false)) return;
        if (registry.find(authority) != null) return;
        String importedPin = null;
        boolean legacyTls = false;
        for (String candidate : candidates(settings, host, authority)) {
            String key = key(candidate);
            String pin = settings.get("pin_" + key, "");
            if (!pin.isEmpty()) {
                pin = TrustPolicy.normalize(pin);
                if (importedPin != null && !importedPin.equals(pin))
                    throw new IOException("Conflicting legacy pins for controller; review saved trust before connecting");
                importedPin = pin;
                legacyTls |= settings.getBoolean("legacy_" + key, false);
            }
        }
        if (importedPin != null) registry.accept(authority, authority, importedPin, "Imported verified pin from 1.0.1",
                "", 0, 0, legacyTls, System.currentTimeMillis());
    }

    private static Set<String> candidates(Preferences settings, String raw, String authority) {
        Set<String> values = new LinkedHashSet<>();
        values.add(raw);
        values.add(IloSupport.validateHost(raw));
        values.add(authority);
        int split = authority.startsWith("[") ? authority.indexOf(']') + 1 : authority.indexOf(':');
        if (split < 0) split = authority.length();
        String host = authority.substring(0, split);
        String port = authority.substring(split);
        if (port.isEmpty()) values.add(host + ":443");
        if (!host.startsWith("[") && !host.matches("[0-9.]+")) {
            values.add(host + "." + port);
            if (port.isEmpty()) values.add(host + ".:443");
        }
        // Hashed keys cannot reveal arbitrary historical case/spelling. lastHost is
        // the only recoverable raw spelling besides what the user supplied now.
        String last = settings.get("lastHost", "");
        try {
            if (authority.equals(KnownControllers.canonicalAuthority(last))) values.add(last);
        } catch (IllegalArgumentException invalidLastHost) { /* Unrelated obsolete setting. */ }
        return values;
    }

    /** Flush BEFORE removing registry trust; on failure the caller must not remove it. */
    public static synchronized void forget(Preferences settings, String host) throws IOException {
        settings.putBoolean("forgotten_" + key(KnownControllers.canonicalAuthority(host)), true);
        try { settings.flush(); }
        catch (BackingStoreException ex) { throw new IOException("Cannot persist legacy trust deletion", ex); }
    }

    private static String key(String value) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
                s.append(String.format(Locale.ROOT, "%02x", b & 255));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
