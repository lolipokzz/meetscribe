package io.meetscribe.app.config;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.platform.win32.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** No secret values are stored in YAML, CLI arguments, logs or exception messages. */
public final class Secrets {
    private Secrets() {}
    public static boolean windows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"); }
    public static String resolve(String reference) {
        if (reference == null) throw new IllegalArgumentException("Укажите ссылку на секрет.");
        if (reference.isBlank()) return "";
        if (reference.matches("\\{env:[A-Za-z0-9_.-]+}")) {
            String value = System.getenv(reference.substring(5,reference.length()-1));
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Переменная окружения API-ключа не задана.");
            return value;
        }
        if (reference.matches("\\{credential:[A-Za-z0-9_.-]+}")) return read(reference.substring(12,reference.length()-1));
        throw new IllegalArgumentException("Используйте ссылку на env или Credential Manager.");
    }
    /** HKLM policy takes precedence over user settings; unreadable policy fails closed. */
    public static boolean enforcedLocalOnly() {
        if (Boolean.getBoolean("meetscribe.localOnly")) return true;
        if (!windows()) return false;
        String key = "SOFTWARE\\Policies\\MeetScribe";
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE,key,"LocalOnly")
                    && Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE,key,"LocalOnly") != 0;
        } catch (Win32Exception e) {
            return e.getErrorCode() != 2; // Missing policy is not a prohibition; other failures are.
        }
    }
    public interface Vault extends StdCallLibrary {
        boolean CredReadW(WString target,int type,int flags,PointerByReference credential);
        boolean CredWriteW(Credential credential,int flags);
        void CredFree(Pointer buffer);
    }
    @Structure.FieldOrder({"flags","type","target","comment","lastWritten","blobSize","blob","persist","attributeCount","attributes","alias","user"})
    public static class Credential extends Structure {
        public int flags, type; public WString target, comment;
        public WinBase.FILETIME lastWritten = new WinBase.FILETIME();
        public int blobSize; public Pointer blob; public int persist, attributeCount;
        public Pointer attributes; public WString alias, user;
        public Credential() {}
        public Credential(Pointer p) { super(p); read(); }
    }
    private static Vault vault() {
        if (!windows()) throw new IllegalStateException("Credential Manager доступен только в Windows. Используйте env.");
        return Native.load("Advapi32",Vault.class);
    }
    private static String read(String name) {
        Vault v=vault(); PointerByReference out=new PointerByReference();
        if (!v.CredReadW(new WString("MeetScribe/"+name),1,0,out))
            throw new IllegalStateException("Ключ не найден в Windows Credential Manager.");
        try {
            Credential c=new Credential(out.getValue());
            if (c.blobSize <= 0 || c.blobSize > 2560 || c.blobSize % 2 != 0 || c.blob == null) throw new IllegalStateException("Некорректный размер ключа.");
            byte[] bytes=c.blob.getByteArray(0,c.blobSize);
            try { return new String(bytes,StandardCharsets.UTF_16LE); }
            finally { Arrays.fill(bytes,(byte)0); c.blob.clear(c.blobSize); }
        } finally { v.CredFree(out.getValue()); }
    }
    public static void store(String name,String secret) {
        if (name == null || secret == null || !name.matches("[A-Za-z0-9_.-]+") || secret.isBlank()) throw new IllegalArgumentException("Укажите имя и значение ключа.");
        byte[] bytes=secret.getBytes(StandardCharsets.UTF_16LE);
        if (bytes.length > 2560) { Arrays.fill(bytes,(byte)0); throw new IllegalArgumentException("Ключ слишком длинный."); }
        try (Memory memory=new Memory(bytes.length)) {
            try {
                memory.write(0,bytes,0,bytes.length);
                Credential c=new Credential(); c.type=1; c.target=new WString("MeetScribe/"+name);
                c.user=new WString("MeetScribe"); c.persist=2; c.blobSize=bytes.length; c.blob=memory;
                if (!vault().CredWriteW(c,0)) throw new IllegalStateException("Не удалось сохранить ключ в Credential Manager.");
            } finally { memory.clear(); }
        } finally { Arrays.fill(bytes,(byte)0); }
    }
}
