package com.tibco.bw.maven.plugin.tra;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Read/modify/write helper for TIBCO {@code .tra} launcher configuration files.
 *
 * <p>A TRA file is a flat list of {@code key value} entries consumed by the native TIBCO launcher,
 * which turns them into JVM arguments and environment variables before {@code JNI_CreateJavaVM}.
 * Both {@code designer.tra} and {@code bwengine.tra} are handled here so the Designer and engine
 * goals share one parser instead of each carrying a copy.</p>
 *
 * <h3>Two separator styles</h3>
 * <p>Entries are written either {@code key=value} or {@code key value}, and a single file mixes
 * both: {@code bwengine.tra} (BW 5.16) has 17 {@code tibco.env.*} entries with {@code =} and 5
 * with a space, while {@code designer.tra} (5.13) uses a space for all 24. Every method below
 * therefore accepts both forms and, when rewriting an existing entry, preserves the separator that
 * entry already used. When a key has to be added the dominant style of its own key family is
 * reproduced (see {@link #detectSeparator(List, String)}), so the generated file still looks like
 * the one it was copied from.</p>
 *
 * <h3>Escaping</h3>
 * <p>The launcher un-escapes backslashes when reading a value, exactly like
 * {@code java.util.Properties}: {@code \t} becomes a TAB and any other {@code \x} loses the
 * backslash. Windows paths must therefore be passed through {@link #escapePath(String)} before
 * being written, or {@code C:\target\...} is silently mangled. On POSIX this is a no-op.</p>
 */
public final class TraFile {

    private TraFile() {
        // utility class
    }

    /** Reads a TRA file into a mutable list of lines. */
    public static List<String> read(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Files.newInputStream(file.toPath()), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** Writes {@code lines} to {@code file}, one per line, in the TRA charset. */
    public static void write(File file, List<String> lines) throws IOException {
        try (Writer w = new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.ISO_8859_1)) {
            for (String line : lines) {
                w.write(line);
                w.write("\n");
            }
        }
    }

    /**
     * Doubles the backslashes of a file-system path so it survives the un-escaping the TRA launcher
     * performs when reading a value. No-op for POSIX paths (forward slashes).
     */
    public static String escapePath(String path) {
        return path == null ? "" : path.replace("\\", "\\\\");
    }

    /**
     * Prepends {@code paths} to the value of the {@code key} classpath entry, joined with
     * {@code pathSep}. The injected paths are escaped with {@link #escapePath(String)}; the
     * pre-existing value is preserved verbatim, since TIBCO already stores it escaped. Only the
     * first occurrence of {@code key} is rewritten. When the key is absent a new entry is appended.
     *
     * @param traLines lines of the source TRA file (not modified)
     * @param key      classpath variable to inject into, e.g. {@code tibco.env.CUSTOM_CP_EXT}
     * @param paths    raw (unescaped) paths to place in front of the existing value
     * @param pathSep  classpath separator of the target platform ({@link File#pathSeparator})
     * @return a new list of lines
     */
    public static List<String> injectClasspath(
            List<String> traLines, String key, List<String> paths, String pathSep) {

        List<String> escaped = new ArrayList<>(paths.size());
        for (String p : paths) {
            escaped.add(escapePath(p));
        }
        String prefix = String.join(pathSep, escaped);

        List<String> out = new ArrayList<>(traLines.size() + 1);
        boolean found = false;
        for (String line : traLines) {
            int sep = found ? -1 : separatorIndex(line, key);
            if (sep < 0) {
                out.add(line);
                continue;
            }
            found = true;
            String existing = line.substring(sep + 1);
            out.add(key + line.charAt(sep) + prefix
                + (existing.isEmpty() ? "" : pathSep + existing));
        }
        if (!found) {
            out.add(key + detectSeparator(traLines, keyFamily(key)) + prefix);
        }
        return out;
    }

    /**
     * Sets {@code key} to {@code value}, replacing the first existing entry (keeping its separator)
     * or appending a new one. Values that are file-system paths must be escaped by the caller with
     * {@link #escapePath(String)}.
     *
     * @param traLines lines of the source TRA file (not modified)
     * @return a new list of lines
     */
    public static List<String> setProperty(List<String> traLines, String key, String value) {
        List<String> out = new ArrayList<>(traLines.size() + 1);
        boolean found = false;
        for (String line : traLines) {
            int sep = found ? -1 : separatorIndex(line, key);
            if (sep < 0) {
                out.add(line);
                continue;
            }
            found = true;
            out.add(key + line.charAt(sep) + value);
        }
        if (!found) {
            out.add(key + detectSeparator(traLines, keyFamily(key)) + value);
        }
        return out;
    }

    /**
     * Index of the separator character that terminates {@code key} on {@code line}, or {@code -1}
     * when the line does not define {@code key}. Guards against prefix collisions: a line for
     * {@code tibco.env.CUSTOM_CP_EXT_OLD} is not a match for {@code tibco.env.CUSTOM_CP_EXT},
     * because the character after the key is not a separator. Package-private for tests.
     */
    static int separatorIndex(String line, String key) {
        if (line == null || !line.startsWith(key) || line.length() == key.length()) {
            return -1;
        }
        char c = line.charAt(key.length());
        return (c == '=' || c == ' ' || c == '\t') ? key.length() : -1;
    }

    /**
     * The separator style used by the entries of a key family, so an entry we have to add blends in
     * with the file it was copied from: {@code "="} when that form is the more frequent among the
     * lines starting with {@code family}, otherwise {@code " "}. Space wins ties and the
     * no-evidence case, which is the style of {@code designer.tra}. Package-private for tests.
     *
     * @param family key prefix to sample, e.g. {@code "tibco.env."} or {@code "java.property."}
     */
    static String detectSeparator(List<String> traLines, String family) {
        int equals = 0;
        int spaces = 0;
        for (String line : traLines) {
            if (line == null || !line.startsWith(family)) {
                continue;
            }
            int i = indexOfFirstSeparator(line);
            if (i < 0) {
                continue;
            }
            if (line.charAt(i) == '=') {
                equals++;
            } else {
                spaces++;
            }
        }
        return equals > spaces ? "=" : " ";
    }

    /** Position of the first {@code =}, space or tab on the line, or {@code -1} if there is none. */
    private static int indexOfFirstSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=' || c == ' ' || c == '\t') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The key family of {@code key}: its first two dot-separated segments, e.g.
     * {@code tibco.env.CUSTOM_CP_EXT} to {@code tibco.env.} and
     * {@code java.property.user.home} to {@code java.property.}. Used to sample the separator
     * style of comparable entries.
     */
    private static String keyFamily(String key) {
        int first = key.indexOf('.');
        if (first < 0) {
            return key;
        }
        int second = key.indexOf('.', first + 1);
        return second < 0 ? key.substring(0, first + 1) : key.substring(0, second + 1);
    }
}
