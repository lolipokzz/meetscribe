package io.meetscribe.core;
import java.nio.file.Path;
public record MeetingInfo(String id, Path directory, String title, String status, String createdAt, double durationSeconds) {
    public MeetingInfo(String id, Path directory, String title, String status, String createdAt) {
        this(id, directory, title, status, createdAt, 0);
    }
}
