package io.meetscribe.core;

import java.nio.file.Path;
import java.util.Objects;

/** Two local tracks on a shared time origin. Format and synchronization are future work. */
public record RecordedAudio(Path microphone, Path system) {
    public RecordedAudio {
        Objects.requireNonNull(microphone, "microphone");
        Objects.requireNonNull(system, "system");
    }
}
