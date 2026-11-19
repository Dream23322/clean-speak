package dev.cleanspeak.core.ml;

/** Chooses the text-model backend from configuration. */
public final class TextClassifierFactory {

    private TextClassifierFactory() {
    }

    public static TextClassifier create(boolean enabled, MlSettings settings) {
        if (!enabled) {
            return DisabledTextClassifier.INSTANCE;
        }
        if (settings.endpoint().isBlank()) {
            return new BundledTextClassifier();
        }
        return new HttpTextClassifier(settings);
    }
}
