package io.meetscribe.core;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.meetscribe.llm.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class MeetingServiceTest {
    @TempDir Path temp;
    @BeforeEach void canonicalTemp() throws IOException { temp = temp.toRealPath(); }
    private MeetingService service() { return new MeetingService(temp.resolve("meetings"), temp.resolve("missing-cli"), temp.resolve("missing-model"), "en"); }
    private static void wav(Path path, boolean sound) throws Exception {
        byte[] bytes = new byte[48000 * 4];
        for (int n = 24000; n < 48000; n++) {
            short value = sound ? (short)(10000 * Math.sin(n * 2 * Math.PI * 440 / 48000)) : 0;
            for (int c = 0; c < 2; c++) { bytes[n * 4 + c * 2] = (byte)value; bytes[n * 4 + c * 2 + 1] = (byte)(value >> 8); }
        }
        try (var audio = new AudioInputStream(new ByteArrayInputStream(bytes), new AudioFormat(48000,16,2,true,false), 48000)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
    @Test void resamplesStereoAndPreservesLeadingSilence() throws Exception {
        Path input = temp.resolve("input.wav"), output = temp.resolve("output.wav"); wav(input, true);
        var result = PcmAudio.normalize(input, output); assertEquals(16000, result.frames()); assertFalse(result.silent());
        try (var audio = AudioSystem.getAudioInputStream(output.toFile())) {
            assertEquals(16000, audio.getFormat().getSampleRate()); assertEquals(1, audio.getFormat().getChannels());
            byte[] data = audio.readAllBytes(); for (int i = 0; i < 16000; i++) assertEquals(0, data[i]);
            assertTrue(java.util.Arrays.stream(new int[]{data[20000], data[20001], data[20002]}).anyMatch(v -> v != 0));
        }
    }
    @Test void silentAudioPersistsEmptyTranscriptWithoutInvokingWhisper() throws Exception {
        var service = service(); Path dir = service.createMeeting(); wav(dir.resolve("mic.wav"), false); wav(dir.resolve("system.wav"), false);
        service.process(dir, s -> {}); assertEquals("TRANSCRIBED", service.listMeetings().getFirst().status());
        assertTrue(Files.readString(dir.resolve("transcript.json")).contains("[ ]"));
    }
    @Test void sttFailurePreservesOriginalAudioAndFailureStatus() throws Exception {
        var service = service(); Path dir = service.createMeeting(); wav(dir.resolve("mic.wav"), true); wav(dir.resolve("system.wav"), false);
        assertThrows(IOException.class, () -> service.process(dir, s -> {}));
        assertTrue(Files.exists(dir.resolve("mic.wav"))); assertEquals("FAILED", service.listMeetings().getFirst().status());
        assertFalse(Files.exists(dir.resolve("transcript.json")));
    }
    @Test void captureLifecyclePersistsWithoutSttConfiguration() throws Exception {
        var service = service(); Path dir = service.createMeeting(); service.markRecording(dir);
        assertEquals("RECORDING", service.listMeetings().getFirst().status());
        wav(dir.resolve("mic.wav"), true); wav(dir.resolve("system.wav"), false); service.markRecorded(dir);
        var meta = new com.fasterxml.jackson.databind.ObjectMapper().readTree(dir.resolve("meeting.json").toFile());
        assertEquals("RECORDED", meta.path("status").asText()); assertEquals(1, meta.path("schemaVersion").asInt());
        assertEquals("COMPLETED", meta.path("stages").path("capture").asText()); assertEquals(1.0, meta.path("durationSeconds").asDouble());
        service.markCaptureFailed(dir); assertEquals("FAILED", service.listMeetings().getFirst().status());
    }
    @Test void successfulRetranscriptionInvalidatesOldSummaryAndProvenance() throws Exception {
        var service = service(); Path dir = service.createMeeting(); wav(dir.resolve("mic.wav"), false); wav(dir.resolve("system.wav"), false);
        service.process(dir, s -> {});
        SummaryEngine engine = new SummaryEngine() {
            public int contextSize() { return 8192; } public int countTokens(String s) { return s.length(); }
            public String complete(String s, SummaryOptions o) { return "Old summary"; } public void close() {}
        };
        service.summarize(dir, engine, "test-provider", "test-model", s -> {});
        assertTrue(Files.exists(dir.resolve("summary.md"))); service.process(dir, s -> {});
        assertFalse(Files.exists(dir.resolve("summary.md")));
        var meta = new com.fasterxml.jackson.databind.ObjectMapper().readTree(dir.resolve("meeting.json").toFile());
        assertFalse(meta.has("summaryProvider")); assertFalse(meta.has("summaryModel"));
        assertEquals("NOT_STARTED", meta.path("stages").path("summary").asText());
        assertEquals("COMPLETED", meta.path("stages").path("transcription").asText());
        assertEquals("missing-model", meta.path("transcriptionModel").asText());
    }
    @Test void failedRegenerationPreservesPreviousSummaryAndItsProvenance() throws Exception {
        var service = service(); Path dir = service.createMeeting(); Files.writeString(dir.resolve("transcript.md"), "Transcript");
        class Engine implements SummaryEngine {
            private final boolean fail; Engine(boolean fail) { this.fail = fail; }
            public int contextSize() { return 8192; } public int countTokens(String text) { return text.length(); }
            public String complete(String prompt, SummaryOptions options) throws IOException {
                if (fail) throw new IOException("Fixture generation failure"); return "Original summary";
            }
            public void close() {}
        }
        service.summarize(dir, new Engine(false), "original-provider", "original-model", p -> {});
        assertThrows(IOException.class, () -> service.summarize(dir, new Engine(true), "new-provider", "new-model", p -> {}));
        assertEquals("Original summary\n", Files.readString(dir.resolve("summary.md")));
        var meta = new com.fasterxml.jackson.databind.ObjectMapper().readTree(dir.resolve("meeting.json").toFile());
        assertEquals("original-provider", meta.path("summaryProvider").asText());
        assertEquals("original-model", meta.path("summaryModel").asText());
        assertEquals("new-provider", meta.path("summaryAttemptProvider").asText());
        assertEquals("new-model", meta.path("summaryAttemptModel").asText());
        assertEquals("FAILED", meta.path("stages").path("summary").asText());
    }
    @Test void unicodeChunksReconstructLosslesslyWithinBudget() throws Exception {
        String text = "Обсудили 🚀 запуск\n负责人：李明。\n".repeat(35);
        SummaryEngine counter = new SummaryEngine() {
            public int contextSize() { return 2048; }
            public int countTokens(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
            public String complete(String prompt, SummaryOptions options) { throw new AssertionError("Counter only"); }
            public void close() {}
        };
        var chunks = MeetingService.chunks(text, counter, 95);
        assertTrue(chunks.size() > 1); assertEquals(text, String.join("", chunks));
        for (String chunk : chunks) {
            assertTrue(counter.countTokens(chunk) <= 95);
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
        }
    }
    @Test void multiChunkSummaryReducesAndSavesFinalResult() throws Exception {
        var service = service(); Path dir = service.createMeeting();
        Files.writeString(dir.resolve("transcript.md"), "Meeting decision with owner Alice and deadline Monday.\n".repeat(150));
        class ReducingEngine implements SummaryEngine {
            int calls; boolean reducedPartials; boolean closed;
            public int contextSize() { return 2048; }
            public int countTokens(String text) { return text.length(); }
            public String complete(String prompt, SummaryOptions options) {
                calls++;
                assertTrue(countTokens(prompt) + options.maxOutputTokens() <= contextSize());
                if (prompt.contains("PARTIAL_SUMMARY")) { reducedPartials = true; return "Final summary: Alice owns the Monday deadline."; }
                return "PARTIAL_SUMMARY: Alice owns the Monday deadline.";
            }
            public void close() { closed = true; }
        }
        var engine = new ReducingEngine(); service.summarize(dir, engine, s -> {});
        assertTrue(engine.calls > 2); assertTrue(engine.reducedPartials); assertTrue(engine.closed);
        assertEquals("Final summary: Alice owns the Monday deadline.\n", Files.readString(dir.resolve("summary.md")));
        assertEquals("COMPLETED", service.listMeetings().getFirst().status());
    }
    @Test void nonShrinkingMultiChunkSummaryFailsAndKeepsTranscript() throws Exception {
        var service = service(); Path dir = service.createMeeting(); String original = "Meeting content. ".repeat(400);
        Files.writeString(dir.resolve("transcript.md"), original);
        SummaryEngine engine = new SummaryEngine() {
            public int contextSize() { return 2048; } public int countTokens(String text) { return text.length(); }
            // Deliberately noncompliant test engine: a provider may ignore its requested output limit.
            public String complete(String prompt, SummaryOptions options) { return "Expanded content ".repeat(300); }
            public void close() {}
        };
        IOException failure = assertThrows(IOException.class, () -> service.summarize(dir, engine, s -> {}));
        assertTrue(failure.getMessage().contains("did not shrink"));
        assertEquals(original, Files.readString(dir.resolve("transcript.md")));
        assertFalse(Files.exists(dir.resolve("summary.md")));
    }
    @Test void autoSummaryRequestsMeetingLanguage() throws Exception {
        var service = new MeetingService(temp.resolve("meetings"), temp.resolve("cli"), temp.resolve("model"), "auto");
        Path dir = service.createMeeting(); Files.writeString(dir.resolve("transcript.md"), "Meeting transcript");
        SummaryEngine engine = new SummaryEngine() {
            public int contextSize() { return 8192; } public int countTokens(String s) { return s.length(); }
            public String complete(String prompt, SummaryOptions options) {
                assertTrue(prompt.contains("in the same language as the meeting")); assertFalse(prompt.contains("in auto")); return "Summary";
            }
            public void close() {}
        };
        service.summarize(dir, engine, s -> {});
    }
    @Test void cancellationPersistsStateAndKeepsAudio() throws Exception {
        var service = service(); Path dir = service.createMeeting(); wav(dir.resolve("mic.wav"), true); wav(dir.resolve("system.wav"), false);
        try {
            assertThrows(InterruptedException.class, () -> service.process(dir, message -> Thread.currentThread().interrupt()));
        } finally { Thread.interrupted(); }
        assertEquals("CANCELLED", service.listMeetings().getFirst().status()); assertTrue(Files.exists(dir.resolve("mic.wav")));
    }
    @Test void rejectsForeignDirectoryAndSymlinkTargets() throws Exception {
        var service = service(); Path dir = service.createMeeting();
        assertThrows(IOException.class, () -> service.deleteMeeting(temp, false));
        Path external = Files.writeString(temp.resolve("precious.wav"), "precious");
        try { Files.createSymbolicLink(dir.resolve("mic.wav"), external); }
        catch (UnsupportedOperationException | IOException e) { Assumptions.abort("Symlinks unsupported"); }
        assertThrows(IOException.class, () -> service.deleteMeeting(dir, true)); assertEquals("precious", Files.readString(external));
        service.deleteMeeting(dir, false); assertEquals("precious", Files.readString(external));
    }
    @Test void mergesTrackTimelinesUsingProcessBoundaryTestDouble() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        // Test-only CLI double emits valid whisper.cpp JSON; no production fallback uses this.
        Path script = temp.resolve("fixture-cli.sh");
        Files.writeString(script, "#!/bin/sh\nwhile [ \"$#\" -gt 0 ]; do if [ \"$1\" = \"-of\" ]; then shift; prefix=$1; fi; shift; done\nprintf '%s' '{\"transcription\":[{\"offsets\":{\"from\":100,\"to\":400},\"text\":\"Fixture speech\"}]}' > \"${prefix}.json\"\n");
        script.toFile().setExecutable(true);
        Path model = Files.writeString(temp.resolve("fixture-model"), "test only");
        var service = new MeetingService(temp.resolve("meetings"), script, model, "en");
        Path dir = service.createMeeting(); wav(dir.resolve("mic.wav"), true); wav(dir.resolve("system.wav"), true);
        service.process(dir, s -> {});
        var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(dir.resolve("transcript.json").toFile());
        assertEquals(2, rows.size()); assertEquals(0.3, rows.get(0).get("start").asDouble(), 0.0001);
        assertEquals("mic", rows.get(0).get("source").asText()); assertEquals("system", rows.get(1).get("source").asText());
    }
    @Test void failedSummaryClosesEngineAndPreservesTranscript() throws Exception {
        var service = service(); Path dir = service.createMeeting(); Files.writeString(dir.resolve("transcript.md"), "Original transcript");
        class FailingEngine implements SummaryEngine {
            boolean closed;
            public int contextSize() { return 8192; } public int countTokens(String s) { return s.length(); }
            public String complete(String s, SummaryOptions o) throws Exception { throw new IOException("Test failure"); }
            public void close() { closed = true; }
        }
        var engine = new FailingEngine(); assertThrows(IOException.class, () -> service.summarize(dir, engine, s -> {}));
        assertTrue(engine.closed); assertEquals("Original transcript", Files.readString(dir.resolve("transcript.md")));
        assertEquals("FAILED", service.listMeetings().getFirst().status());
    }
}
