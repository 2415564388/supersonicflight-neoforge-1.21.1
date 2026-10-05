package com.baranhan123.supersonicflight.client.gui;

import com.baranhan123.supersonicflight.config.ConfigComment;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds Cloth Config entries straight off a config class's fields, so the screen does not restate
 * anything the config already says.
 *
 * <p>Reflection is deliberate, and matches how {@code CommentedJson} already works: it walks the
 * same declared fields and reads the same {@link ConfigComment}. Three things come out of that:
 *
 * <ul>
 *   <li><b>Labels</b> are the field names — greppable, and identical to the keys in the JSON file.</li>
 *   <li><b>Tooltips</b> are the field's {@code @ConfigComment}, split on its newlines. Those notes are
 *       already written for a human reader, so the GUI reuses them instead of duplicating 40 strings
 *       that would then drift.</li>
 *   <li><b>Defaults</b> come from a freshly constructed instance of the config class, so the "reset to
 *       default" behaviour cannot drift from the field initialisers either.</li>
 * </ul>
 *
 * <p>Every helper throws on an unknown field name rather than skipping it. A typo here would
 * otherwise ship as a silently missing option, which is far harder to notice than a crash.
 */
final class ConfigEntries {

    /** Fresh instances, one per config class, used as the source of default values. */
    private static final Map<Class<?>, Object> DEFAULTS = new HashMap<>();

    private ConfigEntries() {
    }

    static void bool(ConfigCategory category, ConfigEntryBuilder eb, Object config, String name) {
        Field field = field(config, name);
        category.addEntry(eb.startBooleanToggle(label(name), read(field, config, Boolean.class))
                .setDefaultValue(read(field, defaultsFor(config), Boolean.class))
                .setTooltip(tooltip(field))
                .setSaveConsumer(value -> write(field, config, value))
                .build());
    }

    static void intField(ConfigCategory category, ConfigEntryBuilder eb, Object config, String name,
                         int min, int max) {
        Field field = field(config, name);
        category.addEntry(eb.startIntField(label(name), read(field, config, Integer.class))
                .setMin(min)
                .setMax(max)
                .setDefaultValue(read(field, defaultsFor(config), Integer.class))
                .setTooltip(tooltip(field))
                .setSaveConsumer(value -> write(field, config, value))
                .build());
    }

    static void intSlider(ConfigCategory category, ConfigEntryBuilder eb, Object config, String name,
                          int min, int max) {
        Field field = field(config, name);
        category.addEntry(eb.startIntSlider(label(name), read(field, config, Integer.class), min, max)
                .setDefaultValue(read(field, defaultsFor(config), Integer.class))
                .setTooltip(tooltip(field))
                .setSaveConsumer(value -> write(field, config, value))
                .build());
    }

    static void floatField(ConfigCategory category, ConfigEntryBuilder eb, Object config, String name,
                           float min, float max) {
        Field field = field(config, name);
        category.addEntry(eb.startFloatField(label(name), read(field, config, Float.class))
                .setMin(min)
                .setMax(max)
                .setDefaultValue(read(field, defaultsFor(config), Float.class))
                .setTooltip(tooltip(field))
                .setSaveConsumer(value -> write(field, config, value))
                .build());
    }

    /**
     * Bounds are not cosmetic: they mirror the clamps the game applies when reading these two fields,
     * so the screen cannot offer a value that would be silently corrected at use time.
     */
    static void doubleField(ConfigCategory category, ConfigEntryBuilder eb, Object config, String name,
                            double min, double max) {
        Field field = field(config, name);
        category.addEntry(eb.startDoubleField(label(name), read(field, config, Double.class))
                .setMin(min)
                .setMax(max)
                .setDefaultValue(read(field, defaultsFor(config), Double.class))
                .setTooltip(tooltip(field))
                .setSaveConsumer(value -> write(field, config, value))
                .build());
    }

    static void str(ConfigCategory category, ConfigEntryBuilder eb, Object config, String name) {
        Field field = field(config, name);
        category.addEntry(eb.startStrField(label(name), read(field, config, String.class))
                .setDefaultValue(read(field, defaultsFor(config), String.class))
                .setTooltip(tooltip(field))
                .setSaveConsumer(value -> write(field, config, value))
                .build());
    }

    /** A coloured paragraph with no input, for caveats that apply to a whole category. */
    static void note(ConfigCategory category, ConfigEntryBuilder eb, String text, int argbColor) {
        category.addEntry(eb.startTextDescription(Component.literal(text))
                .setColor(argbColor)
                .build());
    }

    // ------------------------------------------------------------------
    // Reflection plumbing
    // ------------------------------------------------------------------

    private static Component label(String name) {
        return Component.literal(name);
    }

    /** The field's own {@code @ConfigComment}, one tooltip line per source line. */
    private static Component[] tooltip(Field field) {
        ConfigComment comment = field.getAnnotation(ConfigComment.class);
        if (comment == null) return new Component[0];

        return Arrays.stream(comment.value().split("\n"))
                .map(Component::literal)
                .toArray(Component[]::new);
    }

    private static Field field(Object config, String name) {
        try {
            Field field = config.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Config screen refers to a field that does not exist: "
                    + config.getClass().getSimpleName() + "." + name, e);
        }
    }

    private static Object defaultsFor(Object config) {
        return DEFAULTS.computeIfAbsent(config.getClass(), type -> {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Config class " + type.getSimpleName()
                        + " needs a no-argument constructor to supply default values", e);
            }
        });
    }

    private static <T> T read(Field field, Object config, Class<T> type) {
        try {
            return type.cast(field.get(config));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read " + field, e);
        }
    }

    private static void write(Field field, Object config, Object value) {
        try {
            field.set(config, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not write " + field, e);
        }
    }
}
