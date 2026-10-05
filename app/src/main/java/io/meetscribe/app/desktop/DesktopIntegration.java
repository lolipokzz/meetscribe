package io.meetscribe.app.desktop;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import io.meetscribe.app.config.Secrets;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** OS integrations own no audio and dispatch all application actions through the supplied callbacks. */
public final class DesktopIntegration implements AutoCloseable {
    private TrayIcon tray;
    private volatile HotkeySession hotkeySession;
    private final java.util.function.Supplier<HotkeySession> sessionFactory;
    public DesktopIntegration(){sessionFactory=null;}
    DesktopIntegration(java.util.function.Supplier<HotkeySession> factory){sessionFactory=factory;}
    private volatile boolean closing;
    private volatile CompletableFuture<Boolean> hotkeyReady;
    public boolean installTray(Runnable show,Runnable toggle,Runnable quit,boolean english) {
        if(!SystemTray.isSupported())return false;
        try {
            BufferedImage image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=image.createGraphics();g.setColor(new Color(57,75,220));g.fillRoundRect(1,1,30,30,9,9);
            g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,21));g.drawString("M",6,24);g.dispose();
            PopupMenu menu=new PopupMenu();
            MenuItem open=new MenuItem(english?"Open MeetScribe":"Открыть MeetScribe");open.addActionListener(e->show.run());menu.add(open);
            MenuItem record=new MenuItem(english?"Start / stop recording":"Начать / остановить запись");record.addActionListener(e->toggle.run());menu.add(record);
            menu.addSeparator();MenuItem exit=new MenuItem(english?"Quit":"Выход");exit.addActionListener(e->quit.run());menu.add(exit);
            tray=new TrayIcon(image,"MeetScribe",menu);tray.setImageAutoSize(true);tray.addActionListener(e->show.run());SystemTray.getSystemTray().add(tray);
            return true;
        }catch(AWTException|RuntimeException e){return false;}
    }
    public interface Hotkeys extends StdCallLibrary {
        boolean RegisterHotKey(WinDef.HWND window,int id,int modifiers,int key);
        boolean UnregisterHotKey(WinDef.HWND window,int id);
        int GetMessageW(WinUser.MSG message,WinDef.HWND window,int min,int max);
        boolean PostThreadMessageW(int thread,int message,WinDef.WPARAM wParam,WinDef.LPARAM lParam);
        boolean PeekMessageW(WinUser.MSG message,WinDef.HWND window,int min,int max,int remove);
    }
    interface HotkeySession {
        void createQueue();
        boolean register();
        int nextMessage();
        void unregister();
        void quit();
    }
    private static final class WindowsHotkeySession implements HotkeySession {
        final Hotkeys api=Native.load("user32",Hotkeys.class);
        final int thread=Kernel32.INSTANCE.GetCurrentThreadId();
        public void createQueue(){api.PeekMessageW(new WinUser.MSG(),null,0,0,0);}
        public boolean register(){return api.RegisterHotKey(null,1,0x4000|0x0002|0x0001,0x52);}
        public int nextMessage(){WinUser.MSG msg=new WinUser.MSG();return api.GetMessageW(msg,null,0,0)>0?msg.message:-1;}
        public void unregister(){api.UnregisterHotKey(null,1);}
        public void quit(){api.PostThreadMessageW(thread,0x0012,new WinDef.WPARAM(0),new WinDef.LPARAM(0));}
    }
    /** Fixed Ctrl+Alt+R; MOD_NOREPEAT prevents repeats while held. */
    public synchronized void installHotkey(Runnable toggle,Consumer<String> warning) {
        if(closing||hotkeyReady!=null||(sessionFactory==null&&!Secrets.windows()))return;
        hotkeyReady=new CompletableFuture<>();
        Thread.ofPlatform().daemon(true).name("meetscribe-hotkey").start(()->{
            HotkeySession session=null;
            boolean registered=false;
            try {
                session=sessionFactory==null?new WindowsHotkeySession():sessionFactory.get();
                session.createQueue(); // PostThreadMessage only works after the queue exists.
                synchronized(this) {
                    if(closing){hotkeyReady.complete(false);return;}
                    registered=session.register();
                    if(registered)hotkeySession=session;
                }
                hotkeyReady.complete(registered);
                if(!registered){warning.accept("Ctrl+Alt+R занята другим приложением.");return;}
                while(!closing) {
                    int message=session.nextMessage();
                    if(message<0)break;
                    if(message==0x0312&&!closing)toggle.run();
                }
            }catch(RuntimeException|LinkageError e){
                hotkeyReady.complete(false);
                if(!closing)warning.accept("Не удалось зарегистрировать Ctrl+Alt+R.");
            }finally {
                if(registered)session.unregister();
                synchronized(this){hotkeySession=null;}
            }
        });
    }
    public void notifyUser(String text) {if(tray!=null)tray.displayMessage("MeetScribe",text,TrayIcon.MessageType.INFO);}
    public boolean hasTray(){return tray!=null;}
    @Override public synchronized void close(){
        closing=true;
        if(tray!=null){SystemTray.getSystemTray().remove(tray);tray=null;}
        if(hotkeySession!=null)hotkeySession.quit();
    }
}
