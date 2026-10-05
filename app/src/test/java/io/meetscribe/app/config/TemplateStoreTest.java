package io.meetscribe.app.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class TemplateStoreTest {
    @TempDir Path dir;
    @Test void installsBundledTemplatesWithoutReplacingUserEdits()throws Exception {
        String previous=System.getProperty("meetscribe.config");
        try {
            System.setProperty("meetscribe.config",dir.resolve("config.yaml").toString());
            TemplateStore.install();
            for(String name:new String[]{"meeting-minutes","one-on-one","interview"})assertFalse(Files.readString(TemplateStore.directory().resolve(name+".md")).isBlank());
            Path file=TemplateStore.selected("");Files.writeString(file,"My custom summary instructions");
            TemplateStore.install();assertEquals("My custom summary instructions",Files.readString(file));
            assertEquals(Path.of("my-custom.md"),TemplateStore.selected("my-custom.md"));
        }finally{if(previous==null)System.clearProperty("meetscribe.config");else System.setProperty("meetscribe.config",previous);}
    }
}
