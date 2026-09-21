import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Offline protocol parsing and integrity-checked local cache support (Java 8).
 * ZIP/CRC validation detects corruption, not authenticity: the caller must use
 * its authenticated/pinned controller transport. No unsigned hash sidecar is used.
 */
final class IloSupport {
    private IloSupport() { }

    private static final Pattern TAG = Pattern.compile("<(embed|param|applet)\\b((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    private static String entities(String text) {
        Matcher entity = Pattern.compile("&(#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]+);").matcher(text);
        StringBuffer out = new StringBuffer();
        while (entity.find()) {
            String name = entity.group(1), value = entity.group();
            if (name.startsWith("#")) {
                try {
                    boolean hex = name.length() > 2 && (name.charAt(1) == 'x' || name.charAt(1) == 'X');
                    int cp = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
                    if (Character.isValidCodePoint(cp) && !(cp >= 0xd800 && cp <= 0xdfff))
                        value = new String(Character.toChars(cp));
                } catch (IllegalArgumentException ignored) { /* Leave unknown entities literal. */ }
            } else if (name.equals("apos")) value = "'";
            else {
                javax.swing.text.html.parser.Entity named = HtmlEntities.DTD.getEntity(name);
                if (named != null) value = named.getString();
            }
            entity.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        return entity.appendTail(out).toString();
    }

    private static final class HtmlEntities {
        static final javax.swing.text.html.parser.DTD DTD;
        static {
            new javax.swing.text.html.parser.ParserDelegator();
            try { DTD = javax.swing.text.html.parser.DTD.getDTD("html32"); }
            catch (IOException e) { throw new ExceptionInInitializerError(e); }
        }
    }

    private static Map<String,String> attributes(String text) {
        Map<String,String> result = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            result.put(matcher.group(1).toUpperCase(Locale.ROOT), entities(value));
        }
        return result;
    }

    private static final Pattern WRITE = Pattern.compile("document\\.write(?:ln)?\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_LITERAL = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'", Pattern.DOTALL);

    // Decode emitted literals, never evaluate JavaScript. Expressions are marked
    // unknown so a dynamic value cannot overwrite a usable static parameter.
    private static String markup(String html) {
        StringBuilder out = new StringBuilder();
        Matcher calls = WRITE.matcher(html);
        int cursor = 0;
        while (calls.find(cursor)) {
            out.append(html, cursor, calls.start());
            int end = calls.end(), depth = 1;
            char quote = 0;
            for (; end < html.length(); end++) {
                char c = html.charAt(end);
                if (quote != 0) {
                    if (c == '\\') end++;
                    else if (c == quote) quote = 0;
                } else if (c == '\'' || c == '"') quote = c;
                else if (c == '(') depth++;
                else if (c == ')' && --depth == 0) break;
            }
            if (end >= html.length()) { out.append(html.substring(calls.start())); return out.toString(); }
            String body = html.substring(calls.end(), end);
            Matcher literals = JS_LITERAL.matcher(body);
            int last = 0;
            while (literals.find()) {
                if (!body.substring(last, literals.start()).matches("[\\s+]*")) out.append('\u0000');
                out.append(decodeJs(literals.group(1) != null ? literals.group(1) : literals.group(2)));
                last = literals.end();
            }
            if (!body.substring(last).matches("[\\s+]*")) out.append('\u0000');
            if (calls.group().toLowerCase(Locale.ROOT).startsWith("document.writeln")) out.append('\n');
            cursor = end + 1;
            int semicolon = cursor;
            while (semicolon < html.length() && Character.isWhitespace(html.charAt(semicolon))) semicolon++;
            if (semicolon < html.length() && html.charAt(semicolon) == ';') cursor = semicolon + 1;
        }
        return out.append(html.substring(cursor)).toString();
    }

    private static String decodeJs(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 == text.length()) { out.append(c); continue; }
            c = text.charAt(++i);
            int digits = c == 'x' ? 2 : c == 'u' ? 4 : 0;
            if (digits > 0 && i + digits < text.length()) {
                try { out.append((char)Integer.parseInt(text.substring(i + 1, i + 1 + digits), 16)); i += digits; continue; }
                catch (NumberFormatException ignored) { /* Preserve malformed escape. */ }
            }
            switch (c) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case '\n': break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    static Map<String,String> parseAppletParams(String html) {
        Map<String,String> result = new LinkedHashMap<>();
        Matcher tags = TAG.matcher(markup(html));
        while (tags.find()) {
            Map<String,String> attrs = attributes(tags.group(2));
            if (tags.group(1).equalsIgnoreCase("param")) {
                String name = attrs.get("NAME");
                if (name != null) putParam(result, name.toUpperCase(Locale.ROOT), attrs.get("VALUE"));
            } else {
                for (Map.Entry<String,String> attr : attrs.entrySet())
                    putParam(result, attr.getKey(), attr.getValue());
            }
        }
        if (!result.containsKey("RCINFOLANG")) result.put("RCINFOLANG", "en");
        return result;
    }

    private static void putParam(Map<String,String> result, String name, String value) {
        if (name.matches("(?:RCINFO|INFO|INTG)[A-Z0-9_]*") && value != null && value.indexOf('\u0000') < 0 && !value.trim().isEmpty())
            result.put(name, value);
    }

    private static final long MAX_COMPRESSED = 16L * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED = 64L * 1024 * 1024;
    interface Downloader { byte[] download() throws IOException; }

    /**
     * Cache layout: cacheRoot/SHA256(canonical authority)/safe archive filename.
     * The downloader should also bound its network read before allocating bytes.
     * Rejects symlinks in every path component; uses POSIX 0700/0600 where supported.
     * The caller must choose a per-user root under trusted parents. Checks do not
     * defend against a hostile same-user process swapping paths between operations.
     * Atomic moves are required (unsupported filesystems fail closed).
     */
    static Path cachedJar(Path cacheRoot, String authority, String jarPath, Downloader downloader) throws IOException {
        checkInterrupted();
        String canonical = validateHost(authority);
        String name = safeJarPath(jarPath).substring("/html/".length());
        Path directory = cacheRoot.toAbsolutePath().resolve(authorityKey(canonical));
        ensureDirectories(directory);
        privatePermissions(cacheRoot.toAbsolutePath(), true);
        privatePermissions(directory, true);
        Path target = directory.resolve(name);
        rejectSymlink(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cache target is not a regular file");
            privatePermissions(target, false);
            try { validateJar(target); return target; }
            catch (IOException corrupt) { Files.delete(target); }
        }
        Path temporary = Files.createTempFile(directory, ".download-", ".tmp");
        try {
            privatePermissions(temporary, false);
            byte[] bytes = downloader.download();
            checkInterrupted();
            if (bytes == null) throw new IOException("Downloader returned no JAR");
            if (bytes.length > MAX_COMPRESSED) throw new IOException("JAR exceeds compressed size limit");
            rejectSymlinksInPath(temporary);
            rejectSymlinksInPath(target);
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            validateJar(temporary);
            checkInterrupted();
            rejectSymlinksInPath(temporary);
            rejectSymlinksInPath(target);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            // If a parent has been replaced, do not delete through that link.
            rejectSymlinksInPath(directory);
            Files.deleteIfExists(temporary);
        }
    }

    private static void privatePermissions(Path path, boolean directory) throws IOException {
        java.nio.file.attribute.PosixFileAttributeView view = Files.getFileAttributeView(path,
                java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("JAR download interrupted");
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symlink in cache path");
    }

    private static void rejectSymlinksInPath(Path path) throws IOException {
        Path current = path.toAbsolutePath().getRoot();
        for (Path component : path.toAbsolutePath()) {
            current = current.resolve(component);
            rejectSymlink(current);
        }
    }

    private static void ensureDirectories(Path directory) throws IOException {
        Path current = directory.toAbsolutePath().getRoot();
        for (Path component : directory.toAbsolutePath()) {
            if (component.toString().equals("..")) throw new IOException("Parent traversal in cache root");
            current = current.resolve(component);
            rejectSymlink(current);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (Files.getFileStore(current.getParent()).supportsFileAttributeView("posix"))
                        Files.createDirectory(current, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
                    else Files.createDirectory(current);
                }
                catch (FileAlreadyExistsException raced) { /* Inspect the winner without following links. */ }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cache parent is not a directory");
        }
    }

    private static void validateJar(Path path) throws IOException {
        if (Files.size(path) > MAX_COMPRESSED) throw new IOException("JAR exceeds compressed size limit");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path.toFile())) {
            java.util.zip.ZipEntry applet = zip.getEntry("com/hp/ilo2/intgapp/intgapp.class");
            if (applet == null || applet.isDirectory()) throw new IOException("Applet class missing from JAR");
            byte[] buffer = new byte[8192];
            long total = 0;
            Map<String, java.util.zip.ZipEntry> central = new HashMap<>();
            Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (central.put(entry.getName(), entry) != null) throw new IOException("Duplicate JAR entry");
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                long size = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_UNCOMPRESSED) throw new IOException("JAR exceeds uncompressed size limit");
                        crc.update(buffer, 0, count); size += count;
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc())
                    throw new IOException("Corrupt JAR entry");
            }
            // ZipFile checks the central directory; ZipInputStream independently
            // checks local headers/data descriptors and rejects hidden entries.
            total = 0;
            try (java.util.zip.ZipInputStream local = new java.util.zip.ZipInputStream(
                    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
                java.util.zip.ZipEntry entry;
                while ((entry = local.getNextEntry()) != null) {
                    java.util.zip.ZipEntry expected = central.remove(entry.getName());
                    if (expected == null) throw new IOException("Inconsistent JAR directories");
                    int count;
                    while ((count = local.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_UNCOMPRESSED) throw new IOException("JAR exceeds uncompressed size limit");
                    }
                    if (entry.getSize() != expected.getSize() || entry.getCrc() != expected.getCrc()
                            || entry.getMethod() != expected.getMethod()) throw new IOException("Inconsistent JAR metadata");
                }
            }
            if (!central.isEmpty()) throw new IOException("Missing local JAR entries");
        }
    }

    private static String authorityKey(String authority) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(authority.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte b : digest) key.append(String.format(Locale.ROOT, "%02x", b & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError("SHA-256 unavailable", e); }
    }

    /** Validate without DNS/network I/O; invalid input throws IllegalArgumentException. */
    static String validateHost(String authority) {
        if (authority == null || authority.isEmpty() || !authority.matches("[A-Za-z0-9.\\[\\]:-]+"))
            throw new IllegalArgumentException("Expected a bare controller authority");
        String host, port = "";
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("Invalid IPv6 authority");
            host = authority.substring(0, close + 1);
            port = authority.substring(close + 1);
            try {
                java.net.URI uri = new java.net.URI("https://" + host).parseServerAuthority();
                if (uri.getHost() == null || host.indexOf(':') < 0) throw new IllegalArgumentException("Invalid IPv6 literal");
            } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Invalid IPv6 literal", e); }
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            if (colon >= 0) port = authority.substring(colon);
            if (host.matches("[0-9.]+")) {
                String[] parts = host.split("\\.", -1);
                if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 literal");
                for (String part : parts)
                    if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
                        throw new IllegalArgumentException("Invalid IPv4 literal");
            } else {
                String dns = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
                if (dns.length() > 253 || dns.isEmpty()) throw new IllegalArgumentException("Invalid DNS name");
                for (String label : dns.split("\\.", -1))
                    if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
                        throw new IllegalArgumentException("Invalid DNS label");
            }
        }
        if (!port.isEmpty()) {
            if (!port.matches(":[0-9]{1,5}") || Integer.parseInt(port.substring(1)) < 1 || Integer.parseInt(port.substring(1)) > 65535)
                throw new IllegalArgumentException("Invalid controller port");
            port = ":" + Integer.parseInt(port.substring(1));
        }
        return host.toLowerCase(Locale.ROOT) + port;
    }

    private static String safeJarPath(String path) {
        if (path == null || !path.matches("/html/intgapp[A-Za-z0-9_.-]*\\.jar") || path.contains(".."))
            throw new IllegalArgumentException("Unsafe or missing applet archive path");
        return path;
    }

    /** Require one distinct safe archive across browser branches; reject ambiguous tag attributes. */
    static String jarPath(String html) {
        Matcher tags = TAG.matcher(markup(html));
        String result = null;
        while (tags.find()) {
            Matcher attrs = ATTRIBUTE.matcher(tags.group(2));
            boolean foundArchive=false;
            while (attrs.find()) {
                if (!attrs.group(1).equalsIgnoreCase("archive")) continue;
                if(foundArchive) throw new IllegalArgumentException("Duplicate archive attribute");
                foundArchive=true;
                String value = attrs.group(2) != null ? attrs.group(2)
                        : attrs.group(3) != null ? attrs.group(3) : attrs.group(4);
                String path=safeJarPath(entities(value));
                if (result != null && !result.equals(path)) throw new IllegalArgumentException("Multiple distinct applet archives");
                result = path;
            }
        }
        return safeJarPath(result);
    }

    static String jsonString(String value) {
        Objects.requireNonNull(value, "value");
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\t': out.append("\\t"); break;
                case '\n': out.append("\\n"); break;
                case '\f': out.append("\\f"); break;
                case '\r': out.append("\\r"); break;
                default:
                    if (Character.isISOControl(c)) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
