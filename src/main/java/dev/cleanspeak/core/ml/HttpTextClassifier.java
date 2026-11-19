package dev.cleanspeak.core.ml;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cleanspeak.api.Category;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional HTTP classifier for a small local model sidecar. No model file is
 * downloaded by CleanSpeak. Non-loopback endpoints are rejected unless the
 * owner explicitly sets allow-remote-endpoint: true.
 *
 * <p>Expected request: JSON with text, channel, conversation, policy_version and
 * categories. Expected response: {"scores":{"THREAT":0.1,...},
 * "confidence":0.9,"model_version":"..."}. See docs/ML.md.</p>
 */
public final class HttpTextClassifier implements TextClassifier {

    private final MlSettings settings;
    private final HttpClient client;
    private final URI endpoint;
    private final URI healthEndpoint;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntil = new AtomicLong();
    private final AtomicLong lastSuccessMillis = new AtomicLong();
    private final AtomicLong lastAttemptMillis = new AtomicLong();
    private volatile String lastError = "";
    private volatile String lastModelVersion;

    public HttpTextClassifier(MlSettings settings) {
        this.settings = settings;
        this.endpoint = parseEndpoint(settings.endpoint());
        this.healthEndpoint = endpoint == null ? null : healthUri(endpoint);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(200, Math.min(5000, settings.endpoint().isBlank() ? 1000 : 2000))))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.lastModelVersion = settings.modelVersion();
    }

    private static URI parseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(endpoint);
            if (uri.getScheme() == null || uri.getHost() == null
                    || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
                return null;
            }
            return uri;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static URI healthUri(URI endpoint) {
        try {
            return new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), "/health", null, null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean enabled() {
        return settings.enabled() && endpoint != null;
    }

    @Override
    public String version() {
        return lastModelVersion == null ? settings.modelVersion() : lastModelVersion;
    }

    @Override
    public boolean available() {
        return enabled() && System.currentTimeMillis() >= circuitOpenUntil.get();
    }

    @Override
    public String status() {
        if (!settings.enabled()) {
            return "disabled";
        }
        if (endpoint == null) {
            return "misconfigured endpoint";
        }
        if (!endpointAllowed()) {
            return "endpoint rejected (non-local without explicit permission)";
        }
        if (!available()) {
            return "circuit open until " + circuitOpenUntil.get() + " (" + lastError + ")";
        }
        return consecutiveFailures.get() == 0 ? "available" : "degraded (" + lastError + ")";
    }

    @Override
    public MlResult classify(MlInput input) {
        if (!enabled()) {
            return MlResult.disabled();
        }
        if (!endpointAllowed()) {
            lastError = "remote endpoint not allowed";
            return new MlResult(MlResult.Status.INSECURE_ENDPOINT, Map.of(), 0.0D,
                    version(), lastError);
        }
        long now = System.currentTimeMillis();
        if (now < circuitOpenUntil.get()) {
            return MlResult.unavailable("circuit open: " + lastError);
        }
        lastAttemptMillis.set(now);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("text", input.text());
            body.addProperty("channel", input.channelId());
            body.addProperty("conversation", input.conversationId());
            body.addProperty("policy_version", input.policyVersion());
            JsonObject thresholds = new JsonObject();
            input.reviewThresholds().forEach((category, value) ->
                    thresholds.addProperty(category.name(), value));
            body.add("review_thresholds", thresholds);

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(settings.timeoutMillis()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            long started = System.nanoTime();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failure("HTTP " + response.statusCode());
            }
            if (elapsedMs > 5_000) {
                return failure("response exceeded soft latency budget: " + elapsedMs + "ms");
            }
            MlResult parsed = parseResponse(response.body());
            if (parsed.usable()) {
                consecutiveFailures.set(0);
                circuitOpenUntil.set(0);
                lastSuccessMillis.set(System.currentTimeMillis());
                lastModelVersion = parsed.modelVersion();
            } else {
                failure(parsed.detail());
            }
            return parsed;
        } catch (java.net.http.HttpTimeoutException e) {
            lastError = "model request timed out";
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= 3) {
                circuitOpenUntil.set(System.currentTimeMillis() + 60_000L);
            }
            return new MlResult(MlResult.Status.TIMEOUT, Map.of(), 0.0D, version(), lastError);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private MlResult failure(String detail) {
        lastError = detail == null ? "unknown model failure" : detail;
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= 3) {
            circuitOpenUntil.set(System.currentTimeMillis() + 60_000L);
        }
        return MlResult.error(lastError);
    }

    private MlResult parseResponse(String body) {
        try {
            JsonElement rootElement = JsonParser.parseString(body);
            if (!rootElement.isJsonObject()) {
                return MlResult.error("model response is not a JSON object");
            }
            JsonObject root = rootElement.getAsJsonObject();
            if (root.has("ok") && !root.get("ok").getAsBoolean()) {
                return MlResult.error(root.has("error") ? root.get("error").getAsString() : "model returned ok=false");
            }
            EnumMap<Category, Double> scores = new EnumMap<>(Category.class);
            if (root.has("scores") && root.get("scores").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("scores").entrySet()) {
                    Category category = category(entry.getKey());
                    if (category != null && entry.getValue().isJsonPrimitive()) {
                        try {
                            double score = entry.getValue().getAsDouble();
                            scores.put(category, Math.max(0.0D, Math.min(1.0D, score)));
                        } catch (RuntimeException ignored) {
                            // Ignore malformed individual scores; the model can be
                            // partially degraded without crashing chat.
                        }
                    }
                }
            }
            double confidence = 0.5D;
            if (root.has("confidence") && root.get("confidence").isJsonPrimitive()) {
                try {
                    confidence = root.get("confidence").getAsDouble();
                } catch (RuntimeException ignored) {
                    confidence = 0.5D;
                }
            }
            String model = root.has("model_version") && root.get("model_version").isJsonPrimitive()
                    ? root.get("model_version").getAsString()
                    : version();
            return new MlResult(MlResult.Status.OK, scores, confidence, model, "ok");
        } catch (RuntimeException e) {
            return MlResult.error("invalid model JSON: " + e.getMessage());
        }
    }

    private static Category category(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        try {
            return Category.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean endpointAllowed() {
        if (endpoint == null) {
            return false;
        }
        if (settings.allowRemoteEndpoint()) {
            return true;
        }
        String host = endpoint.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("localhost") || lower.equals("::1") || lower.equals("[::1]")) {
            return true;
        }
        return lower.startsWith("127.") || lower.startsWith("10.")
                || lower.startsWith("192.168.") || isPrivate172(lower);
    }

    private static boolean isPrivate172(String host) {
        if (!host.startsWith("172.")) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Optional low-cost health probe for staff status. Never call this from the
     * main thread.
     */
    public boolean probeHealth() {
        if (!enabled() || healthEndpoint == null || !endpointAllowed()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(healthEndpoint)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            if (ok) {
                lastSuccessMillis.set(System.currentTimeMillis());
            }
            return ok;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public long lastSuccessMillis() {
        return lastSuccessMillis.get();
    }

    public long lastAttemptMillis() {
        return lastAttemptMillis.get();
    }

    public String lastError() {
        return lastError;
    }
}
