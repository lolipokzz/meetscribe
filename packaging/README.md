# Windows beta packaging

The first portable build is `0.9.0-beta.1`. It contains a Windows x64 JRE, Windows
JavaFX libraries, static CPU capture/Whisper/llama-completion executables, and the
multilingual Whisper small-q5_1 STT model. No LLM weights are bundled. This is a
local unsigned beta artifact; clean Windows acceptance and project license choice
remain release gates. See `docs/release-acceptance.md`.

## Portable ZIP

Build Java against JDK21 after all application changes are finished:

```sh
./gradlew :app:installDist -PjavafxPlatform=win
python3 packaging/build-portable.py \
  --jre /path/to/extracted/windows-jre \
  --capture /path/to/meetscribe-capture.exe \
  --whisper /path/to/whisper-cli.exe \
  --llama /path/to/llama-completion.exe \
  --model /path/to/ggml-small-q5_1.bin \
  --licenses /path/to/collected-licenses \
  --sources packaging/sources-pinned.json
```

The assembler performs no downloads. Inputs are explicit local files. It rejects
non-Windows JavaFX dependencies and non-PE executables, verifies the model against
its manifest, preserves runtime legal files, extracts JAR license/notice resources,
and includes supplied native licenses. ZIP entries have stable timestamps and
ordering. `build/releases/` receives the directory, ZIP, and external SHA256 file;
an internal manifest hashes every payload file. Reproducibility of a newly compiled
native executable additionally requires the same compiler, source paths and flags.

`MeetScribe.cmd` runs bundled `runtime/bin/javaw.exe`; `start-debug.cmd` uses console
Java and passes arguments, including `--check`, to the Launcher. Bundled engine/model
JVM defaults are overridden by saved user configuration. Templates reside inside
the app JAR and are installed into the user profile by the app. Runtime downloads
are never performed. Extract the whole ZIP before launching.

## Pinned native builds

`sources-pinned.json` records actual source revisions, artifacts and hashes. Native
executables were cross-compiled using llvm-mingw 20260922 UCRT, with `BUILD_SHARED_LIBS=OFF`,
`CMAKE_EXE_LINKER_FLAGS=-static`, `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, and
`GGML_AVX=OFF`, `GGML_AVX2=OFF`, `GGML_FMA=OFF`, `GGML_F16C=OFF`.

Whisper 1.7.6 uses target `whisper-cli`, `WHISPER_BUILD_TESTS=OFF`, `WHISPER_CURL=OFF`;
`CMAKE_CXX_FLAGS="-include algorithm -include new"` supplies standard headers omitted
by this older upstream source when built with libc++23. The CPU backend is generic.
llama.cpp 0.5.0 uses target `llama-completion`, `LLAMA_BUILD_TESTS=OFF`,
`LLAMA_OPENSSL=OFF`, `CMAKE_SYSTEM_PROCESSOR=AMD64`, and C/C++ flags
`-D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00`. Sources are unmodified.
Capture uses this repository's `capture-windows` CMake project and static MinGW linking.

`llvm-readobj --coff-imports` confirmed native executable dependencies are only
Windows system DLLs and UCRT API sets; there are no external VC++, libc++ or ggml DLL
requirements. This is a static dependency inspection, not a Windows execution test.
The JRE carries its own runtime DLLs. JavaFX DLLs remain inside classifier JARs.

## MSI (Windows build host required)

`build-windows.ps1` produces a JPackage app image or unsigned per-user MSI. Use JDK21,
Visual Studio2022 C++ tools/Windows SDK, CMake/CTest and WiX3.x on Windows10/11 x64:

```powershell
.\packaging\build-windows.ps1 -Type msi -Version 0.9.0 `
  -WhisperExecutable C:\assets\whisper-cli.exe `
  -WhisperModel C:\assets\ggml-small-q5_1.bin `
  -LlamaExecutable C:\assets\llama-completion.exe
```

MSI numeric version `0.9.0` corresponds to beta source version `0.9.0-beta.1`.
Engine companion DLLs, when supplied, are copied to separate native directories.
The output includes JPackage's runtime and does not need a system JDK. No MSI was
built or runtime-tested on this macOS host. Collect third-party notices before
public distribution and validate install/upgrade/uninstall on clean Windows.

Upstream references:
- https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse
- https://github.com/ggml-org/whisper.cpp/tree/v1.7.6
- https://huggingface.co/ggerganov/whisper.cpp
- https://github.com/ggml-org/llama.cpp/tree/7fe450e19305b828c199d602c23a8337aaa1f03b
- https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html
