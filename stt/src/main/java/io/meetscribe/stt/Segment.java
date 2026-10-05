package io.meetscribe.stt;

import java.util.Objects;

/** Times are seconds from the beginning of the recording, not wall-clock time. */
public record Segment(double start, double end, String speaker, AudioSource source, String text) {
    public Segment {
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end < start) {
            throw new IllegalArgumentException("Expected finite timestamps with 0 <= start <= end");
        }
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(text, "text");
    }
}
