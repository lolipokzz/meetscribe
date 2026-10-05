# LLM adapters

`SummaryEngines.local(executable, model, contextSize)` executes a user-installed
llama.cpp **llama-completion v0.5.0** with a local GGUF model.
The new `llama-cli` in v0.5.0 is a different interactive frontend and is not supported. It never downloads a model, uses no shell,
and has no cloud fallback. Input is a temporary UTF-8 file removed after the
process exits. Standard error is discarded to keep transcript content out of errors.
Closing the engine or interrupting its worker terminates the process. Runtime is
limited to ten minutes and captured output to 1 MiB. STT must be closed first.

`SummaryEngines.openAiCompatible(baseUrl, model, apiKey, contextSize, localOnly, consent)`
uses `/chat/completions` (appended to the base URL, usually ending in `/v1`). It
supports OpenAI, Ollama's compatibility endpoint and compatible custom services.
The caller must supply explicit consent and disable local-only mode **even for
loopback HTTP**. Non-loopback endpoints require HTTPS. Redirects and automatic
retries/fallbacks are disabled. Only the supplied text prompt is transmitted.
The API key is supplied by the caller and is never written by this module. Responses
are limited to 1 MiB and requests to approximately two minutes. Provider bodies,
URLs, parser excerpts and nested errors are omitted from reported failures.

`countTokens` is a conservative UTF-8 byte count plus 128 wrapper tokens, **not an
exact tokenizer**. Input plus requested output must fit the configured context.
Each oversized call fails before inference/network; chunking must be performed by the caller.
Set context to the model's actual capacity. Unusual provider chat templates can need
more overhead. Explicit HTTP finish reasons other than `stop` are rejected as incomplete.
The batch CLI EOS framing marker `[end of text]` is required and removed from
the result. Output without this marker is rejected as incomplete, including token-cap
truncation. `--log-verbosity 0` retains generated text without diagnostics; do not
replace it with `--log-disable`, which also suppresses generated text in v0.5.0.

Protocol/flags were checked against official documentation:
- [llama.cpp completion at the verified commit](https://github.com/ggml-org/llama.cpp/blob/7fe450e19305b828c199d602c23a8337aaa1f03b/tools/completion/README.md):
  `-m`, `-f`, `-c`, `-n`, `--conversation`, `--single-turn`, `--simple-io`,
  `--no-display-prompt`, `--no-escape`, `--log-verbosity 0`, `--offline`, `--color off`.
- [OpenAI Chat](https://developers.openai.com/api/reference/resources/chat):
  `messages`, `model`, non-streaming responses; `max_completion_tokens` for api.openai.com.
- [Ollama compatibility](https://docs.ollama.com/api/openai-compatibility):
  `max_tokens` for compatible providers; this is not Ollama's native `/api` endpoint.

Tests exercise a loopback HTTP server and a fake local executable. A separate real
CPU smoke test on macOS arm64 successfully called `SummaryEngines.local` using
llama.cpp v0.5.0, commit `7fe450e19305b828c199d602c23a8337aaa1f03b`, and
[Unsloth's SmolLM2-135M-Instruct Q8_0](https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF),
revision `9e6855bc4be717fca1ef21360a1db4b29d5c559a` (144,811,072 bytes).
This is a third-party quantization used only as a transport/inference smoke fixture;
the tiny model's summary quality was poor and it is not a production recommendation.
No model or executable is bundled by this module. No cloud account, native Windows
LLM process, or representative Russian summary quality has been verified. Install a
compatible completion executable and GGUF manually and evaluate your chosen model.

## Provider registry

`ProviderRegistry.create(ProviderConfig)` routes `openai`, `anthropic`, `google`,
`openrouter`, `ollama`, `custom`, and named custom services with an explicit URL.
`openai-compatible` remains an alias. `defaultBaseUrl` returns null for local/custom
providers. `parseModel("openrouter/org/model")` splits only the first slash;
`local/C:\models\model.gguf` and `local//opt/models/model.gguf` retain their paths.
Local engines still require the executable via `SummaryEngines.local`.

The overload `create(config, BooleanSupplier localOnlyPolicy)` rechecks the policy
before every completion. This includes each map-reduce request and model validation;
revoking policy prevents subsequent requests (the caller must cancel an already
running request when revoking consent). All HTTP, including Ollama, requires consent
and is blocked by local-only. `ProviderConfig.toString()` redacts credentials.

`validateModel(engine)` performs a real request with a fixed non-sensitive prompt and
32 output tokens; a network or model error fails validation. It does not close the
engine: its owner must close it. Reasoning models that cannot finish within 32 tokens
may fail this inexpensive probe even when usable with larger output budgets.

Native adapters use the same bounded response, cancellation, HTTPS and no-redirect
implementation as OpenAI-compatible providers:
- [Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create):
  `/v1/messages`, `x-api-key`, `anthropic-version: 2023-06-01`, text content blocks;
  only `end_turn` counts as complete.
- [Gemini generateContent](https://ai.google.dev/api/generate-content):
  `/v1beta/models/{model}:generateContent`, `x-goog-api-key`, `contents`,
  `generationConfig.maxOutputTokens`; only `STOP` counts as complete. Thought
  blocks are excluded from output. API keys never appear in the request URL.

Missing, safety-filtered, tool-only or truncated responses fail rather than silently
producing a partial summary. Tests verify native wire formats with a loopback server;
no live Anthropic/Google account has been used for this release.
