package dev.cleanspeak.core.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cleanspeak.api.Category;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Built-in text model shipped with the plugin.
 *
 * <p>The artifact is a hashed linear model, not a Python pickled pipeline. It
 * stores only idf values and coefficients, so inference is pure Java, needs no
 * sidecar, and does not depend on a vocabulary file. Feature hashing must match
 * {@code tools/training/export_bundled_model.py} exactly; parity examples in the
 * artifact are asserted by {@code BundledTextClassifierTest}.</p>
 */
public final class BundledTextClassifier implements TextClassifier {

    public static final String RESOURCE = "/ml/bundled_model.json.gz";

    private static final long FNV_OFFSET_64 = 0xCBF29CE484222325L;
    private static final long FNV_PRIME_64 = 0x100000001B3L;
    private static final Pattern WORD = Pattern.compile("[a-z0-9_]+");

    private final String version;
    private final String loadError;
    private final int featureCount;
    private final int[] wordNgrams;
    private final int[] charNgrams;
    private final String[] classes;
    private final double[] idf;
    private final float[][] coefficients;
    private final double[] intercepts;
    private final int targetedIndex;
    private final int threatIndex;

    public BundledTextClassifier() {
        this(BundledTextClassifier.class.getResourceAsStream(RESOURCE));
    }

    BundledTextClassifier(InputStream stream) {
        String parsedVersion = "bundled-unavailable";
        String error = "";
        int count = 0;
        int[] words = {1, 2};
        int[] chars = {3, 4, 5};
        String[] parsedClasses = new String[0];
        double[] parsedIdf = new double[0];
        float[][] parsedCoefficients = new float[0][];
        double[] parsedIntercepts = new double[0];
        int targeted = -1;
        int threat = -1;
        try {
            if (stream == null) {
                throw new IOException("resource not found: " + RESOURCE);
            }
            try (InputStream input = stream;
                 InputStreamReader reader = new InputStreamReader(
                         new GZIPInputStream(input), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                parsedVersion = string(root, "version", parsedVersion);
                JsonObject features = root.getAsJsonObject("feature_spec");
                count = features.get("n_features").getAsInt();
                words = intArray(features.getAsJsonArray("word_ngrams"));
                chars = intArray(features.getAsJsonArray("char_ngrams"));
                parsedClasses = stringArray(root.getAsJsonArray("classes"));
                parsedIdf = doubleArray(root.getAsJsonArray("idf"));
                parsedIntercepts = doubleArray(root.getAsJsonArray("intercept"));
                JsonArray coefArray = root.getAsJsonArray("coef");
                parsedCoefficients = new float[coefArray.size()][];
                for (int row = 0; row < coefArray.size(); row++) {
                    parsedCoefficients[row] = floatArray(coefArray.get(row).getAsJsonArray());
                }
                if (parsedIdf.length != count) {
                    throw new IllegalArgumentException("idf length does not match feature count");
                }
                if (parsedCoefficients.length != parsedClasses.length
                        || parsedCoefficients.length == 0
                        || parsedCoefficients[0].length != count) {
                    throw new IllegalArgumentException("coefficient shape does not match classes/features");
                }
                if (parsedIntercepts.length != parsedClasses.length) {
                    throw new IllegalArgumentException("intercept length does not match classes");
                }
                for (int index = 0; index < parsedClasses.length; index++) {
                    if (parsedClasses[index].equals("TARGETED")) {
                        targeted = index;
                    } else if (parsedClasses[index].equals("THREAT")) {
                        threat = index;
                    }
                }
                if (targeted < 0 || threat < 0) {
                    throw new IllegalArgumentException("model must contain TARGETED and THREAT classes");
                }
            }
        } catch (Exception exception) {
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            count = 0;
            parsedClasses = new String[0];
            parsedIdf = new double[0];
            parsedCoefficients = new float[0][];
            parsedIntercepts = new double[0];
        }
        this.version = parsedVersion;
        this.loadError = error;
        this.featureCount = count;
        this.wordNgrams = words;
        this.charNgrams = chars;
        this.classes = parsedClasses;
        this.idf = parsedIdf;
        this.coefficients = parsedCoefficients;
        this.intercepts = parsedIntercepts;
        this.targetedIndex = targeted;
        this.threatIndex = threat;
    }

    private static String string(JsonObject root, String name, String fallback) {
        JsonElement element = root.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static int[] intArray(JsonArray array) {
        int[] values = new int[array.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = array.get(index).getAsInt();
        }
        return values;
    }

    private static double[] doubleArray(JsonArray array) {
        double[] values = new double[array.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = array.get(index).getAsDouble();
        }
        return values;
    }

    private static float[] floatArray(JsonArray array) {
        float[] values = new float[array.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = array.get(index).getAsFloat();
        }
        return values;
    }

    private static String[] stringArray(JsonArray array) {
        String[] values = new String[array.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = array.get(index).getAsString();
        }
        return values;
    }

    @Override
    public boolean enabled() {
        return loadError.isEmpty() && featureCount > 0;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public boolean available() {
        return enabled();
    }

    @Override
    public boolean inProcess() {
        return true;
    }

    @Override
    public String status() {
        if (enabled()) {
            return "bundled " + version + " (" + featureCount + " hashed features)";
        }
        return "bundled model unavailable: " + loadError;
    }

    @Override
    public MlResult classify(MlInput input) {
        if (!enabled()) {
            return MlResult.unavailable(status());
        }
        double[] logits = logitsFor(input.text());
        double[] probabilities = softmax(logits);
        EnumMap<Category, Double> scores = new EnumMap<>(Category.class);
        scores.put(Category.TARGETED_HARASSMENT, probabilities[targetedIndex]);
        scores.put(Category.THREAT, probabilities[threatIndex]);
        double confidence = 0.0D;
        for (double probability : probabilities) {
            confidence = Math.max(confidence, probability);
        }
        return new MlResult(MlResult.Status.OK, scores, confidence, version, "ok (bundled)");
    }

    private double[] logitsFor(String text) {
        Map<Integer, Double> buckets = new HashMap<>();
        featurize(text, buckets);
        double norm = 0.0D;
        Map<Integer, Double> values = new HashMap<>(buckets.size() * 2);
        for (Map.Entry<Integer, Double> entry : buckets.entrySet()) {
            double count = entry.getValue();
            if (count == 0.0D) {
                continue;
            }
            double magnitude = Math.abs(count);
            double value = Math.copySign(1.0D + Math.log(magnitude), count) * idf[entry.getKey()];
            values.put(entry.getKey(), value);
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0D) {
            norm = 1.0D;
        }
        double[] logits = new double[classes.length];
        for (int classIndex = 0; classIndex < classes.length; classIndex++) {
            double total = 0.0D;
            float[] weights = coefficients[classIndex];
            for (Map.Entry<Integer, Double> entry : values.entrySet()) {
                total += weights[entry.getKey()] * entry.getValue();
            }
            logits[classIndex] = total / norm + intercepts[classIndex];
        }
        return logits;
    }

    Map<Integer, Double> featureCountsForTest(String text) {
        Map<Integer, Double> buckets = new HashMap<>();
        featurize(text, buckets);
        return buckets;
    }

    double[] logitsForTest(String text) {
        return logitsFor(text);
    }

    double idfForTest(int bucket) {
        return idf[bucket];
    }

    float coefficientForTest(int classIndex, int bucket) {
        return coefficients[classIndex][bucket];
    }

    double interceptForTest(int classIndex) {
        return intercepts[classIndex];
    }

    private void featurize(String text, Map<Integer, Double> buckets) {
        String source = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        String normalized = String.join(" ", source.split("\\s+"));
        List<String> tokens = new ArrayList<>();
        Matcher matcher = WORD.matcher(normalized);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        for (int size : wordNgrams) {
            for (int index = 0; index + size <= tokens.size(); index++) {
                addFeature(buckets, "w:" + String.join(" ", tokens.subList(index, index + size)));
            }
        }
        for (int size : charNgrams) {
            for (int index = 0; index + size <= normalized.length(); index++) {
                addFeature(buckets, "c:" + normalized.substring(index, index + size));
            }
        }
    }

    private void addFeature(Map<Integer, Double> buckets, String feature) {
        long hashed = fnv1a64(feature);
        int bucket = (int) Math.floorMod(hashed, featureCount);
        double sign = hashed >= 0L ? 1.0D : -1.0D;
        buckets.merge(bucket, sign, Double::sum);
    }

    static long hashFeature(String feature) {
        return fnv1a64(feature);
    }

    static int bucketFor(long hashed, int featureCount) {
        return (int) Math.floorMod(hashed, featureCount);
    }

    private static long fnv1a64(String value) {
        long hashed = FNV_OFFSET_64;
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            hashed ^= (current & 0xFF);
            hashed *= FNV_PRIME_64;
        }
        return hashed;
    }

    private static double[] softmax(double[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (double value : logits) {
            max = Math.max(max, value);
        }
        double[] result = new double[logits.length];
        double total = 0.0D;
        for (int index = 0; index < logits.length; index++) {
            result[index] = Math.exp(logits[index] - max);
            total += result[index];
        }
        for (int index = 0; index < result.length; index++) {
            result[index] /= total;
        }
        return result;
    }
}
