import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.prefs.*;

/** Offline migration fixtures: no user preferences or controller connections. */
public final class LegacyTrustMigrationTest {
    private static final String PIN_A = repeat('A'), PIN_B = repeat('B'), CHANGED = repeat('C');
    private static int passed;

    public static void main(String[] args) throws Exception {
        laterHostRetainsChangedCertificateProtection();
        deletedTrustNeverResurrects();
        availableHostVariantsAreImported();
        conflictingAliasesFailClosed();
        failedRegistryUpdateDoesNotMarkMigrationDone();
        failedTombstoneFlushAbortsRemoval();
        existingTrustWinsAndMissingPinsRemainEligible();
        if (args.length == 1 && "--launcher".equals(args[0])) eagerLauncherMigrationIgnoresGlobalDone();
        System.out.println("LegacyTrustMigrationTest: " + passed + " passed");
    }

    private static void laterHostRetainsChangedCertificateProtection() throws Exception {
        MemoryPreferences settings = new MemoryPreferences();
        settings.put("lastHost", "a.example");
        seed(settings, "a.example", PIN_A, false);
        seed(settings, "b.example", PIN_B, true);
        KnownControllers registry = registry();
        migrate(registry, settings, "a.example");
        check(registry.find("a.example").fingerprint.equals(PIN_A), "last host imported");
        settings.putBoolean("registryMigrationDone", true); // Existing buggy release already ran.
        migrate(registry, settings, "b.example");
        KnownControllers.Entry b = registry.find("b.example");
        check(b != null, "B must import lazily despite lastHost=A and old global done flag");
        check(b.fingerprint.equals(PIN_B) && b.legacyTls, "B pin and legacy flag preserved");
        try {
            TrustPolicy.authorize(b.fingerprint, CHANGED, "", () -> {
                throw new AssertionError("Changed legacy pin must never become a TOFU prompt");
            });
            throw new AssertionError("Changed B certificate was allowed");
        } catch (TrustPolicy.Changed expected) { }
        passed++;
    }

    private static void migrate(KnownControllers registry, Preferences settings, String host) throws Exception {
        LegacyTrustMigration.migrate(registry, settings, host);
    }

    private static void deletedTrustNeverResurrects() throws Exception {
        MemoryPreferences settings = new MemoryPreferences();
        seed(settings, "a.example", PIN_A, false);
        seed(settings, "b.example", PIN_B, false);
        KnownControllers registry = registry();
        migrate(registry, settings, "a.example");
        forget(settings, "A.EXAMPLE.:443"); // Persist tombstone BEFORE registry deletion.
        check(settings.flushes > 0, "Deletion tombstone must be flushed before removal");
        registry.remove("a.example");
        MemoryPreferences reloaded = new MemoryPreferences();
        reloaded.data.putAll(settings.persisted);
        settings = reloaded;
        migrate(registry, settings, "a.example");
        migrate(registry, settings, "A.EXAMPLE.:443");
        check(registry.find("a.example") == null, "Deleted trust must not resurrect through aliases");
        migrate(registry, settings, "b.example");
        check(registry.find("b.example") != null, "Tombstone must affect only its host");
        passed++;
    }

    private static void forget(Preferences settings, String host) throws Exception {
        LegacyTrustMigration.forget(settings, host);
    }

    private static void availableHostVariantsAreImported() throws Exception {
        String[][] variants = {
            {"ilo.example", "ILO.EXAMPLE.:443"},
            {"ilo.example.:443", "ILO.EXAMPLE.:443"},
            {"ILO.EXAMPLE.:443", "ILO.EXAMPLE.:443"},
            {"ilo.example:443", "ilo.example"},
            {"ilo.example.", "ilo.example"},
            {"ilo.example.:443", "ilo.example"},
            {"ilo.example.:8443", "ilo.example:8443"},
            {"[2001:db8::1]:443", "[2001:DB8::1]"}
        };
        for (String[] pair : variants) {
            MemoryPreferences settings = new MemoryPreferences();
            seed(settings, pair[0], PIN_A, true);
            KnownControllers registry = registry();
            migrate(registry, settings, pair[1]);
            KnownControllers.Entry entry = registry.find(pair[1]);
            check(entry != null && entry.fingerprint.equals(PIN_A) && entry.legacyTls,
                    "Available legacy variant was missed: " + Arrays.toString(pair));
        }
        MemoryPreferences settings = new MemoryPreferences();
        settings.put("lastHost", "ILo.Example.:443");
        seed(settings, "ILo.Example.:443", PIN_B, false);
        KnownControllers registry = registry();
        migrate(registry, settings, "ilo.example");
        check(registry.find("ilo.example") != null, "Recover raw lastHost spelling when canonical identity matches");
        passed++;
    }

    private static void conflictingAliasesFailClosed() throws Exception {
        MemoryPreferences settings = new MemoryPreferences();
        seed(settings, "ilo.example", PIN_A, false);
        seed(settings, "ilo.example:443", PIN_B, false);
        KnownControllers registry = registry();
        Map<String, String> before = new TreeMap<>(settings.data);
        try {
            migrate(registry, settings, "ilo.example");
            throw new AssertionError("Conflicting legacy aliases must not silently choose one pin");
        } catch (IOException expected) { }
        check(registry.find("ilo.example") == null, "Conflict must not save a partial import");
        check(settings.data.equals(before), "Conflict must not consume old pins or mark completion");
        passed++;
    }

    private static void failedRegistryUpdateDoesNotMarkMigrationDone() throws Exception {
        MemoryPreferences settings = new MemoryPreferences();
        seed(settings, "ilo.example", PIN_A, false);
        Path file = Files.createTempDirectory("migration-write-failure-").toRealPath().resolve("controllers.properties");
        KnownControllers registry = new KnownControllers(file);
        Map<String, String> before = new TreeMap<>(settings.data);
        settings.onGetKey = "legacy_" + hash("ilo.example");
        settings.onGet = () -> {
            try { Files.createDirectory(file); }
            catch (IOException e) { throw new AssertionError(e); }
        };
        try {
            migrate(registry, settings, "ilo.example");
            throw new AssertionError("Disk update must fail for directory target");
        } catch (IOException expected) { }
        check(settings.data.equals(before), "Failed disk update must not write any completion marker or consume pin");
        Files.delete(file);
        migrate(registry, settings, "ilo.example");
        check(new KnownControllers(file).find("ilo.example").fingerprint.equals(PIN_A),
                "Retry after disk repair must durably import the old pin");
        passed++;
    }

    private static void failedTombstoneFlushAbortsRemoval() throws Exception {
        MemoryPreferences settings = new MemoryPreferences();
        seed(settings, "ilo.example", PIN_A, false);
        KnownControllers registry = registry();
        migrate(registry, settings, "ilo.example");
        settings.failFlush = true;
        try {
            forget(settings, "ilo.example");
            registry.remove("ilo.example");
            throw new AssertionError("Deletion must abort if tombstone cannot be persisted");
        } catch (IOException expected) { }
        check(registry.find("ilo.example") != null, "Failed tombstone persistence must retain registry protection");
        settings.failFlush = false;
        forget(settings, "ilo.example");
        registry.remove("ilo.example");
        migrate(registry, settings, "ilo.example");
        check(registry.find("ilo.example") == null, "Retry must persist tombstone");
        passed++;
    }

    private static void existingTrustWinsAndMissingPinsRemainEligible() throws Exception {
        MemoryPreferences settings = new MemoryPreferences();
        KnownControllers registry = registry();
        migrate(registry, settings, "ilo.example");
        check(settings.data.isEmpty(), "A lookup miss must not globally or locally mark migration complete");
        seed(settings, "ilo.example", PIN_A, false);
        migrate(registry, settings, "ilo.example");
        registry.replace("ilo.example", PIN_A, PIN_B, "new", "", 0, 0, false, 1);
        migrate(registry, settings, "ilo.example");
        check(registry.find("ilo.example").fingerprint.equals(PIN_B), "Legacy data must not overwrite explicit replacement");
        passed++;
    }

    private static void eagerLauncherMigrationIgnoresGlobalDone() throws Exception {
        MemoryPreferences settings = new MemoryPreferences();
        settings.put("lastHost", "a.example");
        settings.putBoolean("registryMigrationDone", true);
        seed(settings, "a.example", PIN_A, false);
        KnownControllers registry = registry();
        Class<?> launcher = Class.forName("ILO3IRC");
        launcher.getDeclaredMethod("migrateLegacyPin", KnownControllers.class, Preferences.class).invoke(null, registry, settings);
        check(registry.find("a.example") != null, "Eager helper must use per-host migration, not global done flag");
        launcher.getDeclaredMethod("migrateLegacyPin", KnownControllers.class, String.class);
        launcher.getDeclaredMethod("forgetLegacyPin", String.class);
        passed++;
    }

    private static KnownControllers registry() throws IOException {
        return new KnownControllers(Files.createTempDirectory("legacy-trust-").toRealPath().resolve("controllers.properties"));
    }
    private static String repeat(char c) { char[] chars = new char[64]; Arrays.fill(chars, c); return new String(chars); }
    private static String hash(String host) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(host.getBytes(StandardCharsets.UTF_8)))
            value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private static void seed(Preferences prefs, String host, String pin, boolean legacy) throws Exception {
        prefs.put("pin_" + hash(host), pin);
        prefs.putBoolean("legacy_" + hash(host), legacy);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class MemoryPreferences extends AbstractPreferences {
        final Map<String, String> data = new TreeMap<>();
        final Map<String, String> persisted = new TreeMap<>();
        int flushes;
        boolean failFlush;
        String onGetKey;
        Runnable onGet;
        MemoryPreferences() { super(null, ""); }
        protected void putSpi(String key, String value) { data.put(key, value); }
        protected String getSpi(String key) {
            if (key.equals(onGetKey) && onGet != null) { Runnable action = onGet; onGet = null; action.run(); }
            return data.get(key);
        }
        protected void removeSpi(String key) { data.remove(key); }
        protected void removeNodeSpi() { data.clear(); }
        protected String[] keysSpi() { return data.keySet().toArray(new String[0]); }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected AbstractPreferences childSpi(String name) { throw new UnsupportedOperationException(); }
        protected void syncSpi() { }
        protected void flushSpi() throws BackingStoreException {
            flushes++;
            if (failFlush) throw new BackingStoreException("synthetic preferences storage failure");
            persisted.clear();
            persisted.putAll(data);
        }
    }
}
