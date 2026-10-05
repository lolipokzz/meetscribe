# Сторонние компоненты 0.9.0-beta.1

Portable beta включает Windows Java runtime/JavaFX, Java-зависимости, native
capture/Whisper/llama-completion и multilingual Whisper small-q5_1.
GGUF-веса LLM не включены. Для пользовательских весов отдельно действуют
условия их поставщика.

Актуальная инвентаризация и материалы сборки:

- [Уведомления о сторонних компонентах](packaging/THIRD_PARTY_NOTICES.txt).
- [Тексты лицензий](packaging/third-party-licenses/).
- [Зафиксированные версии, источники и хеши](packaging/sources-pinned.json).
- [Описание состава и сборки portable](packaging/README.md).

Assembler переносит runtime legal files, извлекает license/notice из Java JAR
и добавляет native notices в portable. Внутренний manifest перечисляет хеши
payload; рядом с ZIP находится внешний SHA-256. Сборочные инструменты и
непоставляемые тестовые модели не следует смешивать с runtime payload.

Лицензия **собственного кода MeetScribe ещё не назначена**: её выбирает автор.
Наличие сторонних notices не предоставляет отдельную лицензию на код проекта.
Перед публичной публикацией требуется проверить полный состав конкретного
артефакта, условия распространения компонентов и выбранную лицензию автора.
