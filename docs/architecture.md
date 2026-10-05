# Архитектура 0.9.0-beta.1

Java 21/Gradle/JavaFX; отдельный C++20 WASAPI-хелпер. Зависимости:
`app → core → stt, llm`. Portable Windows x64 поставляет Java, CPU native CLI,
multilingual Whisper small-q5_1; GGUF-веса LLM пользователь добавляет отдельно.

## Сквозной сценарий

1. Java создаёт `yyyy-MM-dd_HH-mm-ss_UUID`, metadata и OS file lock.
2. Capture записывает mic/loopback PCM16 mono 48 kHz на общей QPC-шкале.
   Endpoint selection и reconnect сохраняют шкалу; пропуски заполняются тишиной.
3. JSONL stop/EOF финализирует WAV; Java проверяет завершение процесса.
4. Core восстанавливает канонический capture-заголовок при необходимости,
   нормализует в 16 kHz и выделяет VAD-диапазоны по 20 мс кадрам.
5. Диапазоны до 60 с обрабатываются whisper-cli последовательно, времена возвращаются
   в исходную шкалу. Два источника объединяются; опционально удаляются точные дубли.
6. Transcript JSON/Markdown заменяются атомарно пофайлово; SHA-256 commit сохраняется
   последним. Новый STT инвалидирует старое саммари. History включает длительность.
7. STT закрыт до создания LLM. После записи саммари выполняется автоматически
   при выбранном режиме; в истории есть повторный запуск. Local GGUF либо HTTP
   после явного согласия. ProviderRegistry маршрутизирует OpenAI, Anthropic, Gemini,
   OpenRouter, Ollama и custom; язык и UTF-8 шаблон задаются отдельно.
8. Bounded map/reduce сохраняет summary и provenance. По выбору пользователя только
   после успеха удаляется аудио; при summary `none` — после успешной STT. По умолчанию исходники остаются.

`resume` повторно использует только fingerprint-валидный transcript commit, иначе
запускает STT; автоматически cloud-запросы не инициируются. OS lock удерживается
во время записи и каждой операции; аварийное завершение процесса освобождает lock.
Пофайловые атомарные записи не являются общей транзакцией summary + metadata.

## IPC и UI

JSONL UTF-8, protocolVersion 1: ready/started/levels/deviceChanged/stopped/error;
команда stop по stdin. `--list-devices` возвращает активные endpoints.
Default-устройства отслеживаются; явный endpoint после отключения повторно открывается
до 30 с. Подробный контракт: [capture-windows](../capture-windows/README.md).
JavaFX предоставляет русский/английский UI, tray, Ctrl+Alt+R, историю, настройки,
предупреждения о тишине, проверку модели и редактируемые файлы шаблонов.
В этой итерации визуальный запуск нового интерфейса не подтверждён.

## Приватность и границы

Аудио остаётся у локального STT. HTTP получает текст; local-only блокирует любой
HTTP, включая loopback. HKLM policy проверяется перед запросами, consent требуется
для передачи встречи. Секреты — env/Credential Manager references; YAML без ключей.
Redirect запрещён; ответы/ожидания ограничены. Телеметрии и runtime downloads нет.

CLI вместо JNI/LangChain4j остаётся осознанным отклонением. VAD энергетический,
не neural classifier; ресемплер area-average; GPU backend не включены в portable.
Аппаратная Windows-приёмка и качество моделей описаны в
[release-acceptance](release-acceptance.md).
