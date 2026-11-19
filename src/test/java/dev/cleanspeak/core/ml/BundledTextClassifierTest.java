package dev.cleanspeak.core.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cleanspeak.api.Category;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the bundled Java inference matches the Python exporter. */
class BundledTextClassifierTest {

    private static MlInput input(String text) {
        return new MlInput(text, "paper:public-chat", "paper:public-chat:public",
                "test", Map.of());
    }

    @Test
    void modelLoadsFromTheShippedResource() {
        BundledTextClassifier classifier = new BundledTextClassifier();
        assertTrue(classifier.enabled(), classifier.status());
        assertTrue(classifier.available());
        assertTrue(classifier.version().startsWith("cleanspeak-bundled"));
        assertTrue(classifier.status().startsWith("bundled "));
    }

    @Test
    void scoresNeutralVentingLowAndTargetedThreatHigh() {
        BundledTextClassifier classifier = new BundledTextClassifier();
        MlResult neutral = classifier.classify(input("I hate mining, it takes forever"));
        MlResult targeted = classifier.classify(
                input("people like you should be removed from this server"));
        MlResult threat = classifier.classify(input("I will find you and hurt you"));

        assertTrue(neutral.usable());
        assertTrue(neutral.score(Category.TARGETED_HARASSMENT) < 0.25D,
                "neutral targeted=" + neutral.score(Category.TARGETED_HARASSMENT));
        assertTrue(neutral.score(Category.THREAT) < 0.10D);
        assertTrue(targeted.score(Category.TARGETED_HARASSMENT) > 0.75D,
                "targeted=" + targeted.score(Category.TARGETED_HARASSMENT));
        assertTrue(threat.score(Category.THREAT) > 0.75D,
                "threat=" + threat.score(Category.THREAT));
        assertTrue(neutral.confidence() >= 0.0D && neutral.confidence() <= 1.0D);
    }

    @Test
    void javaScoresMatchExportedPythonParityExamples() throws Exception {
        JsonObject root;
        try (InputStream resource = BundledTextClassifier.class.getResourceAsStream(BundledTextClassifier.RESOURCE)) {
            assertNotNull(resource);
            try (InputStreamReader reader = new InputStreamReader(
                    new GZIPInputStream(resource), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
        JsonArray examples = root.getAsJsonArray("examples");
        BundledTextClassifier classifier = new BundledTextClassifier();
        for (int index = 0; index < examples.size(); index++) {
            JsonObject example = examples.get(index).getAsJsonObject();
            String text = example.get("text").getAsString();
            JsonObject scores = example.getAsJsonObject("scores");
            MlResult result = classifier.classify(input(text));
            assertEquals(scores.get("TARGETED_HARASSMENT").getAsDouble(),
                    result.score(Category.TARGETED_HARASSMENT), 0.002D,
                    "targeted parity failed for: " + text);
            assertEquals(scores.get("THREAT").getAsDouble(),
                    result.score(Category.THREAT), 0.002D,
                    "threat parity failed for: " + text);
        }
    }

    @Test
    void hashMatchesThePythonReference() {
        assertEquals(6873300181502602167L, BundledTextClassifier.hashFeature("w:i"));
        assertEquals(131071, BundledTextClassifier.bucketFor(-1L, 131072));
        assertEquals(76727, BundledTextClassifier.bucketFor(
                BundledTextClassifier.hashFeature("w:i"), 131072));
    }

    @Test
    void featureCountsMatchThePythonReference() {
        Map<Integer, Double> expected = Map.ofEntries(
                Map.entry(9766, -1.0), Map.entry(28357, -1.0),
                Map.entry(29219, -1.0), Map.entry(36776, 1.0),
                Map.entry(48437, -1.0), Map.entry(73046, -1.0),
                Map.entry(76727, 1.0), Map.entry(107029, -1.0),
                Map.entry(110320, 1.0), Map.entry(116316, -1.0),
                Map.entry(123347, 1.0), Map.entry(129504, -1.0));
        assertEquals(expected, new BundledTextClassifier().featureCountsForTest("i hate"));
    }

    @Test
    void scoreForKnownShortTextMatchesPythonReference() {
        BundledTextClassifier classifier = new BundledTextClassifier();
        assertEquals(2.840849D, classifier.idfForTest(76727), 0.0001D);
        assertEquals(0.178993D, classifier.coefficientForTest(0, 76727), 0.0001D);
        assertEquals(1.240447D, classifier.interceptForTest(0), 0.0001D);
        double[] singleLogits = classifier.logitsForTest("i");
        assertEquals(1.41944D, singleLogits[0], 0.002D);
        assertEquals(-2.587516D, singleLogits[1], 0.002D);
        assertEquals(1.168074D, singleLogits[2], 0.002D);
        MlResult single = classifier.classify(input("i"));
        assertEquals(0.010128D, single.score(Category.TARGETED_HARASSMENT), 0.002D);
        MlResult result = classifier.classify(input("i hate"));
        assertEquals(0.391079D, result.score(Category.TARGETED_HARASSMENT), 0.002D);
    }

    @Test
    void missingResourceIsUnavailableInsteadOfThrowing() {
        BundledTextClassifier classifier = new BundledTextClassifier(null);
        assertFalse(classifier.enabled());
        assertFalse(classifier.available());
        assertTrue(classifier.status().contains("unavailable"));
        assertEquals(MlResult.Status.UNAVAILABLE, classifier.classify(input("anything")).status());
    }
}
