package io.meetscribe.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SummaryEnginesTest {
    @TempDir Path temp;
    private static final SummaryOptions OPTIONS = new SummaryOptions("ru", 128);

    @Test void refusesHttpWithoutConsentOrInLocalOnlyMode() {
        URI uri = URI.create("http://127.0.0.1:9/v1");
        assertThrows(IllegalStateException.class, () -> SummaryEngines.openAiCompatible(uri, "m", "", 4096, true, true));
        assertThrows(IllegalStateException.class, () -> SummaryEngines.openAiCompatible(uri, "m", "", 4096, false, false));
        assertThrows(IllegalArgumentException.class, () -> SummaryEngines.openAiCompatible(URI.create("http://example.com/v1"), "m", "", 4096, false, true));
        assertThrows(IllegalArgumentException.class, () -> SummaryEngines.openAiCompatible(URI.create("https://key@example.com/v1"), "m", "", 4096, false, true));
    }

    @Test void sendsTextOnlyAndParsesCompatibleResponse() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] result = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Решение: выполнить задачу\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result); exchange.close();
        });
        server.start();
        try (SummaryEngine engine = remote(server, 4096)) {
            assertEquals("Решение: выполнить задачу", engine.complete("meeting text", OPTIONS));
            assertTrue(body.get().contains("meeting text"));
            assertTrue(body.get().contains("max_tokens"));
            assertEquals("Bearer secret-key", auth.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsOverflowBeforeRequestAndUsesConservativeUtf8Estimate() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> { calls.incrementAndGet(); exchange.close(); });
        server.start();
        try (SummaryEngine engine = remote(server, 512)) {
            assertTrue(engine.countTokens("Привет") >= "Привет".getBytes(StandardCharsets.UTF_8).length);
            assertThrows(IllegalArgumentException.class, () -> engine.complete("x".repeat(400), OPTIONS));
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }

    @Test void neverFollowsRedirectOrLeaksProviderError() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Location", "/leak?secret-key=private-meeting");
            byte[] bytes = "secret-key private-meeting".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(307, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/leak", exchange -> { redirected.incrementAndGet(); exchange.close(); });
        server.start();
        try (SummaryEngine engine = remote(server, 4096)) {
            Exception error = assertThrows(Exception.class, () -> engine.complete("private-meeting", OPTIONS));
            assertFalse(error.toString().contains("private-meeting"));
            assertFalse(error.toString().contains("secret-key"));
            assertNull(error.getCause());
            assertEquals(0, redirected.get());
        } finally { server.stop(0); }
    }

    @Test void boundsResponsesAndRejectsMalformedOrTruncatedOutput() throws Exception {
        AtomicReference<String> response = new AtomicReference<>("x".repeat(1_048_577));
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        server.start();
        try (SummaryEngine engine = remote(server, 4096)) {
            assertThrows(Exception.class, () -> engine.complete("text", OPTIONS));
            response.set("{invalid: private-meeting}");
            Exception error = assertThrows(Exception.class, () -> engine.complete("text", OPTIONS));
            assertNull(error.getCause());
            assertFalse(error.toString().contains("private-meeting"));
            response.set("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}");
            assertThrows(Exception.class, () -> engine.complete("text", OPTIONS));
        } finally { server.stop(0); }
    }

    @Test void closeCancelsPendingHttpRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            received.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try (SummaryEngine engine = remote(server, 4096); ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> task = executor.submit(() -> engine.complete("text", OPTIONS));
            assertTrue(received.await(3, TimeUnit.SECONDS));
            engine.close();
            assertThrows(ExecutionException.class, () -> task.get(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> engine.complete("text", OPTIONS));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test @DisabledOnOs(OS.WINDOWS) void localCliReceivesPromptAsFileAndCleansUp() throws Exception {
        Path model = Files.writeString(temp.resolve("model.gguf"), "fake test model");
        Path executable = Files.writeString(temp.resolve("llama-fake"), """
            #!/bin/sh
            while [ "$#" -gt 0 ]; do
              if [ "$1" = "-f" ]; then shift; input="$1"; fi
              shift
            done
            printf '%s\\n' "$input"
            cat "$input"
            printf ' [end of text]\\n'
            """);
        assertTrue(executable.toFile().setExecutable(true));
        try (SummaryEngine engine = SummaryEngines.local(executable, model, 4096)) {
            String result = engine.complete("literal $(touch evil) \\n текст", OPTIONS);
            int newline = result.indexOf('\n');
            assertTrue(newline > 0);
            assertFalse(Files.exists(Path.of(result.substring(0, newline))));
            assertEquals("literal $(touch evil) \\n текст", result.substring(newline + 1));
        }
    }

    @Test @DisabledOnOs(OS.WINDOWS) void localRejectsOutputWithoutEndOfSequenceMarker() throws Exception {
        Path model = Files.writeString(temp.resolve("model-truncated.gguf"), "fake test model");
        Path executable = Files.writeString(temp.resolve("llama-truncated"), "#!/bin/sh\nprintf 'incomplete summary'\n");
        assertTrue(executable.toFile().setExecutable(true));
        try (SummaryEngine engine = SummaryEngines.local(executable, model, 4096)) {
            assertThrows(java.io.IOException.class, () -> engine.complete("text", OPTIONS));
        }
    }

    @Test @DisabledOnOs(OS.WINDOWS) void closeTerminatesLocalProcess() throws Exception {
        Path model = Files.writeString(temp.resolve("model-close.gguf"), "fake test model");
        Path marker = temp.resolve("started");
        Path executable = Files.writeString(temp.resolve("llama-blocking"), "#!/bin/sh\ntouch '" + marker + "'\nsleep 30\n");
        assertTrue(executable.toFile().setExecutable(true));
        try (SummaryEngine engine = SummaryEngines.local(executable, model, 4096);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> task = executor.submit(() -> engine.complete("private meeting", OPTIONS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(marker));
            engine.close();
            assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
        }
    }

    private HttpServer server() throws Exception { return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
    private SummaryEngine remote(HttpServer server, int context) {
        return SummaryEngines.openAiCompatible(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), "test-model", "secret-key", context, false, true);
    }
}
