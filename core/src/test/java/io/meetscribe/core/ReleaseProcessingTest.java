package io.meetscribe.core;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class ReleaseProcessingTest {
    @TempDir Path temp;
    @BeforeEach void canonical() throws Exception { temp = temp.toRealPath(); }
    private static void wav(Path path, int seconds, double from, double to, int amplitude) throws Exception {
        byte[] data = new byte[seconds * 32000];
        for (int i = 0; i < seconds * 16000; i++) {
            short sample = i >= from * 16000 && i < to * 16000 ? (short)(amplitude * Math.sin(i * 2 * Math.PI * 240 / 16000)) : 0;
            data[i * 2] = (byte)sample; data[i * 2 + 1] = (byte)(sample >> 8);
        }
        try (var stream = new AudioInputStream(new ByteArrayInputStream(data), new AudioFormat(16000, 16, 1, true, false), seconds * 16000L)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
    @Test void vadSkipsLongSilenceAndAddsWordPadding() throws Exception {
        Path wav = temp.resolve("speech.wav"); wav(wav, 10, 3, 4, 1000);
        var ranges = VoiceActivity.detect(wav); assertEquals(1, ranges.size());
        assertEquals(2.7, ranges.getFirst().startFrame() / 16000.0, .021);
        assertEquals(4.4, ranges.getFirst().endFrame() / 16000.0, .021);
        Path chunk = temp.resolve("chunk.wav"); VoiceActivity.extract(wav, chunk, ranges.getFirst());
        try (var stream = AudioSystem.getAudioInputStream(chunk.toFile())) {
            assertEquals(ranges.getFirst().endFrame() - ranges.getFirst().startFrame(), stream.getFrameLength());
        }
    }
    @Test void vadRejectsQuietNoiseAndBoundsContinuousActivity() throws Exception {
        Path wav = temp.resolve("sound.wav"); wav(wav, 4, 0, 4, 25); assertTrue(VoiceActivity.detect(wav).isEmpty());
        wav(wav, 125, 0, 125, 1200); var ranges = VoiceActivity.detect(wav);
        assertTrue(ranges.size() >= 3); assertEquals(0, ranges.getFirst().startFrame());
        assertEquals(125 * 16000L, ranges.getLast().endFrame());
        for (int i = 0; i < ranges.size(); i++) {
            assertTrue(ranges.get(i).endFrame() - ranges.get(i).startFrame() <= 60L * 16000);
            if (i > 0) assertEquals(ranges.get(i-1).endFrame(), ranges.get(i).startFrame());
        }
    }
    @Test void killedCaptureHeaderRecoversAllCompleteFrames() throws Exception {
        Path wav = temp.resolve("killed.wav"); wav(wav, 2, 1, 2, 1000);
        try (var file = new RandomAccessFile(wav.toFile(), "rw")) { file.seek(40); file.writeInt(0); file.seek(file.length()); file.writeByte(99); }
        assertTrue(PcmAudio.recoverCaptureHeader(wav)); assertFalse(PcmAudio.recoverCaptureHeader(wav));
        try (var stream = AudioSystem.getAudioInputStream(wav.toFile())) { assertEquals(32000, stream.getFrameLength()); }
        assertEquals(64044, Files.size(wav));
    }
    @Test void lockPreventsSecondInstanceMutatingMeeting() throws Exception {
        var first = new MeetingService(temp.resolve("meetings"), temp.resolve("missing-cli"), temp.resolve("missing-model"), "auto");
        var second = new MeetingService(temp.resolve("meetings"), temp.resolve("missing-cli"), temp.resolve("missing-model"), "auto");
        Path dir = first.createMeeting(); wav(dir.resolve("mic.wav"), 1, 0, 0, 0); wav(dir.resolve("system.wav"), 1, 0, 0, 0);
        first.process(dir, message -> assertThrows(IOException.class, () -> second.deleteMeeting(dir, false)));
        assertEquals(1, second.listMeetings().getFirst().durationSeconds());
    }
    @Test void resumeRetainsCommittedTranscriptWithoutOriginalAudio() throws Exception {
        var service = new MeetingService(temp.resolve("meetings"), temp.resolve("missing-cli"), temp.resolve("missing-model"), "auto"); Path dir = service.createMeeting();
        assertTrue(dir.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}_.*"));
        wav(dir.resolve("mic.wav"), 1, 0, 0, 0); wav(dir.resolve("system.wav"), 1, 0, 0, 0);
        service.process(dir, message -> {}); service.deleteMeeting(dir, true);
        service.resume(dir, message -> {}); assertEquals("TRANSCRIBED", service.listMeetings().getFirst().status());
        Files.writeString(dir.resolve("transcript.md"), "corrupted");
        assertThrows(IOException.class, () -> service.resume(dir, message -> {}));
    }
    @Test void editableTemplateUsesIndependentLanguageAndDeletesAudioOnlyAfterSuccess() throws Exception {
        Path template = Files.writeString(temp.resolve("prompt.txt"), "Summary in {{language}}: {{transcript}} END");
        var options = new ProcessingOptions("Маша", "fr", template, true, false);
        var service = new MeetingService(temp.resolve("meetings"), temp.resolve("missing-cli"), temp.resolve("missing-model"), "ru", options);
        Path dir = service.createMeeting(); Files.writeString(dir.resolve("transcript.md"), "Bonjour");
        wav(dir.resolve("mic.wav"), 1, 0, 0, 0); wav(dir.resolve("system.wav"), 1, 0, 0, 0);
        var engine = new io.meetscribe.llm.SummaryEngine() {
            public int contextSize() { return 8192; }
            public int countTokens(String text) { return text.length(); }
            public String complete(String prompt, io.meetscribe.llm.SummaryOptions options) {
                assertEquals("Summary in fr: Bonjour END", prompt); assertEquals("fr", options.language()); return "Résumé";
            }
            public void close() {}
        };
        service.summarize(dir, engine, progress -> {});
        assertFalse(Files.exists(dir.resolve("mic.wav"))); assertTrue(Files.exists(dir.resolve("transcript.md")));
    }
    @Test void vadChunkOffsetsAndDedupPreserveOriginalTimeline() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path script = Files.writeString(temp.resolve("fixture.sh"), """
                #!/bin/sh
                while [ "$#" -gt 0 ]; do if [ "$1" = "-of" ]; then shift; prefix=$1; fi; shift; done
                printf '%s' '{"transcription":[{"offsets":{"from":100,"to":500},"text":"Decision"}]}' > "${prefix}.json"
                """);
        assertTrue(script.toFile().setExecutable(true));
        Path model = Files.writeString(temp.resolve("model"), "fixture");
        var service = new MeetingService(temp.resolve("meetings"), script, model, "en",
                new ProcessingOptions("Alice", "auto", null, false, true));
        Path dir = service.createMeeting(); wav(dir.resolve("mic.wav"), 10, 3, 4, 1000); wav(dir.resolve("system.wav"), 10, 3, 4, 1000);
        service.process(dir, progress -> {});
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(dir.resolve("transcript.json").toFile());
        assertEquals(1, json.size()); assertEquals(2.8, json.get(0).get("start").asDouble(), .021);
        assertEquals("Alice", json.get(0).get("speaker").asText());
        assertEquals(10, service.listMeetings().getFirst().durationSeconds());
    }

    @Test void recordingReservesMeetingUntilCaptureStops() throws Exception {
        var first = new MeetingService(temp.resolve("meetings"), null, null, "auto");
        var second = new MeetingService(temp.resolve("meetings"), null, null, "auto");
        Path dir = first.createMeeting(); first.markRecording(dir);
        assertThrows(IOException.class, () -> second.deleteMeeting(dir, false));
        assertThrows(IOException.class, () -> first.deleteMeeting(dir, false));
        first.markCaptureFailed(dir); second.deleteMeeting(dir, false);
        assertFalse(Files.exists(dir));
    }

}
