package io.meetscribe.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs a locally installed whisper.cpp CLI, releasing model memory when each process exits. */
public final class WhisperCliEngine implements TranscriptionEngine {
    private final Path executable, model;
    private final Duration timeout;
    private volatile Process active;
    private volatile boolean closed;
    public WhisperCliEngine(Path executable, Path model) { this(executable, model, Duration.ofHours(4)); }
    public WhisperCliEngine(Path executable, Path model, Duration timeout) {
        this.executable = Objects.requireNonNull(executable).toAbsolutePath();
        this.model = Objects.requireNonNull(model).toAbsolutePath();
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
    }
    @Override public List<Segment> transcribe(Path audio, TranscriptionOptions options) throws Exception {
        if (closed) throw new IllegalStateException("Engine is closed");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!Files.isRegularFile(executable) || !Files.isRegularFile(model))
            throw new IOException("Configure existing whisper-cli executable and Whisper model files");
        Path temp = Files.createTempDirectory("meetscribe-whisper-");
        try {
            Path prefix = temp.resolve("result");
            Process process = new ProcessBuilder(executable.toString(), "-m", model.toString(), "-f",
                    audio.toAbsolutePath().toString(), "-l", options.language(), "-oj", "-of", prefix.toString(), "-np")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            active = process;
            if (closed) process.destroyForcibly();
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Whisper timed out after " + timeout);
                if (process.exitValue() != 0) throw new IOException("Whisper exited with code " + process.exitValue() + "; check model, executable and audio compatibility");
                Path json = Path.of(prefix + ".json");
                if (!Files.isRegularFile(json) || Files.size(json) > 64L * 1024 * 1024) throw new IOException("Whisper JSON missing or too large");
                return parse(new ObjectMapper().readTree(json.toFile()), options);
            } finally { terminate(process); active = null; }
        } finally {
            try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    static List<Segment> parse(JsonNode root, TranscriptionOptions options) throws IOException {
        JsonNode rows = root.get("transcription");
        if (rows == null || !rows.isArray()) throw new IOException("Invalid whisper.cpp JSON: transcription array required");
        List<Segment> result = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode offsets = row.path("offsets");
            if (!offsets.path("from").isNumber() || !offsets.path("to").isNumber() || !row.path("text").isTextual())
                throw new IOException("Invalid whisper.cpp segment fields");
            String text = row.get("text").asText().strip();
            if (!text.isEmpty()) result.add(new Segment(offsets.get("from").asDouble() / 1000.0,
                    offsets.get("to").asDouble() / 1000.0, options.speaker(), options.source(), text));
        }
        return List.copyOf(result);
    }
    @Override public void close() { closed = true; Process process = active; if (process != null) terminate(process); }
    private static void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { interrupted = true; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
