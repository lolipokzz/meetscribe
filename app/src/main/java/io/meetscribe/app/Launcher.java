package io.meetscribe.app;

import io.meetscribe.app.config.AppConfig;
import io.meetscribe.core.MeetingService;
import java.nio.file.*;

public final class Launcher {
    private Launcher() {}
    public static void main(String[] args) throws Exception {
        if (args.length==1 && args[0].equals("--check")) {
            AppConfig.defaults().validate();
            System.out.println("MeetScribe 0.9 beta: Java " + Runtime.version().feature()+"; configuration defaults OK.");
            System.out.println("Native models/capture are configured separately; this check does not record audio.");
            return;
        }
        if (args.length==1 && args[0].equals("--help")) {
            System.out.println("MeetScribe: no arguments = desktop; --check = headless check");
            System.out.println("Developer pipeline: --transcribe MIC.wav SYSTEM.wav OUTPUT_ROOT WHISPER_CLI MODEL [LANGUAGE]");
            return;
        }
        if ((args.length==6 || args.length==7) && args[0].equals("--transcribe")) {
            MeetingService service=new MeetingService(Path.of(args[3]),Path.of(args[4]),Path.of(args[5]),args.length==7?args[6]:"auto");
            Path dir=service.createMeeting();
            Files.copy(Path.of(args[1]),dir.resolve("mic.wav"));
            Files.copy(Path.of(args[2]),dir.resolve("system.wav"));
            service.process(dir,System.out::println);
            System.out.println("Saved: "+dir);
            return;
        }
        if (args.length!=0) throw new IllegalArgumentException("Unknown arguments; use --help");
        Path lockPath=AppConfig.location().toAbsolutePath().getParent().resolve("application.lock");
        Files.createDirectories(lockPath.getParent());
        try(var channel=java.nio.channels.FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            try(var lock=channel.tryLock()){
                if(lock==null){System.err.println("MeetScribe is already running. Open it from the system tray.");return;}
                MeetScribeApplication.launch(MeetScribeApplication.class,args);
            }
        }
    }
}
