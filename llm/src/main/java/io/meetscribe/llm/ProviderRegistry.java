package io.meetscribe.llm;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Provider routing; local inference is configured through SummaryEngines.local. */
public final class ProviderRegistry {
    private ProviderRegistry() {}
    public record ModelSelection(String provider, String model) {}

    /** Split only the provider separator; preserve nested model IDs and Windows/POSIX paths. */
    public static ModelSelection parseModel(String value) {
        if (value == null) throw new IllegalArgumentException("Provider/model is required");
        int slash = value.indexOf('/');
        if (slash < 1 || slash == value.length() - 1)
            throw new IllegalArgumentException("Expected provider/model");
        String provider = value.substring(0, slash).toLowerCase(Locale.ROOT);
        if (!provider.matches("[a-z][a-z0-9_-]*")) throw new IllegalArgumentException("Invalid provider ID");
        return new ModelSelection(provider, value.substring(slash + 1));
    }

    public static List<String> builtIns() {
        return List.of("local", "openai", "anthropic", "google", "openrouter", "ollama", "custom");
    }

    public static URI defaultBaseUrl(String id) {
        return switch (normalize(id)) {
            case "openai", "openai-compatible" -> URI.create("https://api.openai.com/v1");
            case "anthropic" -> URI.create("https://api.anthropic.com/v1");
            case "google", "gemini" -> URI.create("https://generativelanguage.googleapis.com/v1beta");
            case "openrouter" -> URI.create("https://openrouter.ai/api/v1");
            case "ollama" -> URI.create("http://127.0.0.1:11434/v1");
            case "local", "custom" -> null;
            default -> null;
        };
    }

    public static SummaryEngine create(ProviderConfig config) {
        return create(config, config::localOnly);
    }

    /** Policy is re-read before every completion, including model validation and map-reduce calls. */
    public static SummaryEngine create(ProviderConfig config, BooleanSupplier localOnlyPolicy) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(localOnlyPolicy);
        String id = normalize(config.id());
        if (id.equals("local")) throw new IllegalArgumentException("Local models require SummaryEngines.local(executable, model, context)");
        if (config.localOnly() || localOnlyPolicy.getAsBoolean())
            throw new IllegalStateException("HTTP summarization is disabled in local-only mode");
        URI base = config.baseUrl() == null ? defaultBaseUrl(id) : config.baseUrl();
        if (base == null) throw new IllegalArgumentException("Custom provider requires an API base URL");
        SummaryEngine delegate = switch (id) {
            case "anthropic" -> SummaryEngines.anthropic(base, config.model(), config.apiKey(), config.contextSize(), false, config.consent());
            case "google", "gemini" -> SummaryEngines.gemini(base, config.model(), config.apiKey(), config.contextSize(), false, config.consent());
            default -> SummaryEngines.openAiCompatible(base, config.model(), config.apiKey(), config.contextSize(), false, config.consent());
        };
        return new SummaryEngine() {
            public String complete(String prompt, SummaryOptions options) throws Exception {
                if (config.localOnly() || localOnlyPolicy.getAsBoolean())
                    throw new IllegalStateException("HTTP summarization is disabled in local-only mode");
                return delegate.complete(prompt, options);
            }
            public int contextSize() { return delegate.contextSize(); }
            public int countTokens(String text) { return delegate.countTokens(text); }
            public void close() throws Exception { delegate.close(); }
        };
    }

    /** Performs a real, non-sensitive request. Caller owns and must close the engine. */
    public static void validateModel(SummaryEngine engine) throws Exception {
        String answer = Objects.requireNonNull(engine).complete("Reply with the single word OK.", new SummaryOptions("en", 32));
        if (answer == null || answer.isBlank()) throw new IllegalStateException("Model returned no validation text");
    }

    private static String normalize(String id) {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]*"))
            throw new IllegalArgumentException("Invalid provider ID");
        return id.toLowerCase(Locale.ROOT);
    }
}
