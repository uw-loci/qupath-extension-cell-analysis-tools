package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Checks shared by the JSON files QP-CAT saves for the user (configs, rule
 * sets, gates, model metadata): every number finite before writing, a clear
 * error for an empty or {@code null} file when reading, and atomic writes.
 */
public final class JsonFiles {

    /** Serializes NaN and Infinity, so the walk can find and name them. */
    private static final Gson PROBE = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    private JsonFiles() {}

    /**
     * Refuse a value whose JSON form would hold NaN or Infinity, which strict
     * JSON (and plain Gson) cannot write.
     *
     * @param value the object about to be saved
     * @param what  what it is, for the message ("The rule set")
     * @throws IOException naming the first offending field, e.g. {@code gates > CD3}
     */
    public static void requireFinite(Object value, String what) throws IOException {
        String path = firstNonFinite(PROBE.toJsonTree(value));
        if (path != null) {
            throw new IOException(what + " cannot be saved: '" + path
                    + "' is not a finite number (NaN or infinite). Set a number there "
                    + "and save again.");
        }
    }

    /**
     * The path of the first NaN or infinite number in a JSON tree, or null.
     *
     * @param root the tree
     * @return segments joined with {@code " > "}, array positions as {@code [i]}
     */
    static String firstNonFinite(JsonElement root) {
        Deque<String> path = new ArrayDeque<>();
        return walk(root, path);
    }

    private static String walk(JsonElement e, Deque<String> path) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            if (e.getAsJsonPrimitive().isNumber()) {
                double d = e.getAsDouble();
                if (!Double.isFinite(d)) return path.isEmpty() ? "(value)" : String.join(" > ", path);
            }
            return null;
        }
        if (e.isJsonArray()) {
            for (int i = 0; i < e.getAsJsonArray().size(); i++) {
                path.addLast("[" + i + "]");
                String hit = walk(e.getAsJsonArray().get(i), path);
                path.removeLast();
                if (hit != null) return hit;
            }
            return null;
        }
        for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
            path.addLast(m.getKey());
            String hit = walk(m.getValue(), path);
            path.removeLast();
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * Read a JSON object file into {@code type}.
     *
     * @param file the file
     * @param gson the Gson to read with
     * @param type the class to read
     * @param what what the file should hold, for messages ("a phenotype rule set")
     * @return the parsed object, never null
     * @throws IOException when the file is missing, not JSON, empty, {@code null}
     *                     or not a JSON object
     */
    public static <T> T readObject(Path file, Gson gson, Class<T> type, String what)
            throws IOException {
        if (file == null || !Files.exists(file)) {
            throw new IOException("File not found: " + file);
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement tree;
        try {
            tree = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IOException(file.getFileName() + " is not valid JSON, so it is not "
                    + what + ".", e);
        }
        if (tree == null || !tree.isJsonObject()) {
            throw new IOException(file.getFileName() + " is empty or does not hold "
                    + what + " (no JSON object in it).");
        }
        try {
            T out = gson.fromJson(tree, type);
            if (out == null) {
                throw new IOException(file.getFileName() + " does not hold " + what + ".");
            }
            return out;
        } catch (JsonParseException e) {
            throw new IOException(file.getFileName() + " does not hold " + what + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * Write text to a sibling temp file and move it into place, so an interrupted
     * write never truncates the previous file.
     *
     * @param file the destination
     * @param text the content, written as UTF-8
     * @throws IOException on a write failure
     */
    public static void writeAtomic(Path file, String text) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
