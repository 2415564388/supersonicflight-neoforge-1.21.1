package com.baranhan123.supersonicflight.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads and writes config files as JSON with {@code //} comments.
 *
 * <p><b>These files are deliberately not valid JSON.</b> The options ship with Chinese explanations
 * because the config is read by the pack author and players, and JSON has no comment syntax — so the
 * notes are written as ordinary {@code //} lines and stripped again before parsing. An editor will
 * flag the file; that is expected, and removing the comments to silence it is the wrong fix.
 *
 * <p>Gson's lenient reader tolerates {@code //} (and {@code #}) natively — verified against Gson
 * 2.10.1 — but that is not relied on here: the stripper below is explicit, so swapping the parser or
 * tightening its leniency cannot silently corrupt a config. It tracks string literals, so a value
 * containing {@code //} survives untouched.
 *
 * <p>Writing goes through reflection over the declared fields so options keep their declaration
 * order and each one is preceded by its {@link ConfigComment}.
 */
public final class CommentedJson {

    private CommentedJson() {
    }

    /** Parses {@code file}, ignoring any {@code //} comment lines. Returns null if unreadable. */
    public static <T> T load(File file, Class<T> type, Gson gson) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return gson.fromJson(stripComments(text), type);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Writes {@code instance} as pretty JSON with a {@code //} comment above every documented option. */
    public static void save(File file, Object instance, Gson gson) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");

        Field[] fields = instance.getClass().getDeclaredFields();
        int written = 0;
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;

            try {
                field.setAccessible(true);
                Object value = field.get(instance);

                ConfigComment comment = field.getAnnotation(ConfigComment.class);
                if (comment != null) {
                    for (String line : comment.value().split("\n")) {
                        out.append("  // ").append(line).append('\n');
                    }
                }

                JsonElement json = gson.toJsonTree(value);
                out.append("  \"").append(field.getName()).append("\": ").append(json);
                // Trailing comma on every entry but the last keeps this simple and is valid JSON.
                out.append(written < countSerializableFields(instance.getClass()) - 1 ? ",\n\n" : "\n");
                written++;
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        out.append("}\n");

        try {
            Files.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static int countSerializableFields(Class<?> type) {
        int count = 0;
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) count++;
        }
        return count;
    }

    /**
     * Removes {@code //} line comments. String literals are tracked so a value containing a double
     * slash is not truncated — the {@code grabItem} option is a resource location that could
     * plausibly contain one.
     */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }

            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                if (i < text.length()) out.append('\n');
                continue;
            }

            out.append(c);
        }
        return out.toString();
    }
}
