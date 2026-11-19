package dev.cleanspeak.core.ml;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextClassifierFactoryTest {

    private MlSettings settings(String endpoint) {
        return new MlSettings(true, endpoint, false, "test", 800,
                Map.of(), Map.of(), "RULES_ONLY");
    }

    @Test
    void blankEndpointSelectsTheBundledModel() {
        TextClassifier classifier = TextClassifierFactory.create(true, settings(""));
        assertInstanceOf(BundledTextClassifier.class, classifier);
        assertTrue(classifier.enabled(), classifier.status());
    }

    @Test
    void configuredEndpointSelectsHttp() {
        TextClassifier classifier = TextClassifierFactory.create(
                true, settings("http://127.0.0.1:8091/classify"));
        assertInstanceOf(HttpTextClassifier.class, classifier);
        assertTrue(classifier.enabled());
    }

    @Test
    void disabledConfigSelectsTheNoopBackend() {
        TextClassifier classifier = TextClassifierFactory.create(false, settings(""));
        assertInstanceOf(DisabledTextClassifier.class, classifier);
        assertEquals(MlResult.Status.DISABLED, classifier.classify(
                new MlInput("hello", "chat", "chat:public", "test", Map.of())).status());
    }
}
