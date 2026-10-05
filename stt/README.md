# Local whisper.cpp adapter

`WhisperCliEngine` launches an explicitly configured local `whisper-cli` executable
with a local model and normalized WAV using `-m`, `-f`, `-l`, `-oj`, `-of`, `-np`.
It parses the official JSON `transcription[].offsets.from/to` millisecond fields.
Source: https://github.com/ggml-org/whisper.cpp/blob/master/examples/cli/cli.cpp
CLI reference: https://github.com/ggml-org/whisper.cpp/tree/master/examples/cli

The MVP deliberately uses an external process rather than JNI. This provides
crash isolation and releases model memory when the process exits before the LLM
is loaded. No model or binary is bundled or silently downloaded. Configure paths
to a compatible whisper.cpp build and GGML model. Each track invocation has a
four-hour timeout; interruption/close forcibly kills its child process. Console
output is discarded to avoid logging private transcript text. Errors report exit
codes and configuration guidance; no successful fallback transcript is fabricated.

Tests use explicitly marked process test doubles for exit/timeout handling and
JSON fixtures for parsing. They do not demonstrate real-model recognition quality.
