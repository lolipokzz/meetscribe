package io.meetscribe.app.config;
import java.io.*;
import java.nio.file.*;
public final class TemplateStore {
    private TemplateStore(){}
    public static Path directory(){return AppConfig.location().toAbsolutePath().getParent().resolve("templates");}
    public static void install() throws IOException {
        Files.createDirectories(directory());
        for(String name:new String[]{"meeting-minutes","one-on-one","interview"}) {
            Path file=directory().resolve(name+".md");
            if(!Files.exists(file))try(InputStream in=TemplateStore.class.getResourceAsStream("/templates/"+name+".md")){
                if(in==null)throw new IOException("Bundled summary template is missing: "+name);
                try {Files.copy(in,file);}catch(FileAlreadyExistsException ignored){ /* Concurrent first-run initialization preserves user content. */ }
            }
        }
    }
    public static Path selected(String path){return path.isBlank()?directory().resolve("meeting-minutes.md"):Path.of(path);}
}
