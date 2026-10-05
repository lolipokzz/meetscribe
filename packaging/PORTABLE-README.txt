MeetScribe 0.9.0-beta.1 — Windows 10/11 x64 private test build

1. Extract the entire ZIP to a writable local folder (do not launch inside ZIP).
2. Open MeetScribe.cmd. A system Java installation is not required.
3. Recording and transcription use the bundled capture helper, CPU Whisper and
   multilingual small-q5_1 model. The app does not download anything at runtime.
4. Local summary needs a separately supplied compatible GGUF instruction model;
   select it in Settings. The llama-completion CPU executable is included.
   Alternatively configure a supported provider and explicitly allow sending text.
   Local-only mode blocks every HTTP summary provider, including Ollama.
5. Existing settings in %APPDATA%\MeetScribe take precedence over bundle defaults.
   Review executable/model paths if upgrading from a development installation.

For diagnostics run start-debug.cmd --check from Command Prompt. This checks app
configuration only, not recording hardware or inference. Run start-debug.cmd
without arguments to see startup diagnostics in a console.

This is an unsigned beta. Windows runtime, real capture hardware, clean Windows
installation and long recording acceptance still require validation. No quality,
performance or hardware compatibility certification is implied by cross-compiling.
The archive includes no local LLM weights, GPU acceleration, or MSI installer.

Source project license selection is pending. This local build is not a public
release. Third-party license texts and hashes are supplied separately.
