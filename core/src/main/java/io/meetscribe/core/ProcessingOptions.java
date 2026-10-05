package io.meetscribe.core;
import java.nio.file.Path;
import java.util.Objects;
/** User-controlled processing policy; audio is retained by default. */
public record ProcessingOptions(String speakerName, String summaryLanguage, Path template,
                                boolean deleteAudioAfterProcessing, boolean deduplicate) {
    public ProcessingOptions {
        speakerName = Objects.requireNonNull(speakerName).strip();
        summaryLanguage = Objects.requireNonNull(summaryLanguage).strip();
        if (speakerName.isEmpty() || speakerName.length() > 80 || speakerName.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Speaker name must contain 1–80 printable characters");
        if (!summaryLanguage.matches("auto|[A-Za-z]{2,3}(-[A-Za-z]{2,8})?"))
            throw new IllegalArgumentException("Summary language must be auto or a language code");
    }
    public static ProcessingOptions defaults() { return new ProcessingOptions("Я", "auto", null, false, false); }
}
