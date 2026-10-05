package io.meetscribe.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryTest {
    @Test void parsesOnlyFirstSlashAndPreservesLocalPaths() {
        assertEquals("C:\\models\\q.gguf", ProviderRegistry.parseModel("local/C:\\models\\q.gguf").model());
        assertEquals("/opt/models/q.gguf", ProviderRegistry.parseModel("local//opt/models/q.gguf").model());
        assertEquals("org/model", ProviderRegistry.parseModel("openrouter/org/model").model());
        assertThrows(IllegalArgumentException.class, () -> ProviderRegistry.parseModel("/model"));
        assertThrows(IllegalArgumentException.class, () -> ProviderRegistry.parseModel("local/"));
        assertFalse(new ProviderConfig("custom", null, "m", "secret-key", 4096, false, true).toString().contains("secret-key"));
    }

    @Test void nativeProtocolsUseCorrectHeadersBodiesAndFinishReasons() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String provider : new String[]{"anthropic", "google"}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicReference<String> requestBody = new AtomicReference<>();
            AtomicReference<String> key = new AtomicReference<>();
            AtomicReference<String> version = new AtomicReference<>();
            AtomicReference<String> query = new AtomicReference<>();
            AtomicReference<String> response = new AtomicReference<>(provider.equals("anthropic")
                ? "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"Done\"}]}"
                : "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hidden\"},{\"text\":\"Done\"}]}}]}");
            server.createContext(provider.equals("anthropic") ? "/v1/messages" : "/v1/models/test-model:generateContent", exchange -> {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                key.set(exchange.getRequestHeaders().getFirst(provider.equals("anthropic") ? "x-api-key" : "x-goog-api-key"));
                version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
                query.set(exchange.getRequestURI().getQuery());
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            try (SummaryEngine engine = ProviderRegistry.create(new ProviderConfig(provider, base, "test-model", "private-key", 4096, false, true))) {
                assertEquals("Done", engine.complete("non-sensitive text", new SummaryOptions("en", 128)));
                assertEquals("private-key", key.get());
                assertNull(query.get());
                var body = json.readTree(requestBody.get());
                if (provider.equals("anthropic")) {
                    assertEquals("2023-06-01", version.get());
                    assertEquals(128, body.path("max_tokens").asInt());
                    assertEquals("non-sensitive text", body.path("messages").path(0).path("content").asText());
                } else {
                    assertEquals(128, body.path("generationConfig").path("maxOutputTokens").asInt());
                    assertEquals("non-sensitive text", body.path("contents").path(0).path("parts").path(0).path("text").asText());
                }
                response.set(provider.equals("anthropic")
                    ? "{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}"
                    : "{\"candidates\":[{\"finishReason\":\"SAFETY\",\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}");
                Exception failure = assertThrows(Exception.class, () -> engine.complete("private-meeting", new SummaryOptions("en", 128)));
                assertNull(failure.getCause());
                assertFalse(failure.toString().contains("private-meeting"));
            } finally { server.stop(0); }
        }
    }

    @Test void policyRevocationBlocksNextRequestEvenForOllama() throws Exception {
        AtomicBoolean policy = new AtomicBoolean(false);
        ProviderConfig config = new ProviderConfig("ollama", URI.create("http://127.0.0.1:9/v1"), "m", "", 4096, false, true);
        try (SummaryEngine engine = ProviderRegistry.create(config, policy::get)) {
            policy.set(true);
            assertThrows(IllegalStateException.class, () -> ProviderRegistry.validateModel(engine));
        }
        assertThrows(IllegalStateException.class, () -> ProviderRegistry.create(config, policy::get));
        for (String provider : new String[]{"anthropic", "google", "openai", "ollama"}) {
            assertThrows(IllegalStateException.class, () -> ProviderRegistry.create(new ProviderConfig(provider, null, "m", "", 4096, true, true)));
            assertThrows(IllegalStateException.class, () -> ProviderRegistry.create(new ProviderConfig(provider, null, "m", "", 4096, false, false)));
        }
    }

    @Test void validationUsesTinyNeutralPromptAndDoesNotCloseEngine() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        SummaryEngine engine = new SummaryEngine() {
            public String complete(String prompt, SummaryOptions options) {
                assertEquals("Reply with the single word OK.", prompt);
                assertEquals(32, options.maxOutputTokens());
                return "OK";
            }
            public int contextSize() { return 4096; }
            public int countTokens(String text) { return text.length(); }
            public void close() { closed.set(true); }
        };
        ProviderRegistry.validateModel(engine);
        assertFalse(closed.get());
    }
}
