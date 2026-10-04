package com.baranhan123.supersonicflight.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A human-readable note written above this option in the generated config file.
 *
 * <p>JSON has no comment syntax, so {@link CommentedJson} emits the text as a {@code //} line and
 * strips those lines again when reading. Keeping the note on the field means it cannot drift away
 * from the option it documents.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigComment {
    String value();
}
