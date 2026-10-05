# Статус 0.9.0-beta.1

Подготовлена первая portable beta для Windows x64: собственная Java, capture,
CPU Whisper и multilingual small-q5_1; llama-completion включён без LLM-весов.
Реализованы VAD, восстановление/возобновление, блокировки, устройства/reconnect,
tray/hotkey, RU/EN, шаблоны, настройка обработки и реестр провайдеров.

**Это ещё не подтверждённый стабильный Windows-релиз.** Новая GUI не прошла
визуальный запуск в этой итерации; WASAPI, hotkey/tray, системные политики,
Credential Manager и portable runtime ожидают целевой Windows-приёмки.
MSI существует как script, а не испытанный установщик. GPU-поставка отсутствует.

Фактические результаты: 64 Java-теста (app 25, core 22, llm 13, stt 4),
0 failures/errors/skips; два portable native-теста; Windows cross-compilation;
настоящий VAD → Whisper на английском образце с паузами и русском TTS.
Русский smoke с small-q5_1 дал точное совпадение слов на пяти синтетических
предложениях; это не WER-квалификация. Предыдущий tiny LLM smoke подтверждал
инференс, но показал плохое качество и не является рекомендацией модели.

Нужны реальная аппаратная матрица, длинные встречи/аварии, WER и качество саммари,
live API, установка и подпись. Лицензия своего кода остаётся решением автора;
публичный GitHub release не создавался.

[Приёмка](release-acceptance.md) · [Проверки](verification.md) · [План](backlog.md) ·
[Сборка portable/MSI](../packaging/README.md).
