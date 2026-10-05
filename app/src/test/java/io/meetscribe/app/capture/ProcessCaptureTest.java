package io.meetscribe.app.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class ProcessCaptureTest {
    @TempDir Path dir;
    private Path executable(String script) throws Exception {
        Path file = Files.writeString(dir.resolve("fake-capture"), "#!/bin/sh\n" + script);
        assertTrue(file.toFile().setExecutable(true)); return file;
    }
    private ProcessCapture capture(Path executable, AtomicReference<String> error) throws Exception {
        return new ProcessCapture(executable, dir, "default", "default", (m,s) -> {}, error::set, Duration.ofSeconds(2));
    }
    @Test void validatesStartedLevelsAndStopHandshake() throws Exception {
        Path command = dir.resolve("command.txt");
        Path executable = executable("printf '%s\\n' '{\"protocolVersion\":1,\"event\":\"ready\"}' '{\"protocolVersion\":1,\"event\":\"started\"}' '{\"protocolVersion\":1,\"event\":\"levels\",\"mic\":0.5,\"system\":0.25}'\nread -r command\nprintf '%s' \"$command\" > '" + command + "'\nprintf '%s\\n' '{\"protocolVersion\":1,\"event\":\"stopped\"}'\n");
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<Double> mic = new AtomicReference<>();
        try (ProcessCapture capture = new ProcessCapture(executable, dir, "default", "default", (m,s) -> mic.set(m), error::set, Duration.ofSeconds(3))) {
            capture.stop(); capture.stop();
        }
        assertEquals(0.5, mic.get()); assertNull(error.get());
        assertEquals("{\"protocolVersion\":1,\"command\":\"stop\"}", Files.readString(command));
    }
    @Test void malformedNativeOutputNeverLeaksParserSource() throws Exception {
        Path executable = executable("printf '%s\\n' '{secret-private-value'\n");
        AtomicReference<String> error = new AtomicReference<>();
        IOException failure = assertThrows(IOException.class, () -> capture(executable, error));
        assertFalse(failure.toString().contains("secret-private-value")); assertNull(failure.getCause());
    }
    @Test void rejectsOversizeProtocolLinesBeforeUnboundedAllocation() throws Exception {
        Path executable = executable("awk 'BEGIN {for (i=0;i<70000;i++) printf \"x\"; printf \"\\n\"}'\n");
        assertThrows(IOException.class, () -> capture(executable, new AtomicReference<>()));
    }
    @Test void startupTimeoutAndStopTimeoutAreBounded() throws Exception {
        Path executable = executable("sleep 30\n");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class, () -> capture(executable, new AtomicReference<>())));
        Path blockingStop = executable("printf '%s\\n' '{\"protocolVersion\":1,\"event\":\"ready\"}' '{\"protocolVersion\":1,\"event\":\"started\"}'\nread -r command\nsleep 30\n");
        try (ProcessCapture capture = capture(blockingStop, new AtomicReference<>())) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class, capture::stop));
        }
    }
    @Test void requiresStoppedEventAndZeroExit() throws Exception {
        Path executable = executable("printf '%s\\n' '{\"protocolVersion\":1,\"event\":\"ready\"}' '{\"protocolVersion\":1,\"event\":\"started\"}'\nread -r command\nexit 1\n");
        try (ProcessCapture capture = capture(executable, new AtomicReference<>())) { assertThrows(IOException.class, capture::stop); }
    }
    @Test void interruptionPreservesCancellationSignal() throws Exception {
        Path executable = executable("sleep 30\n");
        AtomicBoolean preserved = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try { capture(executable, new AtomicReference<>()); }
            catch (Exception e) { preserved.set(Thread.currentThread().isInterrupted()); }
        });
        worker.join(3000); assertFalse(worker.isAlive()); assertTrue(preserved.get());
    }
    @Test void nativeLimitStopIsAcceptedAndReplayedToLateListener() throws Exception {
        Path executable = executable("printf '%s\\n' '{\"protocolVersion\":1,\"event\":\"ready\"}' '{\"protocolVersion\":1,\"event\":\"started\"}' '{\"protocolVersion\":1,\"event\":\"deviceChanged\",\"track\":\"mic\",\"status\":\"reconnecting\"}' '{\"protocolVersion\":1,\"event\":\"deviceChanged\",\"track\":\"mic\",\"status\":\"connected\"}' '{\"protocolVersion\":1,\"event\":\"limitReached\",\"seconds\":14400}' '{\"protocolVersion\":1,\"event\":\"stopped\"}'\n");
        AtomicReference<String> error = new AtomicReference<>();
        try (ProcessCapture capture = capture(executable,error)) {
            for(int i=0;i<100&&capture.isRunning();i++)Thread.sleep(10);
            CountDownLatch callback = new CountDownLatch(1);
            capture.onLimitReached(callback::countDown);
            assertTrue(callback.await(2,TimeUnit.SECONDS));
            capture.stop(); assertNull(error.get());
        }
    }
    @Test void rejectsTrailingJsonInProtocolLine() throws Exception {
        Path executable=executable("printf '%s\\n' '{\"protocolVersion\":1,\"event\":\"ready\"} {}'\n");
        assertThrows(IOException.class,()->capture(executable,new AtomicReference<>()));
    }

}
