package io.meetscribe.app.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class AudioDevices {
    private AudioDevices() {}
    public record Device(String id,String name,String flow,boolean isDefault) {
        @Override public String toString(){return name+(isDefault?" ★":"");}
    }
    public static List<Device> list(Path executable) throws Exception { return list(executable,java.time.Duration.ofSeconds(10)); }
    static List<Device> list(Path executable,java.time.Duration timeout) throws Exception {
        if(timeout.isNegative()||timeout.isZero())throw new IllegalArgumentException("Invalid device enumeration timeout");
        if(!Files.isRegularFile(executable))throw new IOException("Не найден capture.exe.");
        Process p=new ProcessBuilder(executable.toAbsolutePath().toString(),"--list-devices")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        ExecutorService reader=Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<byte[]> bytes=reader.submit(()->p.getInputStream().readNBytes(1024*1024+1));
            if(!p.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)||p.exitValue()!=0)throw new IOException("Не удалось получить список устройств.");
            byte[] response=bytes.get(2,TimeUnit.SECONDS);
            if(response.length>1024*1024)throw new IOException("Некорректный список устройств.");
            var root=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response);
            if(root==null||!root.isObject()||!root.path("protocolVersion").isIntegralNumber()
                    ||!root.path("protocolVersion").canConvertToInt()||root.path("protocolVersion").intValue()!=1||!root.path("devices").isArray())throw new IOException();
            List<Device> out=new ArrayList<>();
            for(var d:root.path("devices")) {
                String flow=d.path("flow").asText();
                if(!d.path("id").isTextual()||d.path("id").asText().isBlank()||!d.path("name").isTextual()
                        ||!d.path("isDefault").isBoolean()||!Set.of("input","output").contains(flow))throw new IOException();
                out.add(new Device(d.path("id").asText(),d.path("name").asText(),flow,d.path("isDefault").asBoolean()));
            }
            return List.copyOf(out);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        catch(Exception e){throw new IOException("Не удалось получить список устройств. Проверьте capture.exe и Windows Audio.");}
        finally {
            p.descendants().forEach(ProcessHandle::destroyForcibly);p.destroyForcibly();
            try {p.getInputStream().close();p.getOutputStream().close();}catch(IOException ignored){}
            reader.shutdownNow();
        }
    }
}
