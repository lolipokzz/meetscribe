package io.meetscribe.llm;

import java.net.URI;

/** In-memory provider settings. Never persist apiKey in configuration or logs. */
public record ProviderConfig(String id, URI baseUrl, String model, String apiKey,
                             int contextSize, boolean localOnly, boolean consent) {
    @Override public String toString() {
        return "ProviderConfig[id=" + id + ", credentials=redacted]";
    }
}
