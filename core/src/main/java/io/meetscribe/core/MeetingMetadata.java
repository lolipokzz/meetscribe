package io.meetscribe.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MeetingMetadata(UUID id, String title, Instant createdAt, MeetingState state) {
    public MeetingMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(state, "state");
    }
}
