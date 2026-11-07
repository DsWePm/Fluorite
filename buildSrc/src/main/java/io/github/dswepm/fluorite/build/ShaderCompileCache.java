package io.github.dswepm.fluorite.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Local, validated per-variant results. Compiler-emitted dependencies own invalidation. */
public final class ShaderCompileCache {
    private final Path directory;
    private final Path workingDirectory;

    public ShaderCompileCache(Path directory, Path workingDirectory) {
        this.directory = directory;
        this.workingDirectory = workingDirectory;
    }

    public boolean copyIfValid(String name, String signature, Path published, Path scratch) {
        try {
            Properties entry = read(directory.resolve(name + ".properties"));
            if (!"1".equals(entry.getProperty("format"))
                    || !signature.equals(entry.getProperty("signature"))
                    || !Files.isRegularFile(published)
                    || !sha256(published).equals(entry.getProperty("output"))) return false;
            int count = Integer.parseInt(entry.getProperty("dependencies"));
            if (count < 1) return false;
            for (int i = 0; i < count; i++) {
                Path source = Path.of(entry.getProperty("path." + i));
                if (!Files.isRegularFile(source)
                        || !sha256(source).equals(entry.getProperty("hash." + i))) return false;
            }
            Files.copy(published, scratch, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | IllegalArgumentException | NullPointerException failure) {
            // Missing, partial or invalid local metadata is a cache miss, never an accepted binary.
            return false;
        }
    }

    /** Call only after the compiler and validator both succeeded; publish metadata after all jobs. */
    public Properties compiled(String signature, Path output, Path depfile) throws IOException {
        List<Path> dependencies = parseDependencies(Files.readString(depfile)).stream()
                .map(workingDirectory::resolve).map(Path::normalize).map(Path::toAbsolutePath)
                .distinct().sorted().toList();
        if (dependencies.isEmpty()) throw new IOException("Compiler emitted no shader dependencies: " + depfile);
        Properties entry = new Properties();
        entry.setProperty("format", "1");
        entry.setProperty("signature", signature);
        entry.setProperty("output", sha256(output));
        entry.setProperty("dependencies", Integer.toString(dependencies.size()));
        for (int i = 0; i < dependencies.size(); i++) {
            Path path = dependencies.get(i);
            entry.setProperty("path." + i, path.toString());
            entry.setProperty("hash." + i, sha256(path));
        }
        return entry;
    }

    public void store(String name, Properties entry) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, name, ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                entry.store(writer, "Validated shader dependencies");
            }
            Files.move(temporary, directory.resolve(name + ".properties"), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Include previous external dependencies in Gradle's own up-to-date check as well. */
    public static List<Path> dependencyFiles(Path directory) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(directory)) return result;
        try (var entries = Files.list(directory)) {
            for (Path path : entries.filter(p -> p.toString().endsWith(".properties")).toList()) {
                try {
                    Properties entry = read(path);
                    int count = Integer.parseInt(entry.getProperty("dependencies"));
                    for (int i = 0; i < count; i++) result.add(Path.of(entry.getProperty("path." + i)));
                } catch (IOException | IllegalArgumentException | NullPointerException ignored) {
                    // The task action will reject this entry and rebuild it.
                }
            }
        }
        return result;
    }

    /** Make depfiles escape spaces, colons and backslashes; Slang uses Windows paths here. */
    public static List<String> parseDependencies(String text) throws IOException {
        text = text.replace("\\\r\n", " ").replace("\\\n", " ");
        int separator = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\\') { i++; continue; }
            if (text.charAt(i) == ':' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                separator = i; break;
            }
        }
        if (separator < 0) throw new IOException("Missing dependency rule separator");
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = separator + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (++i == text.length()) throw new IOException("Incomplete dependency escape");
                token.append(text.charAt(i));
            } else if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '$') {
                token.append('$'); i++;
            } else if (Character.isWhitespace(c)) {
                if (!token.isEmpty()) { result.add(token.toString()); token.setLength(0); }
            } else {
                token.append(c);
            }
        }
        if (!token.isEmpty()) result.add(token.toString());
        return result;
    }

    public static String signature(List<String> values) {
        MessageDigest digest = digest();
        for (String value : values) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Track binaries and Slang's adjacent compiler libraries, not merely a PATH command string. */
    public static String toolchainFingerprint(List<String> commands, Path workingDirectory) throws IOException {
        List<String> values = new ArrayList<>();
        for (String command : commands) {
            Path executable = workingDirectory.resolve(command).normalize();
            if (!Files.isRegularFile(executable)) {
                executable = null;
                String searchPath = System.getenv("PATH");
                if (searchPath == null) searchPath = System.getenv("Path");
                for (String folder : (searchPath == null ? "" : searchPath).split(java.io.File.pathSeparator)) {
                    Path candidate = Path.of(folder.replace("\"", "")).resolve(command);
                    if (Files.isRegularFile(candidate)) { executable = candidate; break; }
                }
            }
            if (executable == null) throw new IOException("Shader tool not found: " + command);
            executable = executable.toAbsolutePath();
            values.add(executable.toString()); values.add(sha256(executable));
            if (executable.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains("slang")) {
                try (var siblings = Files.list(executable.getParent())) {
                    for (Path library : siblings.filter(Files::isRegularFile).filter(path -> {
                        String n = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return (n.startsWith("slang") || n.startsWith("libslang"))
                                && (n.endsWith(".dll") || n.contains(".so") || n.endsWith(".dylib"));
                    }).sorted().toList()) {
                        values.add(library.toString()); values.add(sha256(library));
                    }
                }
            }
        }
        return signature(values);
    }

    private static Properties read(Path path) throws IOException {
        Properties result = new Properties();
        try (var reader = Files.newBufferedReader(path)) { result.load(reader); }
        return result;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
