package io.meetscribe.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Explicit provider selection. There is never an implicit cloud fallback. */
public final class SummaryEngines {
    private SummaryEngines() {}
    private static final int RESPONSE_LIMIT = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static SummaryEngine local(Path executable, Path model, int contextSize) {
        return new Local(executable, model, contextSize);
    }

    /** baseUrl is the API base (e.g. https://api.openai.com/v1), or the full completions URL. */
    public static SummaryEngine openAiCompatible(URI baseUrl, String model, String apiKey,
                                                 int contextSize, boolean localOnly, boolean consent) {
        if (localOnly) throw new IllegalStateException("HTTP summarization is disabled in local-only mode");
        if (!consent) throw new IllegalStateException("Explicit text transmission consent is required");
        return new Remote(baseUrl, model, apiKey, contextSize, Protocol.OPENAI);
    }

    public static SummaryEngine anthropic(URI baseUrl, String model, String apiKey,
                                           int contextSize, boolean localOnly, boolean consent) {
        requireTransmission(localOnly, consent);
        return new Remote(baseUrl, model, apiKey, contextSize, Protocol.ANTHROPIC);
    }

    public static SummaryEngine gemini(URI baseUrl, String model, String apiKey,
                                       int contextSize, boolean localOnly, boolean consent) {
        requireTransmission(localOnly, consent);
        return new Remote(baseUrl, model, apiKey, contextSize, Protocol.GEMINI);
    }

    private static void requireTransmission(boolean localOnly, boolean consent) {
        if (localOnly) throw new IllegalStateException("HTTP summarization is disabled in local-only mode");
        if (!consent) throw new IllegalStateException("Explicit text transmission consent is required");
    }

    private enum Protocol { OPENAI, ANTHROPIC, GEMINI }

    private abstract static class Engine implements SummaryEngine {
        final int context;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean busy = new AtomicBoolean();
        Engine(int context) {
            if (context < 256) throw new IllegalArgumentException("Context size must be at least 256");
            this.context = context;
        }
        public int contextSize() { return context; }
        /** Conservative byte-level estimate, not a provider tokenizer. Includes wrapper reserve. */
        public int countTokens(String text) {
            return Math.addExact(Objects.requireNonNull(text).getBytes(StandardCharsets.UTF_8).length, 128);
        }
        void begin(String prompt, SummaryOptions options) {
            Objects.requireNonNull(options);
            if (closed.get()) throw new IllegalStateException("Summary engine is closed");
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Summary prompt is empty");
            if ((long) countTokens(prompt) + options.maxOutputTokens() > context)
                throw new IllegalArgumentException("Prompt exceeds conservative model context budget; shorten or split the meeting");
            if (!busy.compareAndSet(false, true)) throw new IllegalStateException("Summary engine is busy");
        }
    }

    private static final class Local extends Engine {
        final Path executable, model;
        final AtomicReference<Process> active = new AtomicReference<>();
        Local(Path executable, Path model, int context) {
            super(context);
            this.executable = Objects.requireNonNull(executable).toAbsolutePath();
            this.model = Objects.requireNonNull(model).toAbsolutePath();
        }
        public String complete(String prompt, SummaryOptions options) throws Exception {
            begin(prompt, options);
            Path input = null;
            Process process = null;
            ExecutorService reader = null;
            try {
                if (!Files.isRegularFile(executable) || !Files.isRegularFile(model))
                    throw new IOException("Local model or executable is unavailable");
                input = Files.createTempFile("meetscribe-summary-", ".txt");
                Files.writeString(input, prompt, StandardCharsets.UTF_8);
                ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-m", model.toString(),
                    "-f", input.toString(), "-c", Integer.toString(context), "-n", Integer.toString(options.maxOutputTokens()),
                    "--conversation", "--single-turn", "--simple-io", "--no-display-prompt", "--no-escape", "--log-verbosity", "0", "--offline", "--color", "off");
                // Avoid inherited llama configuration enabling remote RPC/model fetching.
                builder.environment().keySet().removeIf(key -> key.startsWith("LLAMA_ARG_") || key.equals("HF_TOKEN"));
                process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
                active.set(process);
                if (closed.get()) throw new IOException("Summary engine is closed");
                process.getOutputStream().close();
                Process running = process;
                reader = Executors.newVirtualThreadPerTaskExecutor();
                Future<byte[]> output = reader.submit(() -> {
                    try (InputStream stream = running.getInputStream()) {
                        byte[] bytes = stream.readNBytes(RESPONSE_LIMIT + 1);
                        if (bytes.length > RESPONSE_LIMIT) {
                            running.destroyForcibly();
                            throw new IOException("Local summary exceeds output limit");
                        }
                        return bytes;
                    }
                });
                if (!process.waitFor(10, TimeUnit.MINUTES)) throw new IOException("Local summary timed out");
                if (process.exitValue() != 0) throw new IOException("Local summary failed; check executable and model compatibility");
                String result = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
                // llama-completion writes this framing marker only when EOS is reached.
                // --log-disable would also suppress generated text in current llama.cpp.
                String endMarker = "[end of text]";
                if (!result.endsWith(endMarker)) throw new IOException("Local summary is incomplete; increase output token limit");
                result = result.substring(0, result.length() - endMarker.length()).strip();
                if (result.isEmpty()) throw new IOException("Local model returned an empty summary");
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Summary cancelled");
            } catch (Exception e) {
                // Native stderr and nested exceptions may contain prompts, paths, or environment values.
                throw new IOException("Local summary failed, timed out or was truncated; use llama-completion v0.5.0 and check model/context/output limits");
            } finally {
                if (process != null) {
                    terminateProcess(process);
                    // Release Windows file handles before deleting the prompt file.
                    boolean interrupted = Thread.interrupted();
                    try { process.waitFor(3, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; }
                    finally { if (interrupted) Thread.currentThread().interrupt(); }
                    active.compareAndSet(process, null);
                }
                if (reader != null) reader.shutdownNow();
                if (input != null) { try { Files.deleteIfExists(input); } catch (IOException ignored) { input.toFile().deleteOnExit(); } }
                busy.set(false);
            }
        }
        public void close() {
            closed.set(true);
            Process process = active.getAndSet(null);
            if (process != null) terminateProcess(process);
        }
    }

    private static void terminateProcess(Process process) {
        // Process enumeration can be restricted even when terminating our own child is allowed.
        try { process.descendants().forEach(ProcessHandle::destroyForcibly); }
        catch (RuntimeException ignored) { }
        finally { process.destroyForcibly(); }
    }

    private static final class Remote extends Engine {
        final URI endpoint;
        final Protocol protocol;
        final String model, apiKey;
        final HttpClient client;
        final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
        Remote(URI base, String model, String apiKey, int context, Protocol protocol) {
            super(context);
            if (base == null || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
                throw new IllegalArgumentException("Expected a plain HTTP API base URL without credentials, query or fragment");
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(base.getHost().toLowerCase(Locale.ROOT));
            if (!("https".equals(base.getScheme()) || ("http".equals(base.getScheme()) && loopback)))
                throw new IllegalArgumentException("Remote API requires HTTPS; HTTP is allowed only for loopback");
            if (model == null || model.isBlank()) throw new IllegalArgumentException("Model name is required");
            this.protocol = protocol;
            this.model = model;
            this.apiKey = apiKey == null ? "" : apiKey;
            if (this.apiKey.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("API key contains invalid characters");
            String url = base.toString().replaceAll("/+$", "");
            this.endpoint = switch (protocol) {
                case OPENAI -> URI.create(url.endsWith("/chat/completions") ? url : url + "/chat/completions");
                case ANTHROPIC -> URI.create(url.endsWith("/messages") ? url : url + "/messages");
                case GEMINI -> {
                    String name = model.startsWith("models/") ? model.substring(7) : model;
                    if (!name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Invalid Gemini model ID");
                    yield URI.create(url + "/models/" + name + ":generateContent");
                }
            };
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        }
        public String complete(String prompt, SummaryOptions options) throws Exception {
            begin(prompt, options);
            CompletableFuture<HttpResponse<byte[]>> request = null;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                if (protocol == Protocol.GEMINI) {
                    body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))));
                    body.put("generationConfig", Map.of("maxOutputTokens", options.maxOutputTokens()));
                } else {
                    body.put("model", model);
                    body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
                    body.put("stream", false);
                    body.put(protocol == Protocol.OPENAI && endpoint.getHost().equalsIgnoreCase("api.openai.com")
                        ? "max_completion_tokens" : "max_tokens", options.maxOutputTokens());
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
                if (protocol == Protocol.ANTHROPIC) {
                    builder.header("anthropic-version", "2023-06-01");
                    if (!apiKey.isBlank()) builder.header("x-api-key", apiKey);
                } else if (protocol == Protocol.GEMINI) {
                    if (!apiKey.isBlank()) builder.header("x-goog-api-key", apiKey);
                } else if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
                if (closed.get()) throw new IOException("Summary engine is closed");
                request = client.sendAsync(builder.build(), info -> new LimitedBody());
                active.set(request);
                if (closed.get()) request.cancel(true);
                HttpResponse<byte[]> response = request.get(125, TimeUnit.SECONDS);
                if (response.statusCode() != 200) throw new IOException("Summary provider returned HTTP " + response.statusCode());
                JsonNode root = JSON.readTree(response.body());
                String result;
                if (protocol == Protocol.ANTHROPIC) {
                    if (!"end_turn".equals(root.path("stop_reason").asText()))
                        throw new IOException("Summary provider did not finish normally");
                    StringBuilder text = new StringBuilder();
                    for (JsonNode block : root.path("content")) {
                        if ("text".equals(block.path("type").asText()) && block.path("text").isTextual())
                            text.append(block.path("text").asText());
                    }
                    result = text.toString();
                } else if (protocol == Protocol.GEMINI) {
                    JsonNode candidate = root.path("candidates").path(0);
                    if (!"STOP".equals(candidate.path("finishReason").asText()))
                        throw new IOException("Summary provider did not finish normally");
                    StringBuilder text = new StringBuilder();
                    for (JsonNode part : candidate.path("content").path("parts")) {
                        if (!part.path("thought").asBoolean(false) && part.path("text").isTextual())
                            text.append(part.path("text").asText());
                    }
                    result = text.toString();
                } else {
                    JsonNode choice = root.path("choices").path(0);
                    if (!"stop".equals(choice.path("finish_reason").asText()))
                        throw new IOException("Summary provider did not complete normal text generation");
                    JsonNode content = choice.path("message").path("content");
                    result = content.isTextual() ? content.asText() : "";
                }
                if (result.isBlank()) throw new IOException("Summary provider returned no text");
                return result.strip();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Summary cancelled");
            } catch (Exception e) {
                // Do not retain provider bodies, request URLs, credentials or Jackson source excerpts.
                throw new IOException("Summary request failed, timed out or returned incomplete output; check provider settings and limits");
            } finally {
                if (request != null) { request.cancel(true); active.compareAndSet(request, null); }
                busy.set(false);
            }
        }
        public void close() {
            closed.set(true);
            CompletableFuture<?> request = active.getAndSet(null);
            if (request != null) request.cancel(true);
            client.shutdownNow();
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > RESPONSE_LIMIT) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Summary response is too large")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(new IOException("Summary response failed")); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
