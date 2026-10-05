# Meeting pipeline

`MeetingService` creates `yyyy-MM-dd_HH-mm-ss_UUID` directories; old UUID directories
remain readable. History includes `durationSeconds`. Both capture tracks are
normalized to 16 kHz PCM16 mono before transcription. Original files survive
cancellation and processing failures. The STT engine is closed before the caller
can construct an LLM engine; only transcript text is passed to the summary engine.

A frame-energy VAD detects activity using 20 ms RMS frames, a bounded percentile
noise floor, hysteresis, minimum 80 ms activity, 400 ms hangover, and 300/400 ms
padding. Activity ranges are merged and capped at 60 seconds, choosing the quietest
frame in the final ten seconds when a long range must be split. Whisper sees only
these ranges; timestamps are offset back to the original recording timeline.
This is **not neural VAD**: music or loud stationary noise can still trigger it,
and very quiet speech can be missed. Continuous speech can be split at a chunk
boundary. Real Russian WER and noisy-room evaluation remain release acceptance
work. Streaming area averaging is a basic resampler, not a sinc filter.

`ProcessingOptions` controls speaker label (default `Я`), summary language
(default `auto`, independently of STT language), an optional editable prompt,
optional audio deletion after successful summary, and conservative duplicate
removal. Duplicate removal requires equal text from different sources within one
second; it is not semantic deduplication or diarization. A custom UTF-8 prompt is
limited to 64 KiB and must contain exactly one `{{transcript}}`; `{{language}}` is
replaced with the chosen language. The built-in prompt requests overview,
decisions, action items and open questions but structure and factual correctness
remain dependent on the chosen model. Map/reduce is constrained by its context.

`resume(directory, progress)` explicitly reuses only a fingerprint-validated
transcript commit; otherwise it runs STT. It never initiates cloud summarization.
A new transcription first invalidates the old commit and summary. Per-file writes
are atomic when supported. SHA-256 over both transcript files is committed last,
so interrupted writes cannot be reused as a valid checkpoint. The canonical
44-byte PCM16 capture WAV header is repaired from complete frames on disk before
normalization; arbitrary WAV layouts are never guessed. Capture still must flush
bytes to disk: unwritten OS/application buffers cannot be recovered.

Each mutation holds an OS file lock outside the meeting directory, rejecting
other instances. Lock files are intentionally retained so all processes lock the
same inode. Symlink meeting roots/targets are rejected. `markRecording` retains the lock until the same service calls
`markRecorded` or `markCaptureFailed`, protecting active capture from deletion
in any app instance. OS locks are released when the application exits.

Failed summary regeneration preserves the previous summary and provenance.
Summary and metadata are separate atomic writes, not a multi-file transaction;
a crash between them may leave outdated provenance. Interrupting the processing
thread cancels and terminates the Whisper subprocess. Auto-deletion happens only
after the successful summary has been saved; errors preserve remaining audio.
