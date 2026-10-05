package io.meetscribe.llm;

import java.util.Objects;

/** Generation limits; provider and model configuration belongs to the engine. */
public record SummaryOptions(String language, int maxOutputTokens) {
    public SummaryOptions {
        Objects.requireNonNull(language, "language");
        if (language.isBlank() || maxOutputTokens <= 0) {
            throw new IllegalArgumentException("Expected a language and a positive output token limit");
        }
    }
}
