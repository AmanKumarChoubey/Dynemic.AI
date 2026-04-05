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
 * OpenAI GPT Service — implements AIService for OpenAI's Chat Completions API.
 * Supports streaming via SSE.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAIService implements AIService{

    @Value("${ai.openai.api-key}")
    private String apiKey;

    @Value("${ai.openai.base-url}")
    private String baseUrl;

    @Value("${chat.max-tokens:2048}")
    private int maxTokens;

    @Value("${chat.temperature:0.7}")
    private double temperature;

    @Value("${chat.system-prompt}")
    private String systemPrompt;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        ObjectNode body = buildRequestBody(request, true);

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build()
                .post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .filter(data -> !data.equals("[DONE]"))
                .mapNotNull(data -> {
                    try {
                        JsonNode json = objectMapper.readTree(data);
                        JsonNode delta = json.path("choices").path(0).path("delta");
                        if (delta.has("content")) {
                            return delta.get("content").asText();
                        }
                    } catch (Exception e) {
                        log.debug("OpenAI chunk parse error: {}", e.getMessage());
                    }
                    return null;
                })
                .doOnError(e -> log.error("OpenAI streaming error: {}", e.getMessage()));
    }

    @Override
    public String chat(ChatRequest request) {
        ObjectNode body = buildRequestBody(request, false);

        try {
            String response = WebClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .defaultHeader("Content-Type", "application/json")
                    .build()
                    .post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = objectMapper.readTree(response);
            return json.path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error("OpenAI chat error: {}", e.getMessage());
            throw new RuntimeException("OpenAI API error: " + e.getMessage());
        }
    }

    private ObjectNode buildRequestBody(ChatRequest request, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.getModel());
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", stream);

        ArrayNode messages = body.putArray("messages");

        // System message
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);

        // Conversation history
        for (ChatRequest.Message msg : request.getMessages()) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
        }

        return body;
    }

}
