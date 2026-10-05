package io.meetscribe.stt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class WhisperCliEngineTest {
    @TempDir Path temp;
    @Test void parsesMillisecondOffsetsAndTrackLabels() throws Exception {
        var json = new ObjectMapper().readTree("{\"transcription\":[{\"offsets\":{\"from\":1250,\"to\":3200},\"text\":\" hello \"}]}");
        var segments = WhisperCliEngine.parse(json, new TranscriptionOptions("en", AudioSource.SYSTEM, "Others"));
        assertEquals(1.25, segments.getFirst().start()); assertEquals(3.2, segments.getFirst().end());
        assertEquals(AudioSource.SYSTEM, segments.getFirst().source()); assertEquals("hello", segments.getFirst().text());
    }
    @Test void rejectsMalformedOutput() throws Exception {
        var json = new ObjectMapper().readTree("{\"transcription\":[{\"text\":\"hello\"}]}");
        assertThrows(java.io.IOException.class, () -> WhisperCliEngine.parse(json, new TranscriptionOptions("en", AudioSource.MIC, "You")));
    }
    @Test void reportsRealChildFailureWithoutReturningFakeTranscript() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        // An intentionally failing executable is a process-boundary test double, never production STT.
        Path script = temp.resolve("failure.sh"); Files.writeString(script, "#!/bin/sh\nexit 7\n"); script.toFile().setExecutable(true);
        Path model = Files.writeString(temp.resolve("test-model"), "test fixture");
        try (var engine = new WhisperCliEngine(script, model, Duration.ofSeconds(2))) {
            var failure = assertThrows(java.io.IOException.class, () -> engine.transcribe(temp.resolve("audio.wav"), new TranscriptionOptions("en", AudioSource.MIC, "You")));
            assertTrue(failure.getMessage().contains("7"));
        }
    }
    @Test void timesOutChildProcess() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path script = temp.resolve("slow.sh"); Files.writeString(script, "#!/bin/sh\nexec sleep 10\n"); script.toFile().setExecutable(true);
        Path model = Files.writeString(temp.resolve("test-model"), "test fixture");
        try (var engine = new WhisperCliEngine(script, model, Duration.ofMillis(50))) {
            assertThrows(java.io.IOException.class, () -> engine.transcribe(temp.resolve("audio.wav"), new TranscriptionOptions("en", AudioSource.MIC, "You")));
        }
    }
}
