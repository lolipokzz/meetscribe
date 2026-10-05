package io.meetscribe.app.ui;
import javafx.scene.*;
import javafx.scene.control.*;
import java.util.*;
public final class Texts {
    private Texts(){}
    private static boolean english;
    private static final Map<String,String> TEXT=new LinkedHashMap<>();
    static {
        add("Готово","Ready");add("Встречи","Meetings");add("Настройки","Settings");add("Транскрипт","Transcript");add("Саммари","Summary");
        add("● Начать запись","● Start recording");add("■ Остановить","■ Stop");add("Отменить обработку","Cancel processing");add("Транскрибировать","Transcribe");
        add("Открыть папку","Open folder");add("Удалить встречу","Delete meeting");add("Удалить аудио","Delete audio");add("Микрофон","Microphone");add("Система","System");
        add("Запись встречи → локальный транскрипт → саммари","Record → local transcript → summary");
        add("🔒 Только локальная обработка","🔒 Local processing only");add("Аудио локально · отправка текста только после подтверждения","Audio stays local · send text only with confirmation");
        add("Транскрипт появится после обработки записи.","Your transcript will appear after processing.");
        add("Выберите модель в настройках и нажмите «Саммари».","Choose a model in Settings, then click Summary.");
        add("Проверьте решения и задачи по транскрипту: саммари может содержать ошибки.","Verify decisions and action items against the transcript: summaries may contain errors.");
        add("Папка встреч","Meetings folder");add("Capture.exe (Windows)","Capture.exe (Windows)");add("STT-модель ggml","STT model (ggml)");add("Язык (auto / ru / en)","Meeting language (auto / ru / en)");
        add("Микрофон записи","Recording microphone");add("Устройство вывода","Output device");add("Обновить устройства","Refresh devices");add("По умолчанию Windows","Windows default");
        add("Только локально — блокировать все HTTP-провайдеры","Local only — block all HTTP providers");add("LLM-модель GGUF","LLM model (GGUF)");
        add("Ключ: {env:…}/{credential:…}","Key: {env:…}/{credential:…}");add("Контекст LLM (токены)","LLM context (tokens)");add("Сохранить ключ","Store key");
        add("Новый ключ — только Windows Credential Manager","New key — Windows Credential Manager only");add("Сохранить настройки","Save settings");
        add("Язык интерфейса","Interface language");add("Язык саммари (auto / ru / en)","Summary language (auto / ru / en)");add("Ваше имя в транскрипте","Your transcript name");
        add("Шаблон саммари (.md)","Summary template (.md)");add("Открыть шаблоны","Open templates");add("Удалять аудио после успешной обработки","Delete audio after successful processing");
        add("Убирать повторные реплики двух дорожек","Deduplicate overlapping speech across tracks");add("Напоминать предупредить участников","Remind me to notify participants");
        add("Горячая клавиша Ctrl+Alt+R","Global shortcut Ctrl+Alt+R");add("Проверить модель","Test model");add("Возобновить обработку","Resume processing");
        add("Скрыть в трей","Hide to tray");add("Начать запись?","Start recording?");add("Закрыть MeetScribe?","Quit MeetScribe?");
        add("Предупредите участников встречи о записи. Аудио будет сохранено только на этом компьютере.","Notify participants before recording. Audio will be stored only on this computer.");
        add("Текущая обработка будет отменена; запись будет остановлена, аудио останется на диске.","Processing will be cancelled and recording stopped. Audio stays on disk.");
        add("Захват WASAPI доступен в Windows 10/11 x64. Здесь можно работать с историей и инженерным CLI.","WASAPI capture requires Windows 10/11 x64. History and the developer CLI are available here.");
        add("Отмена… ожидаем завершения процесса","Cancelling… waiting for the process to exit");add("Обработка…","Processing…");add("Запуск аудиозахвата…","Starting capture…");
        add("Идёт запись. Аудио сохраняется на диск.","Recording. Audio is being saved to disk.");add("Остановка записи…","Stopping recording…");
        add("Создание саммари…","Generating summary…");add("Удаление…","Deleting…");add("Настройки сохранены.","Settings saved.");add("Настройки сохранены. Язык и горячая клавиша изменятся после перезапуска.","Settings saved. Restart to apply language and hotkey changes.");
        add("HTTP-саммари заблокировано режимом «только локально».","HTTP summaries are blocked by local-only mode.");add("Передать текст провайдеру?","Send text to provider?");
        add("Удалить аудио?","Delete audio?");add("Удалить встречу?","Delete meeting?");add("Дорожки будут удалены безвозвратно; транскрипт и саммари останутся.","Audio will be permanently deleted. Transcript and summary will remain.");
        add("Все файлы выбранной встречи будут удалены безвозвратно.","All files in the selected meeting will be permanently deleted.");
        add("Обработка отменена. Сохранённые данные остаются в истории.","Processing cancelled. Saved data remains in history.");add("Операция не выполнена.","Operation failed.");
        add("Не удалось прочитать файл.","Unable to read file.");add("Проверка модели…","Testing model…");add("Модель доступна.","Model is available.");
        add("Сохранить и проверить модель?","Save and test model?");add("Будет отправлен только технический тест без текста встречи. Адрес: ","Only a technical test without meeting text will be sent. Destination: ");
        add("Провайдер саммари отключён.","Summary provider is disabled.");add("Получение списка устройств…","Loading audio devices…");add("Список устройств обновлён.","Audio device list updated.");
        add("Аудиоустройство подключено. Запись продолжается.","Audio device connected. Recording continues.");add("Переподключение аудиоустройства… пауза заполняется тишиной.","Reconnecting audio device… the gap is being filled with silence.");
        add("Микрофон молчит более 60 секунд. Проверьте устройство.","The microphone has been silent for over 60 seconds. Check the device.");add("Системный звук молчит более 60 секунд. Проверьте устройство.","System audio has been silent for over 60 seconds. Check the device.");
        add("Preparing local audio","Preparing local audio");add("Transcript saved","Transcript saved");add("Summary saved","Summary saved");
    }
    private static void add(String ru,String en){TEXT.put(ru,en);}
    public static void language(String code){english=code.equals("en");}
    public static String text(String input){
        if(input==null)return "";
        if(english) {
            String exact=TEXT.get(input);if(exact!=null)return exact;
            if(input.startsWith("Текст выбранного транскрипта будет отправлен на "))return input.replace("Текст выбранного транскрипта будет отправлен на ","The selected transcript will be sent to ").replace("Модель:","Model:").replace("Аудио не отправляется.","Audio is not sent.");
            return input;
        }
        return switch(input){case "Preparing local audio"->"Подготовка локального аудио";case "Transcript saved"->"Транскрипт сохранён";case "Summary saved"->"Саммари сохранено";default->input;};
    }
    public static void localize(Node node){
        if(node instanceof Labeled l)l.setText(text(l.getText()));
        if(node instanceof TextInputControl c)c.setPromptText(text(c.getPromptText()));
        if(node instanceof TabPane tabs)for(Tab tab:tabs.getTabs()){tab.setText(text(tab.getText()));if(tab.getContent()!=null)localize(tab.getContent());}
        else if(node instanceof ScrollPane scroll && scroll.getContent()!=null)localize(scroll.getContent());
        else if(node instanceof SplitPane split)split.getItems().forEach(Texts::localize);
        else if(node instanceof Parent p)p.getChildrenUnmodifiable().forEach(Texts::localize);
    }
    public static String state(String value){return switch(value){
        case "CREATED"->english?"New":"Новая";case "RECORDING"->english?"Recording":"Запись";case "RECORDED"->english?"Recorded":"Аудио сохранено";
        case "TRANSCRIBING"->english?"Transcribing":"Транскрибация";case "TRANSCRIBED"->english?"Transcribed":"Транскрипт готов";
        case "SUMMARIZING"->english?"Summarizing":"Саммари";case "COMPLETED"->english?"Completed":"Готово";case "FAILED"->english?"Failed — retry available":"Ошибка — можно повторить";
        case "CAPTURE_FAILED"->english?"Capture failed — audio retained":"Ошибка записи — аудио сохранено";case "CANCELLED"->english?"Cancelled":"Отменено";default->value;};}
}
