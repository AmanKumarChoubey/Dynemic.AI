package com.DynemicAI.service;

import com.DynemicAI.model.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Anthropic Claude Service — implements AIService for Anthropic's Messages API.
 * Supports streaming via SSE with the "content_block_delta" event type.
 *
 * API Docs: https://docs.anthropic.com/en/api/messages-streaming
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class AnthropicService implements AIService{
    @Value("${ai.anthropic.api-key}")
    private String apiKey;

    @Value("${ai.anthropic.base-url}")
    private String baseUrl;

    @Value("${ai.anthropic.version:2023-06-01}")
    private String anthropicVersion;

    @Value("${chat.max-tokens:2048}")
    private int maxTokens;

    @Value("${chat.system-prompt}")
    private String systemPrompt;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        ObjectNode body = buildRequestBody(request, true);

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", anthropicVersion)
                .defaultHeader("Content-Type", "application/json")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build()
                .post()
                .uri("/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .mapNotNull(data -> {
                    try {
                        JsonNode json = objectMapper.readTree(data);
                        String type = json.path("type").asText();
                        // Only "content_block_delta" events contain text
                        if ("content_block_delta".equals(type)) {
                            return json.path("delta").path("text").asText("");
                        }
                    } catch (Exception e) {
                        log.debug("Anthropic chunk parse error: {}", e.getMessage());
                    }
                    return null;
                })
                .filter(text -> !text.isEmpty())
                .doOnError(e -> log.error("Anthropic streaming error: {}", e.getMessage()));
    }

    @Override
    public String chat(ChatRequest request) {
        ObjectNode body = buildRequestBody(request, false);

        try {
            String response = WebClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("x-api-key", apiKey)
                    .defaultHeader("anthropic-version", anthropicVersion)
                    .defaultHeader("Content-Type", "application/json")
                    .build()
                    .post()
                    .uri("/messages")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = objectMapper.readTree(response);
            return json.path("content").path(0).path("text").asText();
        } catch (Exception e) {
            log.error("Anthropic chat error: {}", e.getMessage());
            throw new RuntimeException("Anthropic API error: " + e.getMessage());
        }
    }

    private ObjectNode buildRequestBody(ChatRequest request, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.getModel());
        body.put("max_tokens", maxTokens);
        body.put("stream", stream);
        body.put("system", systemPrompt);

        // Anthropic only supports "user" and "assistant" roles
        // Filter out any system messages (already handled above)
        ArrayNode messages = body.putArray("messages");
        for (ChatRequest.Message msg : request.getMessages()) {
            if ("system".equals(msg.getRole())) continue;
            ObjectNode m = messages.addObject();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
        }

        return body;
    }
}
