package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File names and the metadata JSON of a saved autoencoder model
 * ({@code <project>/classifiers/autoencoder/<name>.b64} + {@code <name>.json}).
 *
 * <p>The metadata is written and read with Gson. Until 0.26.0 it was built by
 * hand with names pasted between quotes and read back by regular expressions,
 * so a class or measurement name holding a quote or backslash broke the file,
 * and {@code 1.0E-4} read back as {@code 1.0}. Files from then that are valid
 * JSON (names without those characters) read unchanged.
 */
public final class AutoencoderModelFiles {

    /** Extension of the checkpoint file. */
    public static final String CHECKPOINT_EXT = ".b64";
    /** Extension of the metadata file. */
    public static final String METADATA_EXT = ".json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AutoencoderModelFiles() {}

    /** The inference settings a model needs to be applied, read from its metadata. */
    public record Metadata(String inputMode, int tileSize, double downsample,
                           boolean includeMask, boolean cellsOnly,
                           String[] classNames, List<String> measurements) {}

    /**
     * The base file name for a model the user named.
     *
     * @param name the typed name
     * @return a file-name-safe stem
     */
    public static String stemFor(String name) {
        return FilenameSanitizer.sanitize(name, "autoencoder");
    }

    /** True when a model with this stem is already saved in {@code dir}. */
    public static boolean exists(Path dir, String stem) {
        return FilenameSanitizer.stemTaken(dir, stem, CHECKPOINT_EXT, METADATA_EXT);
    }

    /** The first free stem ({@code stem}, {@code stem_2}, ...) in {@code dir}. */
    public static String freeStem(Path dir, String stem) throws IOException {
        return FilenameSanitizer.reserveStem(dir, stem, FilenameSanitizer.IfExists.NEW_NAME,
                "A saved model", CHECKPOINT_EXT, METADATA_EXT);
    }

    /**
     * Write the metadata JSON. A NaN or infinite training metric is written as
     * {@code null} (strict JSON has no NaN); a non-finite inference setting is refused.
     *
     * @param file     the destination
     * @param metadata key -> value (strings, numbers, booleans, lists)
     * @throws IOException when an inference setting is not finite, or on a write failure
     */
    public static void writeMetadata(Path file, Map<String, Object> metadata) throws IOException {
        Object ds = metadata.get("downsample");
        if (ds instanceof Number n && !Double.isFinite(n.doubleValue())) {
            throw new IOException("The model's downsample is " + n + "; it must be a finite "
                    + "number to save the model.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Number n && !Double.isFinite(n.doubleValue())) v = null;
            out.put(e.getKey(), v);
        }
        JsonFiles.writeAtomic(file, GSON.toJson(out));
    }

    /**
     * Read the inference settings from a metadata file, filling defaults for
     * absent keys.
     *
     * @param file the metadata JSON
     * @return the settings
     * @throws IOException when the file is not valid JSON or not a JSON object
     */
    public static Metadata readMetadata(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject o;
        try {
            JsonElement tree = JsonParser.parseString(json);
            if (tree == null || !tree.isJsonObject()) {
                throw new IOException(file.getFileName() + " is empty or holds no JSON object.");
            }
            o = tree.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException(file.getFileName() + " is not valid JSON. A model saved "
                    + "before QP-CAT 0.26.0 with a quote or backslash in a class or measurement "
                    + "name wrote a damaged file; retrain and save the model again.", e);
        }
        return new Metadata(
                string(o, "input_mode", "measurements"),
                (int) number(o, "tile_size", 64),
                number(o, "downsample", 1.0),
                bool(o, "include_mask", true),
                bool(o, "cells_only", false),
                strings(o, "class_names").toArray(String[]::new),
                strings(o, "measurements"));
    }

    private static String string(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static double number(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            double d = e.getAsDouble();
            return Double.isFinite(d) ? d : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        String s = e.getAsString();
        return "true".equalsIgnoreCase(s) ? true : "false".equalsIgnoreCase(s) ? false : def;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        JsonArray a = e.getAsJsonArray();
        for (JsonElement x : a) {
            if (x != null && !x.isJsonNull()) out.add(x.getAsString());
        }
        return out;
    }
}
