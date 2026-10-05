package io.meetscribe.core;

import java.nio.file.Path;

/** Boundary to the future Windows capture helper. No helper is bundled yet. */
public interface CaptureClient extends AutoCloseable {
    void start(Path recordingDirectory) throws Exception;

    RecordedAudio stop() throws Exception;

    @Override
    void close() throws Exception;
}
