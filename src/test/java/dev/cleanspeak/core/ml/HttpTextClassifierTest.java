package dev.cleanspeak.core.ml;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.cleanspeak.api.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-contract tests for the optional local model sidecar. These use a real
 * loopback HTTP server, so they catch request/response and failure-handling
 * mistakes that a fake TextClassifier cannot.
 */
class HttpTextClassifierTest {

    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/classify";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private MlSettings settings(int timeoutMillis) {
        return new MlSettings(
                true, endpoint, false, "configured-v1", timeoutMillis,
                Map.of(), Map.of(), "RULES_ONLY"
        );
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> write(exchange, status, body));
    }

    private void respondWith(String path, Consumer<HttpExchange> consumer) {
        server.createContext(path, exchange -> consumer.accept(exchange));
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void writeUnchecked(HttpExchange exchange, int status, String body) {
        try {
            write(exchange, status, body);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private MlInput input() {
        return new MlInput(
                "you are a blorple", "paper:public-chat",
                "paper:public-chat:public", "2026.11.19.1", Map.of()
        );
    }

    @Test
    void parsesScoresAndModelVersionAndClampsValues() {
        respond("/classify", 200, """
                {"ok":true,
                 "scores":{"TARGETED_HARASSMENT":1.7,"THREAT":-2,
                           "hate-slur":0.4,"NOT_A_CATEGORY":0.9},
                 "confidence":0.91,
                 "model_version":"sidecar-v9"}
                """);

        MlResult result = new HttpTextClassifier(settings(2000)).classify(input());

        assertTrue(result.usable());
        assertEquals(MlResult.Status.OK, result.status());
        assertEquals(1.0D, result.score(Category.TARGETED_HARASSMENT));
        assertEquals(0.0D, result.score(Category.THREAT));
        assertEquals(0.4D, result.score(Category.HATE_SLUR));
        assertEquals(0.0D, result.score(Category.SPAM));
        assertEquals(0.91D, result.confidence());
        assertEquals("sidecar-v9", result.modelVersion());
    }

    @Test
    void okFalseAndNonJsonAreErrorsNotCrashes() {
        respond("/classify", 200, """
                {"ok":false,"error":"worker overloaded"}
                """);
        MlResult failed = new HttpTextClassifier(settings(2000)).classify(input());
        assertEquals(MlResult.Status.ERROR, failed.status());
        assertFalse(failed.usable());

        server.removeContext("/classify");
        respond("/classify", 200, "not json");
        MlResult invalid = new HttpTextClassifier(settings(2000)).classify(input());
        assertEquals(MlResult.Status.ERROR, invalid.status());
        assertTrue(invalid.detail().contains("invalid model JSON"));
    }

    @Test
    void non2xxResponsesAreFailuresAndOpenTheCircuitAfterThreeAttempts() {
        respond("/classify", 500, "{\"ok\":false}");
        HttpTextClassifier classifier = new HttpTextClassifier(settings(2000));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(MlResult.Status.ERROR, classifier.classify(input()).status());
        }

        assertFalse(classifier.available());
        assertTrue(classifier.status().startsWith("circuit open until"));
        // The circuit must short-circuit without another HTTP call.
        assertEquals(MlResult.Status.UNAVAILABLE, classifier.classify(input()).status());
    }

    @Test
    @Timeout(5)
    void slowServerProducesTimeoutResult() {
        respondWith("/classify", exchange -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            writeUnchecked(exchange, 200, "{\"ok\":true,\"scores\":{}}");
        });

        MlResult result = new HttpTextClassifier(settings(100)).classify(input());
        assertEquals(MlResult.Status.TIMEOUT, result.status());
        assertFalse(result.usable());
    }

    @Test
    void disabledOrRemoteEndpointNeverMakesARequest() throws IOException {
        HttpServer local = server;
        AtomicInteger calls = new AtomicInteger();
        local.createContext("/classify", exchange -> {
            calls.incrementAndGet();
            writeUnchecked(exchange, 200, "{\"ok\":true,\"scores\":{}}");
        });

        MlResult disabled = new HttpTextClassifier(MlSettings.disabled()).classify(input());
        assertEquals(MlResult.Status.DISABLED, disabled.status());

        MlSettings remote = new MlSettings(
                true, "http://203.0.113.5:8091/classify", false, "remote", 800,
                Map.of(), Map.of(), "RULES_ONLY"
        );
        MlResult rejected = new HttpTextClassifier(remote).classify(input());
        assertEquals(MlResult.Status.INSECURE_ENDPOINT, rejected.status());
        assertEquals(0, calls.get());
    }

    @Test
    void healthProbeUsesHealthPathAndRejectsNon2xx() {
        respondWith("/health", exchange -> writeUnchecked(exchange, 200, "{\"ok\":true}"));
        assertTrue(new HttpTextClassifier(settings(2000)).probeHealth());

        server.removeContext("/health");
        respondWith("/health", exchange -> writeUnchecked(exchange, 503, "{\"ok\":false}"));
        assertFalse(new HttpTextClassifier(settings(2000)).probeHealth());
    }
}
