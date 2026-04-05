package com.DynemicAI.service;

import com.DynemicAI.model.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GeminiService implements AIService {

    @Value("${ai.gemini.api-key}")
    private String apiKey;

    @Value("${ai.gemini.base-url}")
    private String baseUrl;

    @Value("${chat.max-tokens:2048}")
    private int maxTokens;

    @Value("${chat.temperature:0.7}")
    private double temperature;

    @Value("${chat.system-prompt}")
    private String systemPrompt;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderName() { return "gemini"; }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.error(new IllegalStateException("Gemini API key not set."));
        }

        Map<String, Object> body  = buildRequestBody(request);
        String              model = request.getModel();
        String              uri   = String.format("/models/%s:generateContent?key=%s", model, apiKey);

        log.info("Gemini request → model={}", model);

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build()
                .post()
                .uri(uri)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.value() == 400, response ->
                        response.bodyToMono(String.class).map(b -> {
                            log.error("Gemini 400: {}", b);
                            return new RuntimeException("Gemini 400: " + b);
                        })
                )
                .onStatus(status -> status.value() == 429, response ->
                        response.bodyToMono(String.class).map(b ->
                                new RuntimeException("Gemini quota exceeded. Please wait and try again.")
                        )
                )
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), response ->
                        response.bodyToMono(String.class).map(b -> {
                            log.error("Gemini error {}: {}", response.statusCode(), b);
                            return new RuntimeException("Gemini error " + response.statusCode());
                        })
                )
                .bodyToMono(String.class)
                .flatMapMany(fullResponse -> {
                    log.debug("Gemini full response received, length={}", fullResponse.length());
                    try {
                        String text = extractTextFromResponse(fullResponse);
                        if (text == null || text.isBlank()) return Flux.empty();

                        //FIX 2: Simulate streaming by splitting text into small chunks
                        // Split by space to emit word-by-word with a small delay
                        // This gives the streaming typing effect in the UI
                        List<String> words = new ArrayList<>(Arrays.asList(text.split("(?<=\\s)|(?=\\s)")));

                        return Flux.fromIterable(words)
                                .delayElements(Duration.ofMillis(18)); // ~55 words/sec typing speed

                    } catch (Exception e) {
                        log.error("Gemini parse error: {}", e.getMessage());
                        return Flux.error(new RuntimeException("Failed to parse Gemini response"));
                    }
                })
                .doOnError(e -> log.error("Gemini stream error: {}", e.getMessage()));
    }

    @Override
    public String chat(ChatRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key not configured.");
        }

        Map<String, Object> body  = buildRequestBody(request);
        String              model = request.getModel();
        String              uri   = String.format("/models/%s:generateContent?key=%s", model, apiKey);

        try {
            String fullResponse = WebClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("Content-Type", "application/json")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build()
                    .post()
                    .uri(uri)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String text = extractTextFromResponse(fullResponse);
            return text != null ? text : "(empty response)";
        } catch (Exception e) {
            log.error("Gemini chat error: {}", e.getMessage());
            throw new RuntimeException("Gemini API error: " + e.getMessage());
        }
    }

    private String extractTextFromResponse(String fullResponse) throws Exception {
        if (fullResponse == null || fullResponse.isBlank()) return null;
        JsonNode root = objectMapper.readTree(fullResponse.trim());

        if (root.isObject()) return extractTextFromNode(root);

        if (root.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode node : root) {
                String text = extractTextFromNode(node);
                if (text != null) sb.append(text);
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    private String extractTextFromNode(JsonNode node) {
        JsonNode candidates = node.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) return null;
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) return null;
        String text = parts.get(0).path("text").asText("");
        return text.isBlank() ? null : text;
    }

    private Map<String, Object> buildRequestBody(ChatRequest request) {
        Map<String, Object> body    = new HashMap<>();
        String              model   = request.getModel();
        boolean             isGemma = model.toLowerCase().startsWith("gemma");

        if (!isGemma) {
            List<Map<String, String>> sysParts = new ArrayList<>();
            sysParts.add(Map.of("text", systemPrompt));
            Map<String, Object> sysInstr = new HashMap<>();
            sysInstr.put("parts", sysParts);
            body.put("system_instruction", sysInstr);
        }

        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatRequest.Message msg : request.getMessages()) {
            if ("system".equals(msg.getRole())) continue;
            String role = "assistant".equals(msg.getRole()) ? "model" : msg.getRole();
            List<Map<String, String>> parts = new ArrayList<>();
            parts.add(Map.of("text", msg.getContent()));
            Map<String, Object> content = new HashMap<>();
            content.put("role", role);
            content.put("parts", parts);
            contents.add(content);
        }
        body.put("contents", contents);

        Map<String, Object> genConfig = new HashMap<>();
        genConfig.put("maxOutputTokens", maxTokens);
        genConfig.put("temperature", temperature);
        body.put("generationConfig", genConfig);

        return body;
    }
}