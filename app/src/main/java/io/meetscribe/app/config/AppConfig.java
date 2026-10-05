package io.meetscribe.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.*;

/** Versioned, secret-reference-only configuration. Loading never makes network requests. */
public record AppConfig(Path output, String capture, String whisper, String sttModel,
        String language, String microphone, String outputDevice, String summaryMode,
        String llama, String llmModel, String endpoint, String remoteModel,
        String keyReference, int contextSize, boolean localOnly,
        String uiLanguage, String summaryLanguage, String speakerName, String template,
        boolean deleteAudioAfterProcessing, boolean deduplicate, boolean remindBeforeRecording,
        boolean hotkeyEnabled) {
    public AppConfig(Path output,String capture,String whisper,String sttModel,String language,String microphone,
            String outputDevice,String summaryMode,String llama,String llmModel,String endpoint,String remoteModel,
            String keyReference,int contextSize,boolean localOnly) {
        this(output,capture,whisper,sttModel,language,microphone,outputDevice,summaryMode,llama,llmModel,endpoint,
            remoteModel,keyReference,contextSize,localOnly,"ru","auto","Я","",false,false,true,true);
    }
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static Path location() {
        String override=System.getProperty("meetscribe.config");
        if(override!=null&&!override.isBlank()) return Path.of(override);
        String appdata = System.getenv("APPDATA");
        return (appdata == null ? Path.of(System.getProperty("user.home"), ".config") : Path.of(appdata))
                .resolve("MeetScribe/config.yaml");
    }
    public static AppConfig defaults() {
        String capture = System.getProperty("meetscribe.capture", System.getenv().getOrDefault("MEETSCRIBE_CAPTURE", ""));
        String appDir = System.getProperty("jpackage.app-path");
        if (capture.isBlank() && appDir != null) {
            Path bundled = Path.of(appDir).getParent().resolve("app/meetscribe-capture.exe");
            if (Files.isRegularFile(bundled)) capture = bundled.toString();
        }
        return new AppConfig(Path.of(System.getProperty("user.home"), "Documents", "MeetScribe"),
                capture, System.getProperty("meetscribe.whisper", ""), System.getProperty("meetscribe.sttModel", ""),
                "auto", "default", "default", "none", System.getProperty("meetscribe.llama", ""), "",
                "https://api.openai.com/v1", "", "{env:OPENAI_API_KEY}", 8192, true);
    }
    public static AppConfig load(Path path) throws IOException {
        AppConfig d = defaults();
        if (!Files.exists(path)) return d;
        if (Files.size(path) > 1024 * 1024) throw new IOException("Файл настроек слишком большой.");
        try {
            JsonNode n = YAML.readTree(path.toFile());
            if (n == null || !n.isObject()) throw new IllegalArgumentException();
            JsonNode version = n.get("schema-version");
            if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1)
                throw new InvalidSetting("/schema-version", "Invalid schema");
            checkFields(n, "", "schema-version", "output-dir", "capture", "transcription", "language", "audio", "summary", "privacy", "ui", "storage", "hotkey");
            checkSection(n, "capture", "executable");
            checkSection(n, "transcription", "executable", "model", "speaker-name", "deduplicate");
            checkSection(n, "audio", "microphone", "output-device", "remind-before-recording");
            checkSection(n, "summary", "mode", "executable", "local-model", "base-url", "model", "api-key", "context-size", "language", "template");
            checkSection(n, "privacy", "local-only");
            checkSection(n, "ui", "language");
            checkSection(n, "storage", "delete-audio-after-processing");
            checkSection(n, "hotkey", "enabled");
            AppConfig config = new AppConfig(Path.of(text(n,"/output-dir",d.output.toString())),
                    text(n,"/capture/executable",d.capture), text(n,"/transcription/executable",d.whisper),
                    text(n,"/transcription/model",d.sttModel), text(n,"/language",d.language),
                    text(n,"/audio/microphone",d.microphone), text(n,"/audio/output-device",d.outputDevice),
                    text(n,"/summary/mode",d.summaryMode), text(n,"/summary/executable",d.llama),
                    text(n,"/summary/local-model",d.llmModel), text(n,"/summary/base-url",d.endpoint),
                    text(n,"/summary/model",d.remoteModel), text(n,"/summary/api-key",d.keyReference),
                    integer(n,"/summary/context-size",d.contextSize), bool(n,"/privacy/local-only",true),
                    text(n,"/ui/language",d.uiLanguage), text(n,"/summary/language",d.summaryLanguage),
                    text(n,"/transcription/speaker-name",d.speakerName),text(n,"/summary/template",d.template),
                    bool(n,"/storage/delete-audio-after-processing",false),bool(n,"/transcription/deduplicate",false),
                    bool(n,"/audio/remind-before-recording",true),bool(n,"/hotkey/enabled",true));
            config.validate();
            return config;
        } catch (Exception e) {
            // Parser errors may contain plaintext secrets or entire YAML lines.
            String line="";
            if(e instanceof com.fasterxml.jackson.core.JsonProcessingException json && json.getLocation()!=null)
                line=" Строка "+json.getLocation().getLineNr()+".";
            else if(e instanceof InvalidSetting invalid) line=" Строка "+lineOf(path,invalid.pointer)+".";
            throw new IOException("Некорректные настройки: проверьте схему версии 1, типы значений и ссылки на секреты."+line);
        }
    }
    private static final class InvalidSetting extends IllegalArgumentException {
        final String pointer;
        InvalidSetting(String pointer,String message){super(message);this.pointer=pointer;}
    }
    private static int lineOf(Path path,String pointer) {
        try(var parser=YAML.getFactory().createParser(path.toFile())) {
            while(parser.nextToken()!=null) {
                if(parser.getParsingContext().pathAsPointer().toString().equals(pointer))
                    return Math.max(1,parser.currentTokenLocation().getLineNr());
            }
        }catch(IOException ignored){}
        return 1;
    }
    private static void checkFields(JsonNode n, String prefix, String... allowed) {
        java.util.Set<String> names = java.util.Set.of(allowed);
        n.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) throw new InvalidSetting(prefix+"/"+name.replace("~","~0").replace("/","~1"),"Unknown field"); });
    }
    private static void checkSection(JsonNode n, String name, String... allowed) {
        JsonNode section = n.get(name);
        if (section == null) return;
        if (!section.isObject()) throw new InvalidSetting("/"+name,"Invalid section");
        checkFields(section, "/"+name, allowed);
    }
    private static String text(JsonNode n, String pointer, String fallback) {
        JsonNode v = n.at(pointer);
        if (v.isMissingNode()) return fallback;
        if (!v.isTextual()) throw new InvalidSetting(pointer,"Invalid text");
        return v.textValue();
    }
    private static int integer(JsonNode n, String pointer, int fallback) {
        JsonNode v = n.at(pointer);
        if (v.isMissingNode()) return fallback;
        if (!v.isIntegralNumber() || !v.canConvertToInt()) throw new InvalidSetting(pointer,"Invalid integer");
        return v.intValue();
    }
    private static boolean bool(JsonNode n, String pointer, boolean fallback) {
        JsonNode v = n.at(pointer);
        if (v.isMissingNode()) return fallback;
        if (!v.isBoolean()) throw new InvalidSetting(pointer,"Invalid boolean");
        return v.booleanValue();
    }
    public void validate() {
        if (output == null || capture == null || whisper == null || sttModel == null || language == null
                || microphone == null || outputDevice == null || summaryMode == null || llama == null
                || llmModel == null || endpoint == null || remoteModel == null || keyReference == null || uiLanguage == null)
            throw new IllegalArgumentException("Настройки содержат пустое значение.");
        if (output.toString().isBlank()) throw new IllegalArgumentException("Укажите каталог сохранения.");
        if (!java.util.Set.of("none", "local", "openai-compatible", "openai", "anthropic", "google", "openrouter", "ollama", "custom").contains(summaryMode))
            throw new InvalidSetting("/summary/mode","Неизвестный режим саммари.");
        if (contextSize < 512 || contextSize > 2_000_000)
            throw new InvalidSetting("/summary/context-size","Контекст должен быть от 512 до 2000000 токенов.");
        if (!keyReference.isBlank() && !keyReference.matches("\\{(env|credential):[A-Za-z0-9_.-]+}"))
            throw new InvalidSetting("/summary/api-key","В настройках допустима только ссылка {env:NAME} или {credential:NAME}.");
        if (!java.util.Set.of("ru","en").contains(uiLanguage)) throw new InvalidSetting("/ui/language","Выберите ru или en.");
        if(summaryLanguage==null||summaryLanguage.isBlank())throw new InvalidSetting("/summary/language","Укажите язык саммари.");
        if(speakerName==null||speakerName.isBlank())throw new InvalidSetting("/transcription/speaker-name","Укажите имя говорящего.");
        if(template==null)throw new InvalidSetting("/summary/template","Некорректный путь шаблона.");
        if (microphone.isBlank() || outputDevice.isBlank()) throw new IllegalArgumentException("Укажите аудиоустройство или default.");
        if (speakerName.length() > 200 || speakerName.indexOf('\n') >= 0 || speakerName.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Имя говорящего должно занимать одну строку до 200 символов.");
        if (language.isBlank()) throw new IllegalArgumentException("Укажите язык или auto.");
    }
    public void save(Path path) throws IOException {
        validate();
        ObjectNode n = YAML.createObjectNode(); n.put("schema-version", 1);
        n.put("output-dir",output.toAbsolutePath().normalize().toString()); n.put("language",language);
        n.putObject("capture").put("executable",capture);
        n.putObject("audio").put("microphone",microphone).put("output-device",outputDevice).put("remind-before-recording",remindBeforeRecording);
        n.putObject("transcription").put("executable",whisper).put("model",sttModel).put("speaker-name",speakerName).put("deduplicate",deduplicate);
        n.putObject("summary").put("mode",summaryMode).put("executable",llama).put("local-model",llmModel)
                .put("base-url",endpoint).put("model",remoteModel).put("api-key",keyReference).put("context-size",contextSize).put("language",summaryLanguage).put("template",template);
        n.putObject("privacy").put("local-only",localOnly);
        n.putObject("ui").put("language",uiLanguage);
        n.putObject("storage").put("delete-audio-after-processing",deleteAudioAfterProcessing);
        n.putObject("hotkey").put("enabled",hotkeyEnabled);
        Path parent = path.toAbsolutePath().getParent(); Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent,"config-",".tmp");
        try {
            YAML.writeValue(temp.toFile(),n);
            try { Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
