# Native Windows capture

Windows 10/11 x64, C++20, Windows SDK, CMake 3.24+. No external C++ dependencies. Uses shared-mode WASAPI microphone capture and render-loopback capture concurrently on one thread.

```powershell
cmake -S capture-windows -B build/capture -A x64
cmake --build build/capture --config Release
ctest --test-dir build/capture -C Release --output-on-failure
.\build\capture\Release\meetscribe-capture.exe --directory C:\Meetings\new-meeting
```

CLI: `--directory PATH [--microphone default|ENDPOINT_ID] [--output-device default|ENDPOINT_ID]`. Unicode paths use Windows wide arguments. Both default endpoints use `eConsole`. Existing mic.wav/system.wav are never overwritten. Run `--list-devices` to receive one UTF-8 JSON object: `{"protocolVersion":1,"devices":[{"id":"...","name":"...","flow":"input","isDefault":true}]}`. Flow is `input` or `output`; only active endpoints are listed. The synthetic `default` selection follows Windows defaults.

stdout is UTF-8 JSONL, protocolVersion 1:

- `ready`: both endpoints initialized and files open.
- `started`: both clients have actually started.
- `levels`: `mic` and `system`, linear peaks 0–1, every 100 ms.
- `stopped`: clients stopped and WAV headers flushed.
- `deviceChanged`: `track` (`mic`/`system`), `status` (`reconnecting`/`connected`), `deviceId`, `message`; nonfatal notification.
- `limitReached`: `seconds:14400`, followed by graceful `stopped`.
- `error`: `code`, `fatal`, `message`; CAPTURE_FAILED and DEVICE_UNAVAILABLE exit with 2, INVALID_COMMAND is nonfatal.

Send `{"protocolVersion":1,"command":"stop"}` plus newline to stdin for safe stop. Ctrl+C/Break requests a safe stop. Closing stdin also finalizes files, including when the Java parent exits. Unknown commands are rejected. Do not close the stdin pipe while recording.

Both files are PCM16 mono 48,000 Hz WAV, stable across endpoint changes. Float32 and PCM16/24/32 inputs are downmixed and converted using a streaming area-average resampler with fractional phase carried across packets. This is a simple speech-oriented converter, not a high-fidelity bandlimited resampler. Packet QPC timestamps share a common recording origin. Missing packets are filled with silence, with a 200 ms holdback so normal packets can arrive. Headers checkpoint every second, and destructors finalize partial data on ordinary errors. Hard termination can leave up to a second beyond the last checkpoint; a WAV recovery reader can recover complete PCM frames from file length. RIFF's 4 GiB limit is enforced explicitly.

Default endpoints are checked every 500 ms and automatically migrated. Invalidated or disconnected explicit endpoints retry the same ID every 500 ms for up to 30 seconds. Gaps are represented as silence on the same clock. Successful reconnection emits `deviceChanged`; exhausted retries terminate with DEVICE_UNAVAILABLE and preserve partial WAVs. Initial missing/inactive endpoints fail with DEVICE_UNAVAILABLE. Four-hour recording limit stops gracefully before the mono WAV approaches RIFF limits. An abrupt failure may still lose the last pending engine packets.

Windows runtime validation is required: microphone/loopback signal and silence, sample-rate mismatch, device unplug, privacy permission denial, parent termination, disk exhaustion, long recordings and Unicode paths. `capture-help` verifies binary startup/CLI. `wave-integrity` verifies silence padding, PCM preservation, monotonic frame counts, WAV header lengths, and overwrite protection; `audio-conversion` additionally checks mono conversion, Float32 invalid values and clipping, signed PCM24, silent null packets, continuous phase across packet boundaries at 16/44.1/48/96 kHz, a reconnect gap, overlap trimming and native-rate changes. Both portable tests passed locally with AppleClang on macOS. Run `cmake -S capture-windows -B build/native-tests -DBUILD_TESTING=ON`, build, then ctest to execute them on non-Windows systems. Development on macOS cannot validate WASAPI behavior.

Implementation references:
- https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudiocaptureclient-getbuffer
- https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording

Cross-compilation on macOS also passed with llvm-mingw 20260922 / Clang 23.1.2 and CMake 3.31.6: helper, WAV test and audio conversion test linked as Windows x64 PE executables. This caught and fixed the missing `ksuser` GUID library. Cross-compilation does not test WASAPI runtime behavior or the MSVC packaging build. The helper statically links the MinGW C++ runtime; its imports require only Windows system/UCRT DLLs. Portable Windows test executables may still need companion C++ runtime DLLs. The MSVC packaging workflow is also supported.
