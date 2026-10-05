package io.meetscribe.llm;

/** Text-only summarization contract; this module must not accept or upload audio. */
public interface SummaryEngine extends AutoCloseable {
    String complete(String prompt, SummaryOptions options) throws Exception;

    /** Total model context capacity in tokens, including input and output. */
    int contextSize();

    /** Conservative estimate for budgeting; adapters need not implement the model tokenizer. */
    int countTokens(String text);

    @Override
    void close() throws Exception;
}
