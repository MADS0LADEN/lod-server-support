package dev.vox.lss.config.menu;

import java.util.Objects;

/**
 * A renderer-neutral display string for a slider VALUE: either a translation key
 * (renderers turn it into a translatable component) or a literal (a formatted number).
 * MC-free on purpose — the catalog lives in xplat and its tests run without a
 * {@code Component} factory; each renderer owns the one-line conversion.
 *
 * <p>Exactly one of {@link #key()} / {@link #literal()} is non-null.
 */
public record Label(String key, String literal) {

    public Label {
        if ((key == null) == (literal == null)) {
            throw new IllegalArgumentException("a Label is exactly one of key|literal");
        }
    }

    public static Label key(String translationKey) {
        return new Label(Objects.requireNonNull(translationKey), null);
    }

    public static Label literal(String text) {
        return new Label(null, Objects.requireNonNull(text));
    }

    public static Label number(int value) {
        return literal(Integer.toString(value));
    }

    public boolean isKey() {
        return key != null;
    }
}
