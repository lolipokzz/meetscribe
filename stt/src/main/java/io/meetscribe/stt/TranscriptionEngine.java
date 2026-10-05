package io.meetscribe.stt;

import java.nio.file.Path;
import java.util.List;

/** Local-only STT contract. A future adapter owns and releases native model memory. */
public interface TranscriptionEngine extends AutoCloseable {
    List<Segment> transcribe(Path audio, TranscriptionOptions options) throws Exception;

    @Override
    void close() throws Exception;
}
