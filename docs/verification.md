# Проверки 0.9.0-beta.1 — 5 октября 2026

Среда: macOS arm64, JDK 21, Gradle 9.4.1. Следует различать локальные
автотесты, cross-compilation и выполнение на целевой Windows-машине.

## Java

В XML-результатах: **64 tests, 0 failures, 0 errors, 0 skipped**:
app 25, core 22, llm 13, stt 4. Команда воспроизведения: `./gradlew test`.
Проверены VAD/offsets, WAV recovery, resume commit, OS locks, storage/cancel,
custom prompt/language, cleanup/dedup, bounded map/reduce, provider wire formats,
local-only/consent, YAML/secret references и IPC. HTTP-тесты используют локальный
сервер, process-fixtures — тестовые CLI. Они не вызывают платные live API.

## Настоящий native STT

Whisper 1.7.6 CPU на macOS:

- 120 секунд WAV, JFK speech на 30/80 секунде, tiny.en: обе реплики распознаны,
  исходные timestamps и длительность сохранены; silent channel пропущен.
- Русский локальный TTS Milena, 13,45 секунды, поставляемый small-q5_1: пять
  предложений, точное совпадение слов с коротким эталоном.

Модели, SHA-256, смещения и пределы интерпретации результата сохранены в
[release-acceptance](release-acceptance.md). **Это не квалификация WER ≤15%**.

## Native capture и Windows payload

Два portable C++ теста (`wave-integrity`, `audio-conversion`) прошли на macOS:
целостность WAV, тишина, преобразование PCM/Float, resampling phase, reconnect gaps.
Windows x64 native executable собраны cross-toolchain. Статическая проверка
imports не обнаружила внешних ggml/libc++ runtime DLL; это не запуск на Windows.

Portable assembler собирает JavaFX win + JRE + native CPU + small-q5_1,
проверяет payload и формирует manifests/SHA-256. Параметры и pinned sources:
[packaging](../packaging/README.md). Runtime и GPU на Windows не проверены.

## LLM и интерфейс

Native llama-completion ранее проверялся настоящим маленьким GGUF на macOS:
инференс/сохранение работают, но качество tiny-модели неудовлетворительно.
Она не включена в поставку и не рекомендуется. Новые Anthropic/Gemini и registry
проверены fixture-сервером; нужны отдельные live API проверки.

Новая GUI beta собрана, но **визуальный запуск в этой итерации не подтверждён**.
Предыдущая визуальная проверка MVP не переносится автоматически на новый UI.
Windows tray/hotkey и запись требуют реального запуска. MSI script подготовлен,
MSI не собран/не установлен на чистой Windows.

Полная аппаратная и качественная приёмка: [release-acceptance](release-acceptance.md).
Успешные unit tests не закрывают часовой drift, четырёхчасовую запись,
Credential Manager/HKLM, сетевой аудит, качество саммари и установку.

## Итоговый portable артефакт

`MeetScribe-0.9.0-beta.1-windows-x64.zip`: 253 316 915 байт.
SHA-256: `3b288dea95e75334c3787127585d4509f646e89d26de5b9778e03d04f5e7c000`.
Проверены 377 хешей файлов payload; финальная Java-сборка и 64 теста завершились
успешно. Это проверка целостности поставки, не runtime-приёмка Windows.
