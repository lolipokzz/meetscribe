package io.meetscribe.stt;

import java.util.Objects;

/** Language is a model-supported code or "auto"; speaker is a track label, not diarization. */
public record TranscriptionOptions(String language, AudioSource source, String speaker) {
    public TranscriptionOptions {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(speaker, "speaker");
        if (language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
    }
}
