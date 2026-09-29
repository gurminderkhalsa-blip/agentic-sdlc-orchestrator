package com.agentic.sdlc.llm;

import com.agentic.sdlc.config.SdlcProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

/** Calls OpenAI Chat Completions in JSON mode. Transport-level retries are left to the SDK (2 by default). */
public class OpenAiLlmClient implements LlmClient {

    private final OpenAIClient client;
    private final SdlcProperties.Llm config;

    public OpenAiLlmClient(SdlcProperties.Llm config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set. Export it, or use sdlc.llm.mode=replay");
        }
        this.config = config;
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(config.apiKey())
                .timeout(config.timeout())
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                .model(request.model())
                .addSystemMessage(request.systemPrompt())
                .addUserMessage(request.userPrompt())
                .responseFormat(ResponseFormatJsonObject.builder().build())
                .maxCompletionTokens(config.maxOutputTokens());
        if (config.reasoningEffort() != null && !config.reasoningEffort().isBlank()) {
            params.reasoningEffort(ReasoningEffort.of(config.reasoningEffort()));
        }
        try {
            ChatCompletion completion = client.chat().completions().create(params.build());
            String content = completion.choices().isEmpty() ? null
                    : completion.choices().get(0).message().content().orElse(null);
            if (content == null || content.isBlank()) {
                throw new LlmException("Model returned no content"
                        + (completion.choices().isEmpty() ? "" : " (finish reason: "
                                + completion.choices().get(0).finishReason() + ")"));
            }
            long prompt = completion.usage().map(u -> u.promptTokens()).orElse(0L);
            long output = completion.usage().map(u -> u.completionTokens()).orElse(0L);
            return new LlmResponse(content, completion.model(), prompt, output);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("OpenAI call failed: " + e.getMessage(), e);
        }
    }
}
