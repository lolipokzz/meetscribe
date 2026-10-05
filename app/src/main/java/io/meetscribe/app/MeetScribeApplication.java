package io.meetscribe.app;

import io.meetscribe.app.capture.ProcessCapture;
import io.meetscribe.app.capture.AudioDevices;
import io.meetscribe.app.desktop.DesktopIntegration;
import io.meetscribe.app.ui.Texts;
import io.meetscribe.app.config.*;
import io.meetscribe.core.*;
import io.meetscribe.llm.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;
import java.nio.file.*;
import java.net.URI;
import java.util.concurrent.*;

/** MVP desktop: all audio/inference work runs outside the FX thread. */
public final class MeetScribeApplication extends Application {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private AppConfig config;
    private MeetingService meetingService;
    private volatile Thread activeWorker;
    private Stage window;
    private volatile ProcessCapture capture;
    private volatile Path currentRecording;
    private final Object captureGuard=new Object();
    private Future<?> work;
    private boolean busy, closing;
    private long recordingStarted;
    private boolean micHeard,systemHeard,micWarned,systemWarned;
    private final DesktopIntegration desktop=new DesktopIntegration();
    private final ComboBox<AudioDevices.Device> micChoices=new ComboBox<>(),deviceChoices=new ComboBox<>();
    private final ComboBox<String> uiLanguage=new ComboBox<>();
    private final TextField summaryLanguage=new TextField(),speakerName=new TextField(),template=new TextField();
    private final CheckBox deleteAfter=new CheckBox("Удалять аудио после успешной обработки"),
            deduplicate=new CheckBox("Убирать повторные реплики двух дорожек"),
            remind=new CheckBox("Напоминать предупредить участников"),hotkey=new CheckBox("Горячая клавиша Ctrl+Alt+R");
    private final Label status=new Label("Готово"), timer=new Label("00:00"), privacy=new Label();
    private final ProgressBar micLevel=new ProgressBar(0), systemLevel=new ProgressBar(0);
    private final ListView<MeetingInfo> meetings=new ListView<>();
    private final TextArea transcript=new TextArea(), summary=new TextArea();
    private final Button record=new Button("● Начать запись"), stop=new Button("■ Остановить"),
            transcribe=new Button("Транскрибировать"), summarize=new Button("Саммари"),
            cancel=new Button("Отменить обработку"), open=new Button("Открыть папку"),
            delete=new Button("Удалить встречу"), deleteAudio=new Button("Удалить аудио");
    private final TabPane tabs=new TabPane();
    private Tab settingsTab;
    private final TextField output=new TextField(), capturePath=new TextField(), whisperPath=new TextField(), sttModel=new TextField(),
            language=new TextField(), microphone=new TextField(), device=new TextField(), llamaPath=new TextField(),
            llmModel=new TextField(), endpoint=new TextField(), remoteModel=new TextField(), keyRef=new TextField(), context=new TextField();
    private final ComboBox<String> mode=new ComboBox<>();
    private final CheckBox localOnly=new CheckBox("Только локально — блокировать все HTTP-провайдеры");

    @Override public void start(Stage stage) {
        window=stage; String configError=null;
        try { config=AppConfig.load(AppConfig.location()); }
        catch(Exception e) { config=AppConfig.defaults(); configError=e.getMessage()+" "+AppConfig.location(); }
        Texts.language(config.uiLanguage());
        try {TemplateStore.install();}catch(Exception e){configError="Не удалось подготовить шаблоны: "+e.getMessage();}
        Label title=new Label("MeetScribe"); title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");
        Label subtitle=new Label("Запись встречи → локальный транскрипт → саммари");
        HBox heading=new HBox(16,title,new Region(),timer); HBox.setHgrow(heading.getChildren().get(1),Priority.ALWAYS);
        micLevel.setPrefWidth(140); systemLevel.setPrefWidth(140);
        Button hide=new Button("Скрыть в трей");
        hide.setOnAction(e->{if(desktop.hasTray())stage.hide();});
        FlowPane controls=new FlowPane(10,8,record,stop,cancel,hide,new Label("Микрофон"),micLevel,new Label("Система"),systemLevel);
        VBox top=new VBox(10,heading,subtitle,privacy,controls); top.setPadding(new Insets(22));
        Tab history=new Tab("Встречи",historyPane()); history.setClosable(false);
        settingsTab=new Tab("Настройки",settingsPane()); settingsTab.setClosable(false);
        tabs.getTabs().addAll(history,settingsTab);
        BorderPane root=new BorderPane(tabs,top,null,status,null); BorderPane.setMargin(status,new Insets(12,22,16,22));
        root.setStyle("-fx-font-family: 'Segoe UI', 'Arial'; -fx-font-size: 13px; -fx-background-color: #f5f7fa;");
        Texts.localize(root);stage.setTitle("MeetScribe · 0.9 beta"); stage.setScene(new Scene(root,1100,760)); stage.setMinWidth(900); stage.setMinHeight(620);
        stage.setOnCloseRequest(e -> {
            if(!closing && desktop.hasTray()){e.consume();stage.hide();return;}
            if (!closing && (capture!=null || busy)) {
                e.consume();
                if (confirm("Закрыть MeetScribe?", "Текущая обработка будет отменена; запись будет остановлена, аудио останется на диске.")) shutdown();
            }
        });
        record.setOnAction(e -> startRecording()); stop.setOnAction(e -> stopRecording());
        cancel.setOnAction(e -> { if (activeWorker!=null) { activeWorker.interrupt(); status.setText(Texts.text("Отмена… ожидаем завершения процесса")); } });
        transcribe.setOnAction(e -> { MeetingInfo m=selected(); if(m!=null) runWork("Обработка…",() -> processMeeting(m.directory())); });
        summarize.setOnAction(e -> summarizeSelected());
        open.setOnAction(e -> { MeetingInfo m=selected(); if(m!=null) getHostServices().showDocument(m.directory().toUri().toString()); });
        delete.setOnAction(e -> removeSelected(false)); deleteAudio.setOnAction(e -> removeSelected(true));
        Timeline clock=new Timeline(new KeyFrame(Duration.seconds(1),e -> {
            if(capture!=null) { long seconds=(System.nanoTime()-recordingStarted)/1_000_000_000L; timer.setText("%02d:%02d".formatted(seconds/60,seconds%60));
                if(seconds>=60){
                    if(!micHeard&&!micWarned){micWarned=true;progress("Микрофон молчит более 60 секунд. Проверьте устройство.");}
                    if(!systemHeard&&!systemWarned){systemWarned=true;progress("Системный звук молчит более 60 секунд. Проверьте устройство.");}
                } }
        })); clock.setCycleCount(Animation.INDEFINITE); clock.play();
        Runnable show=()->Platform.runLater(()->{stage.show();stage.toFront();});
        Runnable toggle=()->Platform.runLater(()->{if(!busy){if(capture==null)startRecording();else stopRecording();}});
        boolean tray=desktop.installTray(show,toggle,()->Platform.runLater(()->{
            if(capture!=null||busy){if(confirm("Закрыть MeetScribe?","Текущая обработка будет отменена; запись будет остановлена, аудио останется на диске."))shutdown();}
            else shutdown();
        }),config.uiLanguage().equals("en"));
        hide.setDisable(!tray);
        if(tray)Platform.setImplicitExit(false);
        if(config.hotkeyEnabled())desktop.installHotkey(toggle,this::progress);
        refresh(); syncControls(); stage.show();
        if(configError!=null) error(configError);
    }
    private javafx.scene.Node historyPane() {
        meetings.setPrefWidth(260);
        meetings.setCellFactory(v -> new ListCell<>() { @Override protected void updateItem(MeetingInfo item,boolean empty) {
            super.updateItem(item,empty); setText(empty||item==null?null:item.title()+"\n"+Texts.state(item.status())+" · "+"%02d:%02d".formatted((int)item.durationSeconds()/60,(int)item.durationSeconds()%60));
        }});
        meetings.getSelectionModel().selectedItemProperty().addListener((o,old,m) -> { showMeeting(m); syncControls(); });
        transcript.setEditable(false); transcript.setWrapText(true); summary.setEditable(false); summary.setWrapText(true);
        transcript.setPromptText("Транскрипт появится после обработки записи."); summary.setPromptText("Выберите модель в настройках и нажмите «Саммари».");
        Label reminder=new Label("Проверьте решения и задачи по транскрипту: саммари может содержать ошибки.");
        reminder.setWrapText(true); reminder.setPadding(new Insets(10));
        BorderPane summaryPane=new BorderPane(summary,null,null,reminder,null);
        Tab t=new Tab("Транскрипт",transcript),s=new Tab("Саммари",summaryPane); t.setClosable(false);s.setClosable(false);
        TabPane content=new TabPane(t,s);
        Button resume=new Button("Возобновить обработку");
        resume.setOnAction(e->{MeetingInfo m=selected();if(m!=null&&capture==null&&!busy)runWork("Обработка…",()->service().resume(m.directory(),this::progress));});
        FlowPane actions=new FlowPane(8,8,transcribe,resume,summarize,open,deleteAudio,delete); actions.setPadding(new Insets(10));
        BorderPane detail=new BorderPane(content,null,null,actions,null);
        SplitPane split=new SplitPane(meetings,detail); split.setDividerPositions(.25); return split;
    }
    private javafx.scene.Node settingsPane() {
        GridPane grid=new GridPane();grid.setHgap(14);grid.setVgap(10);grid.setPadding(new Insets(22));
        ColumnConstraints labelCol=new ColumnConstraints(220),valueCol=new ColumnConstraints();valueCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol,valueCol);
        int i=0;
        i=field(grid,i,"Папка встреч",output,true,true); i=field(grid,i,"Capture.exe (Windows)",capturePath,true,false);
        i=field(grid,i,"whisper-cli",whisperPath,true,false);i=field(grid,i,"STT-модель ggml",sttModel,true,false);
        i=field(grid,i,"Язык (auto / ru / en)",language,false,false);
        grid.add(new Label("Микрофон записи"),0,i);grid.add(micChoices,1,i++);
        grid.add(new Label("Устройство вывода"),0,i);grid.add(deviceChoices,1,i++);
        micChoices.setMaxWidth(Double.MAX_VALUE);deviceChoices.setMaxWidth(Double.MAX_VALUE);
        Button refreshDevices=new Button("Обновить устройства");refreshDevices.setOnAction(e->refreshDevices());grid.add(refreshDevices,1,i++);
        mode.getItems().addAll("none","local","openai","anthropic","google","openrouter","ollama","custom","openai-compatible");
        mode.valueProperty().addListener((o,old,value)->{
            if(value!=null&&!java.util.Set.of("none","local","custom","openai-compatible").contains(value)){
                endpoint.setText(ProviderRegistry.defaultBaseUrl(value).toString());
                keyRef.setText(value.equals("ollama")?"":"{env:"+value.toUpperCase(java.util.Locale.ROOT)+"_API_KEY}");
            }
        });grid.add(new Label("Саммари"),0,i);grid.add(mode,1,i++);
        grid.add(localOnly,1,i++);
        i=field(grid,i,"llama-completion",llamaPath,true,false);i=field(grid,i,"LLM-модель GGUF",llmModel,true,false);
        i=field(grid,i,"API base URL (…/v1)",endpoint,false,false);i=field(grid,i,"API model",remoteModel,false,false);
        i=field(grid,i,"Ключ: {env:…}/{credential:…}",keyRef,false,false);i=field(grid,i,"Контекст LLM (токены)",context,false,false);
        i=field(grid,i,"Язык саммари (auto / ru / en)",summaryLanguage,false,false);
        i=field(grid,i,"Ваше имя в транскрипте",speakerName,false,false);
        i=field(grid,i,"Шаблон саммари (.md)",template,true,false);
        Button templates=new Button("Открыть шаблоны");templates.setOnAction(e->getHostServices().showDocument(TemplateStore.directory().toUri().toString()));grid.add(templates,1,i++);
        uiLanguage.getItems().addAll("ru","en");grid.add(new Label("Язык интерфейса"),0,i);grid.add(uiLanguage,1,i++);
        for(CheckBox option:new CheckBox[]{deleteAfter,deduplicate,remind,hotkey})grid.add(option,1,i++);
        Button validateModel=new Button("Проверить модель");validateModel.setOnAction(e->testOrSaveSettings(false));grid.add(validateModel,1,i++);
        PasswordField key=new PasswordField();key.setPromptText("Новый ключ — только Windows Credential Manager");
        Button storeKey=new Button("Сохранить ключ");storeKey.setDisable(!Secrets.windows());
        storeKey.setOnAction(e -> {
            try {
                String ref=keyRef.getText().trim();
                if(!ref.matches("\\{credential:[A-Za-z0-9_.-]+}")) throw new IllegalArgumentException("Задайте ссылку {credential:имя}.");
                Secrets.store(ref.substring(12,ref.length()-1),key.getText()); key.clear();status.setText("Ключ сохранён в Windows Credential Manager.");
            } catch(Exception ex) { key.clear();error(ex.getMessage()); }
        });
        grid.add(new HBox(10,key,storeKey),1,i++);
        Label note=new Label(config.uiLanguage().equals("en")?"Bundled STT works offline. LLM models are optional and supplied manually.\nLocal-only blocks Ollama HTTP as well; use llama-completion for fully local summaries.":"Встроенный STT работает без интернета. Модель саммари необязательна и добавляется вручную.\nВ режиме только локально Ollama HTTP тоже заблокирован; используйте llama-completion.");note.setWrapText(true);grid.add(note,1,i++);
        Button save=new Button("Сохранить настройки");save.setOnAction(e -> testOrSaveSettings(true));grid.add(save,1,i);
        fillSettings();ScrollPane scroll=new ScrollPane(grid);scroll.setFitToWidth(true);return scroll;
    }
    private int field(GridPane grid,int row,String label,TextField input,boolean chooser,boolean directory) {
        grid.add(new Label(label),0,row); input.setMaxWidth(Double.MAX_VALUE);
        if(chooser) {
            Button browse=new Button("…");browse.setOnAction(e -> {
                java.io.File file;
                if(directory) file=new DirectoryChooser().showDialog(window); else file=new FileChooser().showOpenDialog(window);
                if(file!=null) input.setText(file.toPath().toString());
            });
            HBox box=new HBox(8,input,browse);HBox.setHgrow(input,Priority.ALWAYS);grid.add(box,1,row);
        } else grid.add(input,1,row);
        return row+1;
    }
    private void fillSettings() {
        output.setText(config.output().toString());capturePath.setText(config.capture());whisperPath.setText(config.whisper());sttModel.setText(config.sttModel());
        language.setText(config.language());microphone.setText(config.microphone());device.setText(config.outputDevice());mode.setValue(config.summaryMode());
        llamaPath.setText(config.llama());llmModel.setText(config.llmModel());endpoint.setText(config.endpoint());remoteModel.setText(config.remoteModel());keyRef.setText(config.keyReference());
        summaryLanguage.setText(config.summaryLanguage());speakerName.setText(config.speakerName());template.setText(config.template());
        uiLanguage.setValue(config.uiLanguage());deleteAfter.setSelected(config.deleteAudioAfterProcessing());deduplicate.setSelected(config.deduplicate());
        remind.setSelected(config.remindBeforeRecording());hotkey.setSelected(config.hotkeyEnabled());
        setDevices(java.util.List.of());
        context.setText(Integer.toString(config.contextSize()));localOnly.setSelected(config.localOnly()||Secrets.enforcedLocalOnly());localOnly.setDisable(Secrets.enforcedLocalOnly());
    }
    private AppConfig editedConfig() {
        AudioDevices.Device mic=micChoices.getValue(),out=deviceChoices.getValue();
        return new AppConfig(Path.of(output.getText().trim()),capturePath.getText().trim(),whisperPath.getText().trim(),sttModel.getText().trim(),
                language.getText().trim(),mic==null?"default":mic.id(),out==null?"default":out.id(),mode.getValue(),llamaPath.getText().trim(),llmModel.getText().trim(),
                endpoint.getText().trim(),remoteModel.getText().trim(),keyRef.getText().trim(),Integer.parseInt(context.getText().trim()),localOnly.isSelected()||Secrets.enforcedLocalOnly(),
                uiLanguage.getValue(),summaryLanguage.getText().trim(),speakerName.getText().trim(),template.getText().trim(),
                deleteAfter.isSelected(),deduplicate.isSelected(),remind.isSelected(),hotkey.isSelected());
    }
    private void testOrSaveSettings(boolean save) {
        final AppConfig next;
        try {next=editedConfig();next.validate();}
        catch(Exception e){error(e.getMessage());return;}
        boolean remote=!java.util.Set.of("none","local").contains(next.summaryMode());
        boolean blocked=remote&&(next.localOnly()||Secrets.enforcedLocalOnly());
        if(blocked&&!save){error("HTTP-саммари заблокировано режимом «только локально».");return;}
        if(remote&&!blocked&&!confirm("Сохранить и проверить модель?",Texts.text("Будет отправлен только технический тест без текста встречи. Адрес: ")+next.endpoint()))return;
        if(!save&&next.summaryMode().equals("none")){error("Провайдер саммари отключён.");return;}
        runWork("Проверка модели…",()->{
            for(String path:new String[]{next.capture(),next.whisper(),next.sttModel()})
                if(!path.isBlank()&&!Files.isRegularFile(Path.of(path)))throw new java.io.IOException("Файл не найден: "+path);
            if(!next.summaryMode().equals("none")&&!blocked){
                try(SummaryEngine engine=summaryEngine(next,true)){ProviderRegistry.validateModel(engine);}
            }
            if(save){
                next.save(AppConfig.location());
                Platform.runLater(()->{config=next;meetingService=null;refresh();syncControls();status.setText(Texts.text("Настройки сохранены. Язык и горячая клавиша изменятся после перезапуска."));});
            }else progress("Модель доступна.");
        });
    }
    private void refreshDevices(){
        Path helper=Path.of(capturePath.getText().trim());
        runWork("Получение списка устройств…",()->{
            var list=AudioDevices.list(helper);Platform.runLater(()->{setDevices(list);status.setText(Texts.text("Список устройств обновлён."));});
        });
    }
    private void setDevices(java.util.List<AudioDevices.Device> available){
        String mic=micChoices.getValue()==null?config.microphone():micChoices.getValue().id();
        String out=deviceChoices.getValue()==null?config.outputDevice():deviceChoices.getValue().id();
        fillDevices(micChoices,available,"input",mic);fillDevices(deviceChoices,available,"output",out);
    }
    private void fillDevices(ComboBox<AudioDevices.Device> box,java.util.List<AudioDevices.Device> devices,String flow,String selected){
        box.getItems().setAll(new AudioDevices.Device("default",Texts.text("По умолчанию Windows"),flow,false));
        devices.stream().filter(d->d.flow().equals(flow)).forEach(d->box.getItems().add(d));
        AudioDevices.Device chosen=box.getItems().stream().filter(d->d.id().equals(selected)).findFirst().orElse(null);
        if(chosen==null){chosen=new AudioDevices.Device(selected,selected,flow,false);box.getItems().add(chosen);}box.setValue(chosen);
    }
    private String apiModel(AppConfig c){
        String name=c.remoteModel();String prefix=c.summaryMode()+"/";
        return name.startsWith(prefix)?name.substring(prefix.length()):name;
    }
    private SummaryEngine summaryEngine(AppConfig c,boolean consent) throws Exception {
        if(c.summaryMode().equals("local")){
            Path model=Path.of(c.llmModel());
            if(!Files.isRegularFile(model))throw new java.io.IOException("Не найден GGUF-файл модели.");
            long estimate=(long)(Files.size(model)*1.2)+256L*1024*1024;
            var os=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if(os.getFreeMemorySize()>0 && estimate>os.getFreeMemorySize())throw new java.io.IOException("Недостаточно свободной RAM: оценка модели "+estimate/(1024*1024)+" MiB.");
            return SummaryEngines.local(Path.of(c.llama()),model,c.contextSize());
        }
        return ProviderRegistry.create(new ProviderConfig(c.summaryMode(),URI.create(c.endpoint()),apiModel(c),Secrets.resolve(c.keyReference()),c.contextSize(),c.localOnly()||Secrets.enforcedLocalOnly(),consent),()->c.localOnly()||Secrets.enforcedLocalOnly());
    }
    private synchronized MeetingService service() {
        if(meetingService==null) meetingService=new MeetingService(config.output(),Path.of(config.whisper()),Path.of(config.sttModel()),config.language(),new ProcessingOptions(config.speakerName(),config.summaryLanguage(),TemplateStore.selected(config.template()),config.deleteAudioAfterProcessing(),config.deduplicate()));
        return meetingService;
    }
    private void startRecording() {
        if(!Secrets.windows()) { error("Захват WASAPI доступен в Windows 10/11 x64. Здесь можно работать с историей и инженерным CLI.");return; }
        if(config.remindBeforeRecording()&&!confirm("Начать запись?","Предупредите участников встречи о записи. Аудио будет сохранено только на этом компьютере."))return;
        runWork("Запуск аудиозахвата…",() -> {
            Path recording=service().createMeeting();
            currentRecording=recording;
            service().markRecording(recording);
            java.util.concurrent.atomic.AtomicBoolean captureFailed=new java.util.concurrent.atomic.AtomicBoolean();
            try { ProcessCapture created=new ProcessCapture(Path.of(config.capture()),recording,config.microphone(),config.outputDevice(),
                    (mic,system) -> Platform.runLater(() -> {micLevel.setProgress(mic);systemLevel.setProgress(system);if(mic>0.001)micHeard=true;if(system>0.001)systemHeard=true;}),
                    message -> {
                        synchronized(captureGuard){captureFailed.set(true);if(recording.equals(currentRecording))capture=null;}
                        try {service().markCaptureFailed(recording);} catch(Exception ignored) { }
                        Platform.runLater(() -> {if(recording.equals(currentRecording)){status.setText(Texts.text(message));refresh();syncControls();}});
                    });
                synchronized(captureGuard){
                    if(captureFailed.get()||closing){created.close();throw new java.io.IOException("Запись не началась.");}
                    capture=created;
                }
                created.onInformation(this::progress);
                created.onLimitReached(()->Platform.runLater(()->{if(capture==created)stopRecording();}));
            } catch(Exception ex) {service().markCaptureFailed(recording);throw ex;}
            micHeard=systemHeard=micWarned=systemWarned=false;recordingStarted=System.nanoTime();progress("Идёт запись. Аудио сохраняется на диск.");
        });
    }
    private void processMeeting(Path directory) throws Exception {
        service().process(directory,this::progress);
        if(config.deleteAudioAfterProcessing()&&config.summaryMode().equals("none"))service().deleteMeeting(directory,true);
    }
    private void stopRecording() {
        ProcessCapture active=capture;Path dir=currentRecording;if(active==null)return;
        runWork("Остановка записи…",() -> {
            try {active.stop();service().markRecorded(dir);}
            catch(Exception e){service().markCaptureFailed(dir);throw e;} finally {capture=null;}
            if(!config.whisper().isBlank()&&!config.sttModel().isBlank()) processMeeting(dir);
            else progress("Аудио сохранено. Укажите whisper-cli и модель, затем выберите «Транскрибировать».");
        },()->{
            if(!config.summaryMode().equals("none")&&Files.isRegularFile(dir.resolve("transcript.md"))){
                meetings.getItems().stream().filter(m->m.directory().equals(dir)).findFirst().ifPresent(m->meetings.getSelectionModel().select(m));
                summarizeSelected();
            }
        });
    }
    private void summarizeSelected() {
        MeetingInfo m=selected();if(m==null)return;
        boolean remote=!java.util.Set.of("none","local").contains(config.summaryMode());
        if(remote && (config.localOnly()||Secrets.enforcedLocalOnly())) {error("HTTP-саммари заблокировано режимом «только локально».");return;}
        if(remote && !confirm("Передать текст провайдеру?","Текст выбранного транскрипта будет отправлен на "+config.endpoint()+"\nМодель: "+config.remoteModel()+"\nАудио не отправляется.")) return;
        runWork("Создание саммари…",() -> {
            SummaryEngine engine=summaryEngine(config,true);
            service().summarize(m.directory(),engine,remote?config.endpoint():"local/llama.cpp",remote?apiModel(config):config.llmModel(),this::progress);
        });
    }
    private void removeSelected(boolean audioOnly) {
        MeetingInfo m=selected();if(m==null)return;
        if(!confirm(audioOnly?"Удалить аудио?":"Удалить встречу?",audioOnly?"Дорожки будут удалены безвозвратно; транскрипт и саммари останутся.":"Все файлы выбранной встречи будут удалены безвозвратно."))return;
        runWork("Удаление…",() -> service().deleteMeeting(m.directory(),audioOnly));
    }
    private MeetingInfo selected() {return meetings.getSelectionModel().getSelectedItem();}
    private void refresh() {
        String id=selected()==null?null:selected().id();
        try {
            meetings.getItems().setAll(service().listMeetings());
            if(id!=null) meetings.getItems().stream().filter(m->m.id().equals(id)).findFirst().ifPresent(m->meetings.getSelectionModel().select(m));
            if(selected()==null&&!meetings.getItems().isEmpty())meetings.getSelectionModel().selectFirst();
            showMeeting(selected());
        }catch(Exception e){status.setText("Не удалось прочитать историю: "+e.getMessage());}
    }
    private void showMeeting(MeetingInfo m) {
        transcript.setText(m==null?"":readResult(m.directory().resolve("transcript.md")));
        summary.setText(m==null?"":readResult(m.directory().resolve("summary.md")));
    }
    private String readResult(Path path) {
        try {if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return "";if(Files.size(path)>4*1024*1024)return "Файл слишком большой для просмотра. Откройте папку встречи.";return Files.readString(path);}
        catch(Exception e){return "Не удалось прочитать файл.";}
    }
    private void syncControls() {
        boolean recording=capture!=null,has=selected()!=null;
        record.setDisable(busy||recording);stop.setDisable(busy||!recording);cancel.setDisable(!busy||recording);
        transcribe.setDisable(busy||recording||!has);summarize.setDisable(busy||recording||!has||config.summaryMode().equals("none")||(!config.summaryMode().equals("local")&&(config.localOnly()||Secrets.enforcedLocalOnly())));
        delete.setDisable(busy||recording||!has);deleteAudio.setDisable(busy||recording||!has);open.setDisable(!has);
        settingsTab.setDisable(busy||recording);
        privacy.setText(Texts.text(config.localOnly()||Secrets.enforcedLocalOnly()?"🔒 Только локальная обработка":"Аудио локально · отправка текста только после подтверждения"));
    }
    @FunctionalInterface private interface Work {void run() throws Exception;}
    private void runWork(String message,Work action) {runWork(message,action,null);}
    private void runWork(String message,Work action,Runnable success) {
        if(busy||closing)return;busy=true;status.setText(Texts.text(message));syncControls();
        work=worker.submit(() -> {
            activeWorker=Thread.currentThread();
            boolean succeeded=false;
            try {action.run();succeeded=true;}
            catch(InterruptedException e){Thread.currentThread().interrupt();progress("Обработка отменена. Сохранённые данные остаются в истории.");}
            catch(Exception e){Platform.runLater(()->error(e.getMessage()==null?"Операция не выполнена.":e.getMessage()));}
            finally {activeWorker=null;boolean done=succeeded;Platform.runLater(()->{busy=false;refresh();syncControls();if(done&&success!=null&&!closing)success.run();});}
        });
    }
    private void progress(String text) {Platform.runLater(()->status.setText(Texts.text(text)));}
    private boolean confirm(String title,String text) {Alert a=new Alert(Alert.AlertType.CONFIRMATION,Texts.text(text),ButtonType.OK,ButtonType.CANCEL);a.initOwner(window);a.setTitle(Texts.text(title));a.setHeaderText(Texts.text(title));return a.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;}
    private void error(String text){text=Texts.text(text);status.setText(text);Alert a=new Alert(Alert.AlertType.ERROR,text,ButtonType.OK);a.initOwner(window);a.setHeaderText("MeetScribe");a.show();}
    private void shutdown() {
        closing=true;desktop.close();if(work!=null)work.cancel(true);
        Thread.ofVirtual().start(()->{
            worker.shutdownNow();
            try {worker.awaitTermination(25,TimeUnit.SECONDS);} catch(InterruptedException e){Thread.currentThread().interrupt();}
            ProcessCapture active;Path recording;
            synchronized(captureGuard){active=capture;recording=currentRecording;capture=null;}
            if(active!=null) {
                try {active.stop();if(recording!=null)service().markRecorded(recording);}
                catch(Exception e){try{if(recording!=null)service().markCaptureFailed(recording);}catch(Exception ignored){}}
            }
            Platform.runLater(()->{window.close();Platform.exit();});
        });
    }
    @Override public void stop(){desktop.close();if(work!=null)work.cancel(true);ProcessCapture active=capture;if(active!=null)active.close();worker.shutdownNow();}
}
