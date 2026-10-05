package io.meetscribe.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @TempDir Path dir;
    @Test void defaultsRoundTripAndDoNotContainSecretValues() throws Exception {
        AppConfig config = AppConfig.defaults();
        Path file = dir.resolve("config.yaml"); config.save(file);
        assertEquals(config, AppConfig.load(file));
        assertTrue(Files.readString(file).contains("{env:OPENAI_API_KEY}"));
        assertTrue(AppConfig.load(file).localOnly());
    }
    @Test void missingConfigIsSafeAndPropertySelectsCapture() throws Exception {
        String previous = System.getProperty("meetscribe.capture");
        try {
            System.setProperty("meetscribe.capture", dir.resolve("capture.exe").toString());
            AppConfig c = AppConfig.load(dir.resolve("missing.yaml"));
            assertTrue(c.localOnly()); assertEquals("none", c.summaryMode());
            assertEquals(dir.resolve("capture.exe").toString(), c.capture());
        } finally {
            if (previous == null) System.clearProperty("meetscribe.capture"); else System.setProperty("meetscribe.capture", previous);
        }
    }
    @Test void rejectsWrongSchemaTypesUnknownFieldsPlaintextAndDuplicateKeys() throws Exception {
        String[] invalid = {
            "schema-version: 1\n---\nschema-version: 1\n", "schema-version: 2\n", "language: ru\n", "schema-version: '1'\n",
            "schema-version: 1\nprivacy:\n  local-only: 'false'\n",
            "schema-version: 1\nprivacy: false\n",
            "schema-version: 1\nsummary:\n  context-size: '8192'\n",
            "schema-version: 1\nsummary:\n  api-key: secret-value-do-not-leak\n",
            "schema-version: 1\nsummary:\n  api-key: 123\n",
            "schema-version: 1\nsummary:\n  mode: unsupported\n",
            "schema-version: 1\nsummary:\n  context-size: 9999999999999999\n",
            "schema-version: 1\nprivacy:\n  localOnly: false\n",
            "schema-version: 1\nprivacy:\n  local-only: true\n  local-only: false\n",
            "schema-version: 1\nsummary: [secret-value-do-not-leak\n"
        };
        for (String source : invalid) {
            Path file = Files.writeString(dir.resolve("invalid.yaml"), source);
            IOException error = assertThrows(IOException.class, () -> AppConfig.load(file), source);
            assertFalse(error.toString().contains("secret-value-do-not-leak"));
            assertNull(error.getCause());
        }
    }
    @Test void minimalSchemaDefaultsToLocalOnlyAndSupportsSecretReferences() throws Exception {
        Path file = Files.writeString(dir.resolve("config.yaml"), "schema-version: 1\nsummary:\n  api-key: '{credential:my-key}'\n");
        assertTrue(AppConfig.load(file).localOnly());
        assertEquals("{credential:my-key}", AppConfig.load(file).keyReference());
    }
    @Test void rejectsOversizeConfig() throws Exception {
        Path file = Files.writeString(dir.resolve("large.yaml"), "x".repeat(1024 * 1024 + 1));
        assertThrows(IOException.class, () -> AppConfig.load(file));
    }
    @Test void newFieldsRoundTripAndOldSchemaMigrates() throws Exception {
        Path file=dir.resolve("extended.yaml");
        AppConfig c=new AppConfig(dir,"capture","whisper","model","ru","usb-in","usb-out","anthropic",
                "llama","llm","https://api.anthropic.com","claude","{credential:test}",4096,false,
                "en","ru","Alice","custom.md",true,true,false,false);
        c.save(file);assertEquals(c,AppConfig.load(file));
        Files.writeString(file,"schema-version: 1\nsummary:\n  mode: openai-compatible\n");
        AppConfig old=AppConfig.load(file);
        assertEquals("ru",old.uiLanguage());assertEquals("auto",old.summaryLanguage());
        assertFalse(old.deleteAudioAfterProcessing());assertFalse(old.deduplicate());
        assertTrue(old.remindBeforeRecording());assertTrue(old.hotkeyEnabled());
    }
    @Test void reportsParserLineWithoutSourceAndRejectsNewFieldTypes() throws Exception {
        Path file=dir.resolve("invalid.yaml");
        Files.writeString(file,"schema-version: 1\nsummary: [secret-private\n");
        IOException e=assertThrows(IOException.class,()->AppConfig.load(file));
        assertTrue(e.getMessage().contains("Строка"));assertFalse(e.toString().contains("secret-private"));
        for(String extra:new String[]{"ui:\n  language: de\n","storage:\n  delete-audio-after-processing: 'true'\n",
                "hotkey:\n  enabled: 1\n","transcription:\n  speaker-name: ''\n","audio:\n  microphone: ''\n"}) {
            Files.writeString(file,"schema-version: 1\n"+extra);
            assertThrows(IOException.class,()->AppConfig.load(file));
        }
    }

    @Test void semanticErrorReportsExactLineWithoutSecret() throws Exception {
        Path file=Files.writeString(dir.resolve("semantic.yaml"),"schema-version: 1\nsummary:\n  api-key: secret-value-do-not-leak\n");
        IOException error=assertThrows(IOException.class,()->AppConfig.load(file));
        assertTrue(error.getMessage().contains("Строка 3."),error.getMessage());
        assertFalse(error.toString().contains("secret-value"));
        Files.writeString(file,"schema-version: 1\nhotkey:\n  enabled: wrong\n");
        assertTrue(assertThrows(IOException.class,()->AppConfig.load(file)).getMessage().contains("Строка 3."));
    }

}
