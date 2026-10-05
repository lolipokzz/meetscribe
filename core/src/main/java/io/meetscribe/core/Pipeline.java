package io.meetscribe.core;

import io.meetscribe.llm.SummaryOptions;
import io.meetscribe.stt.TranscriptionOptions;
import java.util.Optional;

/**
 * Contract only: no recording, transcription or summarization implementation exists.
 * Future implementations must finish and close STT before constructing/loading an LLM.
 * Engine creation must be lazy; injecting two already-loaded engines violates this contract.
 * Capture and STT keep audio local. Only text may cross a future remote LLM boundary.
 */
public interface Pipeline {
    MeetingResult process(
            RecordedAudio audio,
            TranscriptionOptions microphoneOptions,
            TranscriptionOptions systemOptions,
            Optional<SummaryOptions> summaryOptions) throws Exception;
}
