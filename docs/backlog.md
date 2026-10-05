# Декомпозиция 0.9.0-beta.1 и назначения

Тимлид объединил три параллельных направления; «реализовано» означает код и
указанные проверки, не автоматическое выполнение аппаратной приёмки ТЗ.

| Задача | Субагент / владелец | Результат и проверка |
|---|---|---|
| AUDIO-02: VAD, recovery, сохранение шкалы | release_audio | 20 мс adaptive energy VAD, чанки ≤60 с, WAV repair; unit и настоящий Whisper smoke |
| CORE-03: storage/resume/locks | release_audio | Дата+UUID, duration, OS locks на запись/обработку, SHA-256 transcript commit; 22 core-теста |
| CORE-04: настройки обработки | release_audio | Имя, язык саммари, шаблоны, dedup, opt-in audio cleanup; тесты |
| CAP-02: устройства и reconnect | release_capture | Список endpoints, default migration, retry explicit ID, 48k timeline, checkpoints; cross-build и два portable native-теста |
| UI-02: desktop helpers | release_capture + тимлид | Tray, hotkey, устройства, предупреждения, шаблоны; автоматические проверки, Windows runtime ожидает |
| LLM-04: реестр провайдеров | release_providers | OpenAI/Anthropic/Gemini/OpenRouter/Ollama/custom, provider/model, validation, policy checks; 13 llm-тестов |
| PKG-03: portable beta | release_providers + тимлид | Windows Java, static CPU capture/Whisper/llama, small-q5_1, manifests/checksums/licenses inventory |
| UI-03: интеграция версии | тимлид | RU/EN, настройки, duration/resume, save/probe flow; 25 app-тестов; новая GUI визуально не проверена |
| QA-02: native samples / документация | release_audio + тимлид | JFK с паузами/offsets и русский TTS; 64 Java-теста без ошибок/пропусков |

Зависимости: audio/storage и providers разрабатывались параллельно capture;
после интеграции UI собран Windows portable payload. Подробные доказательства:
[verification](verification.md), [release-acceptance](release-acceptance.md).

## Оставшаяся приёмка

| Задача | Нужный исполнитель / среда | Критерий |
|---|---|---|
| WIN-01 | Windows QA + пользовательское оборудование | Windows 10/11, 5 конфигураций; оба канала, reconnect, tray/hotkey |
| WIN-02 | Audio QA | Zoom/Meet/Teams по часу, drift ≤0,5 с; 4 часа, диск, kill |
| PRIV-02 | Windows/security QA | Credential Manager, HKLM, проверка сетевого трафика local-only |
| MODEL-02 | STT/LLM QA | Размеченный русский корпус WER ≤15%; CPU/RAM; достоверность двухчасового саммари |
| API-01 | Provider QA с реальными ключами | Реальные ответы, лимиты и ошибки API; fixture-тесты не заменяют live calls |
| PKG-04 | Windows release engineer | Чистая portable runtime приёмка; собрать MSI по script, install/upgrade/uninstall |
| GPU-01 | Native/GPU engineer | CUDA/Vulkan, выбор backend и fallback — не входят в CPU beta |
| LEGAL-01 | Автор + release engineer | Выбрать лицензию своего кода, проверить notices, подпись и решение о публичной публикации |

Нейросетевой VAD, полноценная диаризация и высококачественный DSP остаются отдельными
улучшениями. Эта итерация не объявляет закрытыми все M-требования версии 1.0.
