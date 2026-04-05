package com.DynemicAI.service;


import com.DynemicAI.model.ChatRequest;
import com.DynemicAI.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * AIService — The core abstraction for all LLM providers.
 *
 * Every AI provider (OpenAI, Gemini, Anthropic) implements this interface.
 * The ChatController selects the right implementation based on the
 * "provider" field in the ChatRequest — making the system fully plug-and-play.
 *
 * To add a new provider:
 *   1. Create a class implementing AIService
 *   2. Annotate with @Service("yourProviderName")
 *   3. Implement streamChat() and chat()
 *   Done! No other changes needed.
 */
public interface AIService {

    /**
     * Stream a chat completion as a Flux of text chunks.
     * Each emitted String is a partial response token / sentence.
     *
     * @param request - full chat request with history, model, provider
     * @return Flux<String> — stream of text chunks (SSE compatible)
     */
    Flux<String> streamChat(ChatRequest request);

    /**
     * Non-streaming chat completion (full response at once).
     *
     * @param request - full chat request
     * @return complete response text
     */
    String chat(ChatRequest request);

    /**
     * Provider identifier — matches the "provider" field in ChatRequest.
     * e.g. "openai", "gemini", "anthropic"
     */
    String getProviderName();
}
