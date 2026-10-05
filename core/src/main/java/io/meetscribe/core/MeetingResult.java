package io.meetscribe.core;

import io.meetscribe.stt.Segment;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Optional.empty() means summarization was not requested, never a swallowed error. */
public record MeetingResult(List<Segment> transcript, Optional<String> summary) {
    public MeetingResult {
        transcript = List.copyOf(transcript);
        Objects.requireNonNull(summary, "summary");
    }
}
