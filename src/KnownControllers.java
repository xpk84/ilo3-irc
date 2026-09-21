import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/**
 * Explicitly accepted controller identities, never credentials or sessions (Java 8).
 * The caller supplies a file in a dedicated per-user directory: that directory is
 * made private, while existing ancestors are left alone. All operations reload
 * under a persistent sibling .lock; returned entries/lists are immutable snapshots.
 * Invalid arguments throw IllegalArgumentException; corrupt storage and stale pins
 * throw IOException. Corrupt data is never silently reset. Atomic moves are required.
 * Symlinks are refused, but a hostile same-user process swapping parent directories
 * between checks is outside this filesystem threat model.
 */
public final class KnownControllers {
    private final Path file;
    // FileLock overlaps throw within one JVM instead of blocking. Serialize our
    // short registry transactions locally too; the sibling lock covers processes.
    private static final Object JVM_LOCK = new Object();

    public static final class Entry {
        public final String authority, name, fingerprint, subject, issuer;
        public final long notBefore, notAfter, acceptedAt, lastConnected;
        public final boolean legacyTls;

        private Entry(String authority, String name, String fingerprint, String subject,
                      String issuer, long notBefore, long notAfter, long acceptedAt,
                      long lastConnected, boolean legacyTls) {
            this.authority = canonicalAuthority(authority);
            this.name = validateText(name);
            this.fingerprint = normalizeFingerprint(fingerprint);
            this.subject = validateText(subject);
            this.issuer = validateText(issuer);
            if (notAfter < notBefore || acceptedAt < 0 || lastConnected < 0)
                throw new IllegalArgumentException("Invalid controller timestamps");
            this.notBefore = notBefore;
            this.notAfter = notAfter;
            this.acceptedAt = acceptedAt;
            this.lastConnected = lastConnected;
            this.legacyTls = legacyTls;
        }
    }

    public KnownControllers(Path file) throws IOException {
        Path absolute = Objects.requireNonNull(file, "file").toAbsolutePath();
        rejectSymlinks(absolute);
        this.file = absolute.normalize();
        regularFileExists(this.file); // Reject directory/root targets before touching parent permissions.
        ensureDirectory(this.file.getParent());
        privatePermissions(this.file.getParent(), true);
        list();
    }

    public synchronized List<Entry> list() throws IOException {
        return transaction(false, entries -> Collections.unmodifiableList(new ArrayList<>(entries.values())));
    }

    public synchronized Entry find(String authority) throws IOException {
        String canonical = canonicalAuthority(authority);
        return transaction(false, entries -> entries.get(canonical));
    }

    public synchronized void accept(String authority, String name, String fingerprint,
                                    String subject, String issuer, long notBefore,
                                    long notAfter, boolean legacyTls, long now) throws IOException {
        Entry entry = new Entry(authority, name, fingerprint, subject, issuer,
                notBefore, notAfter, now, 0, legacyTls);
        transaction(true, entries -> {
            if (entries.containsKey(entry.authority)) throw new IOException("Controller already accepted");
            entries.put(entry.authority, entry);
            return null;
        });
    }

    public synchronized void replace(String authority, String expectedOldFingerprint,
                                     String newFingerprint, String subject, String issuer,
                                     long notBefore, long notAfter, boolean legacyTls, long now) throws IOException {
        transaction(true, entries -> {
            Entry old = required(entries, authority);
            if (!old.fingerprint.equals(normalizeFingerprint(expectedOldFingerprint)))
                throw new IOException("Controller fingerprint changed; refresh before proceeding");
            entries.put(old.authority, new Entry(old.authority, old.name, newFingerprint, subject,
                    issuer, notBefore, notAfter, now, old.lastConnected, legacyTls));
            return null;
        });
    }

    public synchronized void rename(String authority, String name) throws IOException {
        transaction(true, entries -> {
            Entry old = required(entries, authority);
            entries.put(old.authority, new Entry(old.authority, name, old.fingerprint, old.subject,
                    old.issuer, old.notBefore, old.notAfter, old.acceptedAt, old.lastConnected, old.legacyTls));
            return null;
        });
    }

    public synchronized void markConnected(String authority, String expectedFingerprint,
                                           long when, boolean legacyTls) throws IOException {
        transaction(true, entries -> {
            Entry old = required(entries, authority);
            if (!old.fingerprint.equals(normalizeFingerprint(expectedFingerprint)))
                throw new IOException("Controller fingerprint changed; refresh before proceeding");
            entries.put(old.authority, new Entry(old.authority, old.name, old.fingerprint, old.subject,
                    old.issuer, old.notBefore, old.notAfter, old.acceptedAt, when, legacyTls));
            return null;
        });
    }

    public synchronized void remove(String authority) throws IOException {
        transaction(true, entries -> {
            entries.remove(required(entries, authority).authority);
            return null;
        });
    }

    private interface Operation<T> { T apply(SortedMap<String, Entry> entries) throws IOException; }

    private <T> T transaction(boolean modified, Operation<T> operation) throws IOException {
        synchronized (JVM_LOCK) {
            rejectSymlinks(file);
            privatePermissions(file.getParent(), true);
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            rejectSymlinks(lockFile);
            regularFileExists(lockFile);
            Set<OpenOption> options = new HashSet<>(Arrays.asList(
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            // Never remove/replace the lock inode, even after closing the channel.
            try (FileChannel channel = FileChannel.open(lockFile, options, privateAttributes(lockFile, false))) {
                privatePermissions(lockFile, false);
                try (FileLock lock = channel.lock()) {
                    SortedMap<String, Entry> entries = read();
                    T result = operation.apply(entries);
                    if (modified) write(entries);
                    return result;
                }
            }
        }
    }

    private static Entry required(Map<String, Entry> entries, String authority) throws IOException {
        Entry entry = entries.get(canonicalAuthority(authority));
        if (entry == null) throw new IOException("Unknown controller");
        return entry;
    }

    private SortedMap<String, Entry> read() throws IOException {
        SortedMap<String, Entry> entries = new TreeMap<>();
        rejectSymlinks(file);
        privatePermissions(file.getParent(), true);
        if (!regularFileExists(file)) return entries;
        privatePermissions(file, false);
        // Properties normally overwrites duplicate keys: refuse ambiguous pins instead.
        Properties properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate registry field");
                return super.put(key, value);
            }
        };
        try {
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { properties.load(input); }
            if (!"1".equals(take(properties, "version"))) throw new IllegalArgumentException("Unknown registry version");
            int count = Integer.parseInt(take(properties, "count"));
            if (count < 0 || properties.size() != 10L * count)
                throw new IllegalArgumentException("Invalid registry entry count or fields");
            for (int i = 0; i < count; i++) {
                String p = "entry." + i + ".";
                String authority = take(properties, p + "authority");
                String name = take(properties, p + "name");
                String fingerprint = take(properties, p + "fingerprint");
                String subject = take(properties, p + "subject");
                String issuer = take(properties, p + "issuer");
                long notBefore = Long.parseLong(take(properties, p + "notBefore"));
                long notAfter = Long.parseLong(take(properties, p + "notAfter"));
                long acceptedAt = Long.parseLong(take(properties, p + "acceptedAt"));
                long lastConnected = Long.parseLong(take(properties, p + "lastConnected"));
                String legacy = take(properties, p + "legacyTls");
                if (!legacy.equals("true") && !legacy.equals("false"))
                    throw new IllegalArgumentException("Invalid legacy TLS flag");
                Entry entry = new Entry(authority, name, fingerprint, subject, issuer,
                        notBefore, notAfter, acceptedAt, lastConnected, Boolean.parseBoolean(legacy));
                if (entries.put(entry.authority, entry) != null)
                    throw new IllegalArgumentException("Duplicate controller authority");
            }
            if (!properties.isEmpty()) throw new IllegalArgumentException("Unknown registry fields");
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed known-controllers registry; refusing to reset it", e);
        }
        return entries;
    }

    private static String take(Properties properties, String key) {
        String value = (String) properties.remove(key);
        if (value == null) throw new IllegalArgumentException("Missing registry field: " + key);
        return value;
    }

    private void write(SortedMap<String, Entry> entries) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("count", Integer.toString(entries.size()));
        int i = 0;
        for (Entry e : entries.values()) {
            String p = "entry." + i++ + ".";
            properties.setProperty(p + "authority", e.authority);
            properties.setProperty(p + "name", e.name);
            properties.setProperty(p + "fingerprint", e.fingerprint);
            properties.setProperty(p + "subject", e.subject);
            properties.setProperty(p + "issuer", e.issuer);
            properties.setProperty(p + "notBefore", Long.toString(e.notBefore));
            properties.setProperty(p + "notAfter", Long.toString(e.notAfter));
            properties.setProperty(p + "acceptedAt", Long.toString(e.acceptedAt));
            properties.setProperty(p + "lastConnected", Long.toString(e.lastConnected));
            properties.setProperty(p + "legacyTls", Boolean.toString(e.legacyTls));
        }
        Path temporary = Files.createTempFile(file.getParent(), ".controllers-", ".tmp", privateAttributes(file, false));
        try {
            rejectSymlinks(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                OutputStream output = Channels.newOutputStream(channel);
                properties.store(output, "Known controllers");
                output.flush();
                channel.force(true);
            }
            rejectSymlinks(file);
            regularFileExists(file);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static FileAttribute<?>[] privateAttributes(Path path, boolean directory) {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) return new FileAttribute<?>[0];
        return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))};
    }

    private static void privatePermissions(Path path, boolean directory) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) view.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }

    private static void ensureDirectory(Path directory) throws IOException {
        rejectSymlinks(directory);
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            ensureDirectory(directory.getParent());
            try { Files.createDirectory(directory, privateAttributes(directory, true)); }
            catch (FileAlreadyExistsException concurrentCreator) { /* Validate below. */ }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Registry parent is not a directory: " + directory);
    }

    private static void rejectSymlinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symlink in registry path: " + current);
        }
    }

    private static boolean regularFileExists(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Registry path is not a regular file: " + path);
            return true;
        } catch (NoSuchFileException missing) { return false; }
    }

    private static String validateText(String text) {
        if (text == null) throw new IllegalArgumentException("Missing controller metadata");
        for (int i = 0; i < text.length(); i++)
            if (Character.isISOControl(text.charAt(i)))
                throw new IllegalArgumentException("Control character in controller metadata");
        return text;
    }

    private static String normalizeFingerprint(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("(?:[0-9a-fA-F]{64}|[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){31})"))
            throw new IllegalArgumentException("Expected a SHA-256 fingerprint (64 hex digits)");
        return fingerprint.replace(":", "").toUpperCase(Locale.ROOT);
    }

    public static String canonicalAuthority(String authority) {
        String validated = IloSupport.validateHost(authority);
        int split = validated.startsWith("[") ? validated.indexOf(']') + 1 : validated.indexOf(':');
        if (split < 0) split = validated.length();
        String host = validated.substring(0, split);
        String port = validated.substring(split);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host + (port.equals(":443") ? "" : port);
    }
}
