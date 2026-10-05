package io.meetscribe.app.capture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.*;
import java.nio.file.*;
import java.time.Duration;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
@DisabledOnOs(OS.WINDOWS)
class AudioDevicesTest {
    @TempDir Path dir;
    Path fake(String body)throws Exception{Path p=Files.writeString(dir.resolve("devices"),"#!/bin/sh\n"+body);assertTrue(p.toFile().setExecutable(true));return p;}
    @Test void unicodeDevicesAndEmptyInventory()throws Exception{
        var devices=AudioDevices.list(fake("printf '%s' '{\"protocolVersion\":1,\"devices\":[{\"id\":\"usb-1\",\"name\":\"Микрофон\",\"flow\":\"input\",\"isDefault\":true}]}'"));
        assertEquals("Микрофон",devices.getFirst().name());assertTrue(devices.getFirst().isDefault());
        assertTrue(AudioDevices.list(fake("printf '%s' '{\"protocolVersion\":1,\"devices\":[]}'")).isEmpty());
    }
    @Test void rejectsInvalidTypesTrailingObjectsAndSecretParserOutput()throws Exception{
        for(String output:new String[]{"null","{\"protocolVersion\":\"1\",\"devices\":[]}","{\"protocolVersion\":1,\"devices\":[]} {}","{\"protocolVersion\":1,\"devices\":[{\"id\":\"a\",\"name\":\"secret-private\",\"flow\":\"input\",\"isDefault\":\"true\"}]}","secret-private"}){
            Path p=fake("printf '%s' '"+output+"'");
            var error=assertThrows(IOException.class,()->AudioDevices.list(p));assertFalse(error.toString().contains("secret-private"));assertNull(error.getCause());
        }
    }
    @Test void enumerationTimeoutKillsChildProcess()throws Exception{
        Path pid=dir.resolve("child.pid");
        Path p=fake("sleep 30 &\nprintf '%s' $! > '"+pid+"'\nwait\n");
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertThrows(IOException.class,()->AudioDevices.list(p,Duration.ofMillis(300))));
        long child=Long.parseLong(Files.readString(pid));
        for(int i=0;i<20&&ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false);i++)Thread.sleep(20);
        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    }
}
