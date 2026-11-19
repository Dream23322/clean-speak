package dev.cleanspeak.core.ml;

public final class DisabledTextClassifier implements TextClassifier {

    public static final DisabledTextClassifier INSTANCE = new DisabledTextClassifier();

    private DisabledTextClassifier() {
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String version() {
        return "rules-only";
    }

    @Override
    public MlResult classify(MlInput input) {
        return MlResult.disabled();
    }

    @Override
    public String status() {
        return "disabled";
    }

    @Override
    public boolean available() {
        return false;
    }
}
