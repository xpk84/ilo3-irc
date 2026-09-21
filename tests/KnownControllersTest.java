import java.io.*;
import java.nio.channels.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic offline registry fixtures; never contacts a controller. */
public final class KnownControllersTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("batch-writer")) {
            KnownControllers registry = new KnownControllers(Paths.get(args[1]));
            System.out.println("READY"); System.out.flush(); System.in.read();
            for (int i = 0; i < 20; i++) accept(registry, args[2] + i + ".example", args[2] + i);
            return;
        }
        if (args.length > 0 && args[0].equals("lock-holder")) {
            try (FileChannel channel = FileChannel.open(Paths.get(args[1]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                System.out.println("LOCKED"); System.out.flush();
                System.in.read();
            }
            return;
        }
        int passed = 0;
        Method[] methods = KnownControllersTest.class.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method method : methods) {
            if (!method.getName().startsWith("test")) continue;
            if (args.length > 0 && !Arrays.asList(args).contains(method.getName())) continue;
            try {
                method.invoke(null);
                System.out.println("PASS " + method.getName());
                passed++;
            } catch (InvocationTargetException e) {
                throw new AssertionError(method.getName(), e.getCause());
            }
        }
        if (passed == 0) throw new AssertionError("No tests selected");
        System.out.println("PASS: " + passed + " tests");
    }
    interface Action { void run() throws Exception; }
    static void rejects(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable e) { if (type.isInstance(e)) return; throw new AssertionError("Wrong exception", e); }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }
    static final String PIN = String.join("", Collections.nCopies(32, "AB"));
    static final String NEXT = String.join("", Collections.nCopies(32, "CD"));
    static Path registryFile() throws IOException {
        return Files.createTempDirectory("synthetic-registry-").toRealPath().resolve("private/controllers.properties");
    }
    static void accept(KnownControllers registry, String authority, String name) throws IOException {
        registry.accept(authority, name, PIN.toLowerCase(Locale.ROOT), "CN=Synthetic", "CN=Issuer", 10, 20, false, 15);
    }
    static void testAcceptReloadSortedImmutable() throws Exception {
        Path file = registryFile();
        KnownControllers first = new KnownControllers(file);
        eq(0, first.list().size());
        accept(first, "Z.EXAMPLE.:443", "Сервер Z");
        List<KnownControllers.Entry> snapshot = first.list();
        accept(first, "a.example:8443", "A");
        KnownControllers second = new KnownControllers(file);
        eq(2, second.list().size());
        eq("a.example:8443", second.list().get(0).authority);
        eq(1, snapshot.size());
        rejects(UnsupportedOperationException.class, () -> snapshot.clear());
        KnownControllers.Entry entry = second.find("z.example:443");
        eq("Сервер Z", entry.name);
        eq(PIN, entry.fingerprint);
        eq("CN=Synthetic", entry.subject);
        eq("CN=Issuer", entry.issuer);
        eq(10L, entry.notBefore); eq(20L, entry.notAfter);
        eq(15L, entry.acceptedAt); eq(0L, entry.lastConnected); eq(false, entry.legacyTls);
        eq(null, second.find("missing.example"));
        eq(true, Modifier.isFinal(KnownControllers.Entry.class.getModifiers()));
        for (Field field : KnownControllers.Entry.class.getDeclaredFields())
            eq(true, Modifier.isFinal(field.getModifiers()));
    }
    static void testDuplicateAcceptRefused() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ILO.EXAMPLE", "Original");
        byte[] original = Files.readAllBytes(file);
        rejects(IOException.class, () -> registry.accept("ilo.example.:443", "Override", NEXT, "X", "Y", 1, 2, true, 3));
        eq(true, Arrays.equals(original, Files.readAllBytes(file)));
        eq("Original", registry.find("ilo.example").name);
    }
    static void testRenamePreservesIdentity() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Original");
        KnownControllers.Entry old = registry.find("ilo.example");
        registry.rename("ILO.EXAMPLE.:443", "Новый сервер");
        KnownControllers.Entry renamed = new KnownControllers(file).find("ilo.example");
        eq("Новый сервер", renamed.name); eq("Original", old.name);
        eq(old.fingerprint, renamed.fingerprint); eq(old.acceptedAt, renamed.acceptedAt);
        rejects(IOException.class, () -> registry.rename("missing", "No"));
    }
    static void testMarkConnectedRequiresCurrentPin() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Original");
        registry.markConnected("ilo.example:443", PIN, 30, true);
        KnownControllers.Entry entry = new KnownControllers(file).find("ilo.example");
        eq(30L, entry.lastConnected); eq(true, entry.legacyTls); eq(15L, entry.acceptedAt);
        byte[] original = Files.readAllBytes(file);
        rejects(IOException.class, () -> registry.markConnected("ilo.example", NEXT, 40, false));
        rejects(IOException.class, () -> registry.markConnected("missing", PIN, 40, false));
        eq(true, Arrays.equals(original, Files.readAllBytes(file)));
        registry.markConnected("ilo.example", PIN.toLowerCase(Locale.ROOT), 50, false);
        eq(50L, registry.find("ilo.example").lastConnected);
        eq(false, registry.find("ilo.example").legacyTls);
    }
    static void testExplicitReplacementRejectsStalePin() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Original");
        registry.markConnected("ilo.example", PIN, 30, true);
        KnownControllers.Entry snapshot = registry.find("ilo.example");
        registry.replace("ILO.EXAMPLE.:443", PIN.toLowerCase(Locale.ROOT), NEXT, "CN=New", "CN=New Issuer", 40, 60, false, 50);
        KnownControllers.Entry entry = new KnownControllers(file).find("ilo.example");
        eq("Original", entry.name); eq(30L, entry.lastConnected); eq(50L, entry.acceptedAt);
        eq(NEXT, entry.fingerprint); eq("CN=New", entry.subject); eq("CN=New Issuer", entry.issuer);
        eq(40L, entry.notBefore); eq(60L, entry.notAfter); eq(false, entry.legacyTls);
        eq(PIN, snapshot.fingerprint);
        byte[] original = Files.readAllBytes(file);
        rejects(IOException.class, () -> registry.replace("ilo.example", PIN, PIN, "X", "Y", 1, 2, false, 55));
        rejects(IOException.class, () -> registry.replace("missing", PIN, NEXT, "X", "Y", 1, 2, false, 55));
        rejects(IOException.class, () -> registry.markConnected("ilo.example", PIN, 99, true));
        eq(true, Arrays.equals(original, Files.readAllBytes(file)));
    }
    static void testRemovalPersists() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "one.example", "One"); accept(registry, "two.example", "Two");
        registry.remove("ONE.EXAMPLE.:443");
        eq(null, registry.find("one.example")); eq(1, new KnownControllers(file).list().size());
        rejects(IOException.class, () -> registry.remove("one.example"));
        registry.remove("two.example");
        eq(0, new KnownControllers(file).list().size());
    }
    static void testFingerprintNormalizationAndRejection() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        String colon = String.join(":", Collections.nCopies(32, "ab"));
        registry.accept("ilo.example", "", colon, "", "", 1, 2, false, 3);
        eq(PIN, registry.find("ilo.example").fingerprint);
        registry.markConnected("ilo.example", colon, 4, false);
        registry.replace("ilo.example", colon, NEXT.toLowerCase(Locale.ROOT), "", "", 1, 2, false, 5);
        for (String bad : new String[]{null, "", "AB", PIN + "00", ":" + colon, colon + ":", "AA:" + PIN.substring(2), PIN.substring(0, 63) + "G", " " + PIN}) {
            byte[] original = Files.readAllBytes(file);
            rejects(IllegalArgumentException.class, () -> registry.accept("new.example", "", bad, "", "", 1, 2, false, 3));
            rejects(IllegalArgumentException.class, () -> registry.replace("ilo.example", NEXT, bad, "", "", 1, 2, false, 3));
            rejects(IllegalArgumentException.class, () -> registry.markConnected("ilo.example", bad, 3, false));
            eq(true, Arrays.equals(original, Files.readAllBytes(file)));
        }
    }
    static void testInvalidMetadataDoesNotMutate() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Original");
        byte[] original = Files.readAllBytes(file);
        for (String bad : new String[]{null, "line\nbreak", "nul\u0000", "del\u007f"}) {
            rejects(IllegalArgumentException.class, () -> registry.accept("new.example", bad, PIN, "", "", 1, 2, false, 3));
            rejects(IllegalArgumentException.class, () -> registry.rename("ilo.example", bad));
            rejects(IllegalArgumentException.class, () -> registry.replace("ilo.example", PIN, NEXT, bad, "", 1, 2, false, 3));
            rejects(IllegalArgumentException.class, () -> registry.replace("ilo.example", PIN, NEXT, "", bad, 1, 2, false, 3));
        }
        rejects(IllegalArgumentException.class, () -> registry.accept("bad/address", "", PIN, "", "", 1, 2, false, 3));
        rejects(IllegalArgumentException.class, () -> registry.accept("new.example", "", PIN, "", "", 20, 10, false, 3));
        rejects(IllegalArgumentException.class, () -> registry.accept("new.example", "", PIN, "", "", 1, 2, false, -1));
        rejects(IllegalArgumentException.class, () -> registry.markConnected("ilo.example", PIN, -1, false));
        eq(true, Arrays.equals(original, Files.readAllBytes(file)));
    }
    static Properties loadProperties(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        return p;
    }
    static void saveProperties(Path file, Properties p) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) { p.store(out, "Synthetic fixture"); }
    }
    static void assertCorruptRefused(Path file, KnownControllers existing) throws Exception {
        byte[] corrupt = Files.readAllBytes(file);
        rejects(IOException.class, () -> new KnownControllers(file));
        rejects(IOException.class, () -> existing.list());
        rejects(IOException.class, () -> existing.find("ilo.example"));
        rejects(IOException.class, () -> accept(existing, "new.example", "Never saved"));
        eq(true, Arrays.equals(corrupt, Files.readAllBytes(file)));
    }
    static void testCorruptRegistryFailsClosed() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Original");
        Properties valid = loadProperties(file);
        for (String key : valid.stringPropertyNames()) {
            Properties missing = new Properties(); missing.putAll(valid); missing.remove(key);
            saveProperties(file, missing); assertCorruptRefused(file, registry);
        }
        String[][] changes = {{"version", "99"}, {"count", "-1"}, {"count", "2147483647"},
                {"entry.0.authority", "https://bad"}, {"entry.0.fingerprint", "not-a-pin"},
                {"entry.0.notBefore", "NaN"}, {"entry.0.notAfter", "9"},
                {"entry.0.acceptedAt", "-1"}, {"entry.0.lastConnected", "-2"},
                {"entry.0.legacyTls", "yes"}, {"entry.0.name", "bad\nname"},
                {"password", "SYNTHETIC-NOT-A-REAL-SECRET"}, {"session", "SYNTHETIC-SESSION"}};
        for (String[] change : changes) {
            Properties bad = new Properties(); bad.putAll(valid); bad.setProperty(change[0], change[1]);
            saveProperties(file, bad); assertCorruptRefused(file, registry);
        }
        for (String text : new String[]{"", "garbage", "version=1\ncount=0\ncount=0\n", "version=1\ncount=0\nx=\\uQQQQ\n"}) {
            Files.write(file, text.getBytes("ISO-8859-1")); assertCorruptRefused(file, registry);
        }
        Properties duplicate = new Properties(); duplicate.putAll(valid); duplicate.setProperty("count", "2");
        for (String key : valid.stringPropertyNames())
            if (key.startsWith("entry.0.")) duplicate.setProperty(key.replace("entry.0.", "entry.1."), valid.getProperty(key));
        duplicate.setProperty("entry.1.authority", "ILO.EXAMPLE.:443");
        saveProperties(file, duplicate); assertCorruptRefused(file, registry);
    }
    static void testSymlinksAndNonRegularPathsRefused() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Original");
        Path root = file.getParent().getParent();
        Path link = root.resolve("linked.properties");
        Files.createSymbolicLink(link, file);
        rejects(IOException.class, () -> new KnownControllers(link));
        Path parentLink = root.resolve("linked-dir");
        Files.createSymbolicLink(parentLink, file.getParent());
        rejects(IOException.class, () -> new KnownControllers(parentLink.resolve("controllers.properties")));
        rejects(IOException.class, () -> new KnownControllers(parentLink.resolve("../escaped.properties")));
        Path dangling = root.resolve("dangling.properties");
        Files.createSymbolicLink(dangling, root.resolve("absent.properties"));
        rejects(IOException.class, () -> new KnownControllers(dangling));
        rejects(IOException.class, () -> new KnownControllers(file.getParent()));
        Path saved = root.resolve("saved.properties");
        Files.move(file, saved); Files.createSymbolicLink(file, saved);
        rejects(IOException.class, () -> registry.list());
        rejects(IOException.class, () -> registry.rename("ilo.example", "Unsafe"));
        eq("Original", loadProperties(saved).getProperty("entry.0.name"));
        Files.delete(file); Files.createDirectory(file);
        rejects(IOException.class, () -> registry.list());
    }
    static void testPrivateModes() throws Exception {
        Path file = registryFile().getParent().resolve("nested/controllers.properties");
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Original");
        eq("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        eq("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent().getParent())));
        eq("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        new KnownControllers(file).list();
        eq("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        eq("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
    }
    static void testExistingAncestorPermissionsUnchanged() throws Exception {
        Path file = registryFile();
        Path ancestor = file.getParent().getParent();
        Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("rwxr-xr-x"));
        new KnownControllers(file);
        eq("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(ancestor)));
    }
    static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }
    static void testAtomicReplacementKeepsOldReaderSnapshot() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Original");
        byte[] original = Files.readAllBytes(file);
        Set<String> before = new TreeSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(file.getParent())) {
            for (Path entry : files) before.add(entry.getFileName().toString());
        }
        try (InputStream oldReader = Files.newInputStream(file)) {
            registry.rename("ilo.example", "Updated");
            eq(true, Arrays.equals(original, readBytes(oldReader)));
        }
        eq("Updated", new KnownControllers(file).find("ilo.example").name);
        Set<String> after = new TreeSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(file.getParent())) {
            for (Path entry : files) after.add(entry.getFileName().toString());
        }
        eq(before, after);
        eq("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
    static void testProcessLockSerializesReadModifyWrite() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "first.example", "First");
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process holder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                KnownControllersTest.class.getName(), "lock-holder", lockFile.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            eq("LOCKED", new BufferedReader(new InputStreamReader(holder.getInputStream(), "UTF-8")).readLine());
            CountDownLatch started = new CountDownLatch(1);
            Future<?> update = executor.submit(() -> {
                started.countDown(); accept(registry, "second.example", "Second"); return null;
            });
            eq(true, started.await(5, TimeUnit.SECONDS));
            try { update.get(300, TimeUnit.MILLISECONDS); throw new AssertionError("Update bypassed process lock"); }
            catch (TimeoutException expected) { /* Lock held by other JVM. */ }
            holder.getOutputStream().write(1); holder.getOutputStream().flush();
            update.get(10, TimeUnit.SECONDS);
            eq(true, holder.waitFor(10, TimeUnit.SECONDS)); eq(0, holder.exitValue());
            eq(2, new KnownControllers(file).list().size());
            eq("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(lockFile)));
        } finally {
            holder.destroyForcibly(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
    static void testDirectoryTargetRejectedWithoutPermissionChanges() throws Exception {
        Path directory = Files.createTempDirectory("synthetic-directory-").toRealPath();
        Path parent = directory.getParent();
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(parent);
        rejects(IOException.class, () -> new KnownControllers(directory));
        eq(original, Files.getPosixFilePermissions(parent));
        rejects(IOException.class, () -> new KnownControllers(directory.getRoot()));
    }
    static void testTwoInstancesObserveAndPreserveUpdates() throws Exception {
        Path file = registryFile();
        KnownControllers one = new KnownControllers(file), two = new KnownControllers(file);
        accept(one, "one.example", "One"); accept(two, "two.example", "Two");
        eq(2, one.list().size()); eq(2, two.list().size());
        one.rename("one.example", "Renamed");
        eq("Renamed", two.find("one.example").name);
        two.markConnected("one.example", PIN, 42, true);
        one.replace("one.example", PIN, NEXT, "N", "I", 1, 2, false, 50);
        rejects(IOException.class, () -> two.replace("one.example", PIN, PIN, "N", "I", 1, 2, false, 60));
        eq(42L, two.find("one.example").lastConnected);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = executor.submit(() -> { for (int i = 0; i < 10; i++) accept(one, "a" + i, "A"); return null; });
            Future<?> b = executor.submit(() -> { for (int i = 0; i < 10; i++) accept(two, "b" + i, "B"); return null; });
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
            eq(22, one.list().size());
        } finally { executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
        two.remove("two.example"); eq(null, one.find("two.example"));
    }
    static void testTwoProcessesPreserveEveryEntry() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<Process> children = new ArrayList<>();
        try {
            for (String prefix : new String[]{"p", "q"}) {
                Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        KnownControllersTest.class.getName(), "batch-writer", file.toString(), prefix)
                        .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                children.add(child);
                eq("READY", new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8")).readLine());
            }
            for (Process child : children) { child.getOutputStream().write(1); child.getOutputStream().flush(); }
            for (Process child : children) { eq(true, child.waitFor(20, TimeUnit.SECONDS)); eq(0, child.exitValue()); }
            eq(40, registry.list().size());
            for (String prefix : new String[]{"p", "q"})
                for (int i = 0; i < 20; i++) eq(prefix + i, registry.find(prefix + i + ".example").name);
        } finally { for (Process child : children) child.destroyForcibly(); }
    }
    static void testLockSymlinkRefused() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Original");
        Path lock = file.resolveSibling(file.getFileName() + ".lock");
        Files.delete(lock); Files.createSymbolicLink(lock, file);
        byte[] original = Files.readAllBytes(file);
        rejects(IOException.class, () -> new KnownControllers(file));
        rejects(IOException.class, () -> registry.list());
        rejects(IOException.class, () -> registry.rename("ilo.example", "Unsafe"));
        eq(true, Arrays.equals(original, Files.readAllBytes(file)));
    }
    static void testOnlyExplicitIdentityFieldsPersist() throws Exception {
        Path file = registryFile();
        KnownControllers registry = new KnownControllers(file);
        accept(registry, "ilo.example", "Identity");
        Set<String> fields = new TreeSet<>(Arrays.asList("authority", "name", "fingerprint", "subject", "issuer",
                "notBefore", "notAfter", "acceptedAt", "lastConnected", "legacyTls"));
        Set<String> actual = new TreeSet<>();
        for (Field field : KnownControllers.Entry.class.getDeclaredFields()) actual.add(field.getName());
        eq(fields, actual);
        Set<String> keys = new TreeSet<>(Arrays.asList("version", "count"));
        for (String field : fields) keys.add("entry.0." + field);
        eq(keys, loadProperties(file).stringPropertyNames());
        eq(0L, Files.size(file.resolveSibling(file.getFileName() + ".lock")));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(file.getParent())) {
            for (Path entry : files) {
                String persisted = new String(Files.readAllBytes(entry), "ISO-8859-1").toLowerCase(Locale.ROOT);
                for (String forbidden : new String[]{"password", "session", "token", "rcinfo"})
                    eq(false, persisted.contains(forbidden));
            }
        }
    }
    static void testCanonicalAuthority() throws Exception {
        eq("ilo.example", KnownControllers.canonicalAuthority("ILO.Example.:443"));
        eq("ilo.example:8443", KnownControllers.canonicalAuthority("ILO.Example.:08443"));
        eq("192.0.2.5", KnownControllers.canonicalAuthority("192.0.2.5:443"));
        eq("[2001:db8::1]", KnownControllers.canonicalAuthority("[2001:DB8::1]:443"));
        for (String bad : new String[]{null, "", "https://ilo.example", "user@ilo.example", "ilo/path", "ilo:0", "ilo:65536", "bad..name", "192.0.2.999", "a\nb", "host:443:"})
            rejects(IllegalArgumentException.class, () -> KnownControllers.canonicalAuthority(bad));
    }
}
