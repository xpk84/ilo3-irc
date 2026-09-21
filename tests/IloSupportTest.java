import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Offline fixtures only: synthetic ZIPs contain dummy, non-HP class bytes. */
public final class IloSupportTest {
    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (Method method : IloSupportTest.class.getDeclaredMethods()) {
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
    static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }
    interface Action { void run() throws Exception; }
    static void rejects(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable e) { if (type.isInstance(e)) return; throw new AssertionError("Wrong exception", e); }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static final String CLASS_ENTRY = "com/hp/ilo2/intgapp/intgapp.class";
    private static final String AUTHORITY = "controller.example:443";
    private static final String JAR_PATH = "/html/intgapp_test.jar";
    static Path cacheRoot() throws IOException {
        return Files.createTempDirectory("synthetic-cache-").toRealPath().resolve("cache");
    }
    static byte[] syntheticJar() throws IOException {
        return zip(CLASS_ENTRY, "DUMMY TEST DATA: NOT AN HP CLASS".getBytes("UTF-8"));
    }
    static byte[] zip(String entryName, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry(entryName);
            entry.setTime(0L); // Stable synthetic bytes across test invocations.
            zip.putNextEntry(entry);
            zip.write(data);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    static byte[] corruptSecondaryEntry() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] payload = new byte[]{51, 52, 53, 54, 55, 56};
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(CLASS_ENTRY));
            zip.write(new byte[]{1, 2, 3}); // Synthetic, not executable bytecode.
            zip.closeEntry();
            ZipEntry extra = new ZipEntry("extra.dat");
            extra.setMethod(ZipEntry.STORED);
            extra.setSize(payload.length);
            CRC32 crc = new CRC32(); crc.update(payload); extra.setCrc(crc.getValue());
            zip.putNextEntry(extra); zip.write(payload); zip.closeEntry();
        }
        byte[] corrupt = bytes.toByteArray();
        for (int i = 0; i <= corrupt.length - payload.length; i++) {
            boolean match = true;
            for (int j = 0; j < payload.length; j++) if (corrupt[i+j] != payload[j]) match = false;
            if (match) { corrupt[i] ^= 1; return corrupt; }
        }
        throw new AssertionError("Synthetic payload not found");
    }
    static byte[] largeZip(int size, boolean random) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        Random rng = new Random(7);
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(CLASS_ENTRY));
            for (int written = 0; written < size;) {
                if (random) rng.nextBytes(block);
                int n = Math.min(block.length, size - written);
                zip.write(block, 0, n); written += n;
            }
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    static void testDownloadCannotFollowInsertedSymlink() throws Exception {
        for (boolean replaceTemporary : new boolean[]{false, true}) {
            Path root = cacheRoot();
            Path outside = root.getParent().resolve("untouched.bin");
            byte[] original = new byte[]{9, 8, 7}; Files.write(outside, original);
            rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> {
                Path temporary;
                try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                    temporary = paths.filter(Files::isRegularFile).findFirst().get();
                }
                Path link = replaceTemporary ? temporary : temporary.getParent().resolve("intgapp_test.jar");
                Files.deleteIfExists(link); Files.createSymbolicLink(link, outside);
                return syntheticJar();
            }));
            eq(true, Arrays.equals(original, Files.readAllBytes(outside)));
        }
    }
    static void testZipLocalAndCentralDirectoryMustAgree() throws Exception {
        byte[] inconsistent = syntheticJar();
        // ZIP local filename starts at byte 30; central-directory name stays intact.
        eq((byte)'c', inconsistent[30]);
        inconsistent[30] = 'd';
        Path root = cacheRoot();
        rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> inconsistent));
    }
    static void testCachePrivatePermissions() throws Exception {
        Path root = cacheRoot();
        if (!Files.getFileStore(root.getParent()).supportsFileAttributeView("posix")) return;
        Set<java.nio.file.attribute.PosixFilePermission> directoryMode = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
        Set<java.nio.file.attribute.PosixFilePermission> fileMode = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
        Files.createDirectory(root);
        Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        Path jar = IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> {
            eq(directoryMode, Files.getPosixFilePermissions(root));
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths::iterator)
                    if (Files.isDirectory(path)) eq(directoryMode, Files.getPosixFilePermissions(path));
                    else { eq(true, path.toString().endsWith(".tmp")); eq(fileMode, Files.getPosixFilePermissions(path)); }
            }
            return syntheticJar();
        });
        eq(fileMode, Files.getPosixFilePermissions(jar));
        Files.setPosixFilePermissions(jar, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
        IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> { throw new AssertionError("Good cache should be reused"); });
        eq(fileMode, Files.getPosixFilePermissions(jar));
    }
    static void testDownloadFailureAndInterruptLeaveNoPartial() throws Exception {
        Path root = cacheRoot();
        rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> { throw new IOException("synthetic interruption"); }));
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) { eq(0L, paths.filter(Files::isRegularFile).count()); }
        try {
            rejects(InterruptedIOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> {
                Thread.currentThread().interrupt(); return syntheticJar();
            }));
            eq(true, Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) { eq(0L, paths.filter(Files::isRegularFile).count()); }
        rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> null));
    }
    static void testCacheRejectsSymlinks() throws Exception {
        Path base = Files.createTempDirectory("synthetic-links-").toRealPath();
        Path destination = Files.createDirectory(base.resolve("destination"));
        Path rootLink = Files.createSymbolicLink(base.resolve("root-link"), destination);
        rejects(IOException.class, () -> IloSupport.cachedJar(rootLink, AUTHORITY, JAR_PATH, IloSupportTest::syntheticJar));
        rejects(IOException.class, () -> IloSupport.cachedJar(rootLink.resolve("child"), AUTHORITY, JAR_PATH, IloSupportTest::syntheticJar));
        Path root = cacheRoot();
        Path jar = IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, IloSupportTest::syntheticJar);
        byte[] good = Files.readAllBytes(jar);
        Path external = base.resolve("external.jar"); Files.write(external, good);
        Files.delete(jar); Files.createSymbolicLink(jar, external);
        rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, IloSupportTest::syntheticJar));
        eq(true, Arrays.equals(good, Files.readAllBytes(external)));
        Files.delete(jar); Files.createSymbolicLink(jar, base.resolve("absent.jar"));
        rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, IloSupportTest::syntheticJar));
        eq(false, Files.exists(base.resolve("absent.jar")));
        Files.delete(jar); Files.delete(jar.getParent());
        Files.createSymbolicLink(jar.getParent(), destination);
        rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, IloSupportTest::syntheticJar));
        try (java.util.stream.Stream<Path> paths = Files.list(destination)) { eq(0L, paths.count()); }
    }
    static void testCacheSizeLimits() throws Exception {
        byte[][] oversized = { largeZip(16 * 1024 * 1024 + 1, true), largeZip(64 * 1024 * 1024 + 1, false) };
        for (byte[] bytes : oversized) {
            Path root = cacheRoot();
            rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> bytes));
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) { eq(0L, paths.filter(Files::isRegularFile).count()); }
            Path jar = IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, IloSupportTest::syntheticJar);
            Files.write(jar, bytes);
            final int[] calls = {0};
            IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> { calls[0]++; return syntheticJar(); });
            eq(1, calls[0]);
        }
    }
    static void testAllZipEntryIntegrity() throws Exception {
        byte[] corrupt = corruptSecondaryEntry();
        Path root = cacheRoot();
        rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> corrupt));
        Path jar = IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, IloSupportTest::syntheticJar);
        Files.write(jar, corrupt);
        final int[] calls = {0};
        IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> { calls[0]++; return syntheticJar(); });
        eq(1, calls[0]);
    }
    static void testInvalidDownloadNeverPublished() throws Exception {
        for (byte[] invalid : new byte[][] { new byte[]{1, 2, 3}, zip("not-the-applet.txt", new byte[]{1}), Arrays.copyOf(syntheticJar(), 80) }) {
            Path root = cacheRoot();
            rejects(IOException.class, () -> IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, () -> invalid));
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                eq(0L, paths.filter(Files::isRegularFile).count());
            }
        }
    }
    static void testCacheCorruptionRefetch() throws Exception {
        Path root = cacheRoot();
        final int[] calls = {0};
        byte[] fixture = syntheticJar();
        IloSupport.Downloader download = () -> { calls[0]++; return fixture; };
        Path jar = IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, download);
        Files.write(jar, new byte[]{'P', 'K', 3, 4});
        eq(jar, IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, download));
        eq(2, calls[0]);
        eq(true, Arrays.equals(fixture, Files.readAllBytes(jar)));
    }
    static void testCacheReuseAndAuthorityIsolation() throws Exception {
        Path root = cacheRoot();
        final int[] calls = {0};
        IloSupport.Downloader download = () -> { calls[0]++; return syntheticJar(); };
        Path jar = IloSupport.cachedJar(root, AUTHORITY, JAR_PATH, download);
        eq(true, Arrays.equals(syntheticJar(), Files.readAllBytes(jar)));
        eq("intgapp_test.jar", jar.getFileName().toString());
        StringBuilder digest = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(AUTHORITY.getBytes("UTF-8")))
            digest.append(String.format(Locale.ROOT, "%02x", b & 255));
        eq(root.resolve(digest.toString()).resolve("intgapp_test.jar"), jar);
        eq(jar, IloSupport.cachedJar(root, "CONTROLLER.EXAMPLE:443", JAR_PATH, download));
        eq(1, calls[0]);
        Path other = IloSupport.cachedJar(root, "other.example:443", JAR_PATH, download);
        eq(false, jar.equals(other));
        eq(2, calls[0]);
    }
    static void testHostAuthority() throws Exception {
        eq("controller.example:443", IloSupport.validateHost("Controller.Example:443"));
        eq("controller.example:443", IloSupport.validateHost("Controller.Example:00443"));
        eq("controller", IloSupport.validateHost("CONTROLLER"));
        eq("192.0.2.1:8443", IloSupport.validateHost("192.0.2.1:8443"));
        eq("[2001:db8::1]:443", IloSupport.validateHost("[2001:DB8::1]:443"));
        eq("[::1]", IloSupport.validateHost("[::1]"));
        for (String bad : new String[]{"", "https://example.invalid", "user@example.invalid", "x/path", "x?y", "x#y", "x\\y", "x\ny", "x\u007fy", " x", "x ", "x:0", "x:65536", "x:-1", "x:+80", "x:", "a..b", "-x", "x-", "a_b", "192.0.2.999", "127.1", "001.2.3.4", "2001:db8::1", "[wrong]", "[::1]suffix", "[::1]:0", "[fe80::1%en0]", "[1:2:3]"})
            rejects(IllegalArgumentException.class, () -> IloSupport.validateHost(bad));
    }
    static void testBrowserBranchesShareOneArchive() throws Exception {
        String html="if (browser === 'Netscape') { document.writeln('<embed archive=\"/html/intgapp_branch.jar\">'); } "
                + "else { document.writeln('<applet archive=\"/html/intgapp_branch.jar\">'); document.writeln('</applet>'); }";
        eq("/html/intgapp_branch.jar",IloSupport.jarPath(html));
        rejects(IllegalArgumentException.class,() -> IloSupport.jarPath(html.replace("<applet archive=\"/html/intgapp_branch.jar", "<applet archive=\"/html/intgapp_other.jar")));
        rejects(IllegalArgumentException.class,() -> IloSupport.jarPath("<embed archive='/html/intgapp.jar' archive='/html/intgapp.jar'>"));
    }
    static void testJarPath() throws Exception {
        eq("/html/intgapp_1.2-3.jar", IloSupport.jarPath("<embed archive='/html/intgapp_1.2-3.jar'>"));
        eq("/html/intgapp.jar", IloSupport.jarPath("document.writeln(\"<EMBED ARCHIVE\\=\\\"/html/intgapp.jar\\\">\");"));
        for (String bad : new String[]{"https://example.invalid/html/intgapp.jar", "//example.invalid/html/intgapp.jar", "/html/../intgapp.jar", "/html/intgapp/../a.jar", "/html/intgapp%2e.jar", "/html/intgapp.jar,/html/intgapp2.jar", "/html/intgapp.jar /html/intgapp2.jar", "/html/intgapp.jar?x", "/html/intgapp.jar#x", "intgapp.jar", "/html/other.jar"})
            rejects(IllegalArgumentException.class, () -> IloSupport.jarPath("<embed archive='" + bad + "'>"));
        rejects(IllegalArgumentException.class, () -> IloSupport.jarPath("<embed archive='/html/intgapp.jar' archive='/html/intgapp2.jar'>"));
        rejects(IllegalArgumentException.class, () -> IloSupport.jarPath("<p>no applet</p>"));
    }
    static void testHtmlEntities() {
        Map<String,String> p = IloSupport.parseAppletParams("<param name='RCINFO0' value='a&amp;b &quot;q&quot; &apos;s&apos; &lt;x&gt; &#61; &#x1F600; &copy;'><embed RCINFOLANG='&#32;'>");
        eq("a&b \"q\" 's' <x> = 😀 ©", p.get("RCINFO0"));
        eq("en", p.get("RCINFOLANG"));
    }
    static void testSplitJavascriptWrites() {
        String html = "document.writeln('<embed');\ndocument.writeln('archive=/html/intgapp.jar');\ndocument.writeln('RCINFO0=synthetic');\ndocument.writeln('>');";
        eq("/html/intgapp.jar", IloSupport.jarPath(html));
        eq("synthetic", IloSupport.parseAppletParams(html).get("RCINFO0"));
        eq("en", IloSupport.parseAppletParams("document.writeln('<param name=RCINFOLANG value=\"\" + language + \"\">');").get("RCINFOLANG"));
    }
    static void testJavascriptAppletParams() {
        String html = "document.writeln(\"<embed RCINFO0\\=\\\"synthetic\\\" INFO1\\x3d\\\"17990\\\" RCINFO1=\\\"\" + sessionKey + \"\\\" RCINFOLANG=\\\"\\\">\");";
        Map<String,String> p = IloSupport.parseAppletParams(html);
        eq("synthetic", p.get("RCINFO0"));
        eq("17990", p.get("INFO1"));
        eq(false, p.containsKey("RCINFO1"));
        eq("en", p.get("RCINFOLANG"));
    }
    static void testRawAppletParams() {
        Map<String,String> p = IloSupport.parseAppletParams("<EMBED rcinfo0='synthetic' INFO1=17990 INTG0=\"yes\" ignored='x'><pArAm VALUE='other' NAME='rcinfo2'><param name=RCINFOLANG value=''>");
        eq("synthetic", p.get("RCINFO0"));
        eq("17990", p.get("INFO1"));
        eq("yes", p.get("INTG0"));
        eq("other", p.get("RCINFO2"));
        eq("en", p.get("RCINFOLANG"));
        eq(false, p.containsKey("IGNORED"));
    }
    static void testJsonEscapesOtherControls() {
        for (int c = 127; c <= 159; c++)
            eq(String.format(Locale.ROOT, "\"\\u%04x\"", c), IloSupport.jsonString(String.valueOf((char)c)));
    }
    static void testJsonString() {
        eq("\"\"", IloSupport.jsonString(""));
        eq("\"a\\\"\\\\/é\"", IloSupport.jsonString("a\"\\/é"));
        for (int c = 0; c < 32; c++) {
            String encoded = IloSupport.jsonString(String.valueOf((char)c));
            String[] shortEscapes = {"\\b", "\\t", "\\n", "\\f", "\\r"};
            int[] shortChars = {8, 9, 10, 12, 13};
            String expected = String.format(Locale.ROOT, "\\u%04x", c);
            for (int i = 0; i < shortChars.length; i++)
                if (shortChars[i] == c) expected = shortEscapes[i];
            eq("\"" + expected + "\"", encoded);
        }
    }
}
