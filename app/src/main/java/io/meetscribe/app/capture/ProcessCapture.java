package io.meetscribe.app.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** Isolated native recorder. No audio data is read by this transport. */
public final class ProcessCapture implements AutoCloseable {
    private static final String FAILURE = "Ошибка аудиозахвата или протокола. Проверьте устройства и свободное место; сохранённые дорожки доступны в истории.";
    private final Process process;
    private final long timeoutMillis;
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private volatile boolean stoppedEvent;
    private volatile boolean requestedStop;
    private volatile Consumer<String> information = s -> {};
    private final CompletableFuture<Void> recordingLimit = new CompletableFuture<>();
    public void onLimitReached(Runnable listener){
        Objects.requireNonNull(listener);
        // Replay an early limit event if the native process reached its limit before UI listener attachment.
        recordingLimit.thenRunAsync(listener);
    }
    public void onInformation(Consumer<String> listener) {information=Objects.requireNonNull(listener);}
    public ProcessCapture(Path executable, Path directory, String microphone, String outputDevice,
            BiConsumer<Double, Double> levels, Consumer<String> failure) throws Exception {
        this(executable, directory, microphone, outputDevice, levels, failure, Duration.ofSeconds(20));
    }
    // Short timeouts are available to protocol tests without modifying production limits.
    ProcessCapture(Path executable, Path directory, String microphone, String outputDevice,
            BiConsumer<Double, Double> levels, Consumer<String> failure, Duration timeout) throws Exception {
        Objects.requireNonNull(levels); Objects.requireNonNull(failure);
        timeoutMillis = timeout.toMillis();
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Invalid capture timeout");
        if (!Files.isRegularFile(executable)) throw new IOException("Не найден meetscribe-capture.exe. Укажите путь в настройках.");
        try {
            process = new ProcessBuilder(executable.toAbsolutePath().toString(), "--directory", directory.toAbsolutePath().toString(),
                    "--microphone", microphone, "--output-device", outputDevice).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception e) { throw new IOException("Не удалось запустить capture. Проверьте путь к исполняемому файлу."); }
        Thread.ofVirtual().name("capture-events").start(() -> {
            try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
                ObjectMapper json = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS); String line; boolean ready = false;
                while ((line = boundedLine(reader)) != null) {
                    JsonNode event = json.readTree(line);
                    if (event == null || !event.isObject() || !event.path("protocolVersion").isIntegralNumber()
                            || !event.path("protocolVersion").canConvertToInt() || event.path("protocolVersion").intValue() != 1)
                        throw new IOException();
                    switch (event.path("event").asText()) {
                        case "ready" -> {
                            if (ready || started.isDone() || stoppedEvent) throw new IOException();
                            ready = true;
                        }
                        case "started" -> {
                            if (!ready || started.isDone() || stoppedEvent) throw new IOException();
                            started.complete(null);
                        }
                        case "levels" -> {
                            if (!started.isDone() || stoppedEvent) throw new IOException();
                            double mic = level(event, "mic"), system = level(event, "system");
                            levels.accept(mic, system);
                        }
                        case "stopped" -> {
                            if (!started.isDone() || !requestedStop || stoppedEvent) throw new IOException();
                            stoppedEvent = true;
                        }
                        case "limitReached" -> {
                            if(!started.isDone()||stoppedEvent||recordingLimit.isDone()
                                    ||!event.path("seconds").isIntegralNumber()||event.path("seconds").asLong()!=14400)throw new IOException();
                            requestedStop=true;
                            recordingLimit.complete(null);
                        }
                        case "deviceChanged" -> {
                            if(!started.isDone()||stoppedEvent||!java.util.Set.of("mic","system").contains(event.path("track").asText())
                                    ||!java.util.Set.of("reconnecting","connected").contains(event.path("status").asText())) throw new IOException();
                            information.accept(event.path("status").asText().equals("connected")?
                                    "Аудиоустройство подключено. Запись продолжается.":"Переподключение аудиоустройства… пауза заполняется тишиной.");
                        }
                        case "error" -> {
                            if (!event.path("fatal").isBoolean() || event.path("fatal").booleanValue()) throw new IOException();
                        }
                        default -> throw new IOException();
                    }
                }
                int exit = process.waitFor();
                if (exit != 0 || !stoppedEvent || !requestedStop) throw new IOException();
                ended.complete(null);
            } catch (Exception e) {
                IOException safe = new IOException(FAILURE);
                started.completeExceptionally(safe); ended.completeExceptionally(safe);
                terminate();
                try { failure.accept(FAILURE); } catch (RuntimeException ignored) { }
            }
        });
        try { started.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (Exception e) {
            terminate();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Не удалось начать запись. Проверьте capture.exe и аудиоустройства.");
        }
    }
    private static double level(JsonNode event, String key) throws IOException {
        JsonNode n = event.path(key);
        if (!n.isNumber() || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 1)
            throw new IOException();
        return n.doubleValue();
    }
    private static String boundedLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch, length = 0;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') return line.toString();
            if (++length > 65536) throw new IOException();
            if (ch != '\r') line.append((char) ch);
        }
        return line.isEmpty() ? null : line.toString();
    }
    private synchronized void requestStop() throws IOException {
        if (requestedStop) return;
        requestedStop = true;
        if (process.isAlive()) {
            Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            writer.write("{\"protocolVersion\":1,\"command\":\"stop\"}\n"); writer.flush();
        }
    }
    public boolean isRunning() { return process.isAlive() && !ended.isDone(); }
    public void stop() throws Exception {
        try {
            requestStop();
            ended.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            terminate();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Запись не финализирована. Обработайте сохранённые дорожки из истории.");
        }
    }
    private void terminate() {
        try { process.descendants().forEach(ProcessHandle::destroyForcibly); }
        catch (RuntimeException ignored) { }
        finally { process.destroyForcibly(); }
    }
    @Override public void close() {
        try { stop(); } catch (Exception e) { terminate(); }
    }
}
