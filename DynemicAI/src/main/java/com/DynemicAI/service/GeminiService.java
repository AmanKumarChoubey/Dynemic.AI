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

    @Value("${ai.gemini.api-key}")    private String apiKey;
    @Value("${ai.gemini.base-url}")   private String baseUrl;
    @Value("${chat.max-tokens:2048}") private int    maxTokens;
    @Value("${chat.temperature:0.7}") private double temperature;
    @Value("${chat.system-prompt}")   private String systemPrompt;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String getProviderName() { return "gemini"; }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        if (apiKey == null || apiKey.isBlank())
            return Flux.error(new IllegalStateException("Gemini API key not set."));

        // Sanitize: remove any corrupted SSE messages from history
        ChatRequest clean = sanitize(request);
        if (clean.getMessages().isEmpty())
            return Flux.error(new RuntimeException("No valid messages to process."));

        String uri = String.format("/models/%s:generateContent?key=%s", clean.getModel(), apiKey);
        log.info("Gemini → model={} messages={}", clean.getModel(), clean.getMessages().size());

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build()
                .post().uri(uri).bodyValue(buildBody(clean))
                .retrieve()
                .onStatus(s -> s.value() == 400, r -> r.bodyToMono(String.class).map(b -> { log.error("400: {}", b); return new RuntimeException("Gemini 400: " + b); }))
                .onStatus(s -> s.value() == 429, r -> r.bodyToMono(String.class).map(b -> new RuntimeException("Gemini quota exceeded. Please wait.")))
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(), r -> r.bodyToMono(String.class).map(b -> { log.error("Gemini {}: {}", r.statusCode(), b); return new RuntimeException("Gemini error " + r.statusCode()); }))
                .bodyToMono(String.class)
                .flatMapMany(full -> {
                    try {
                        String text = extractText(full);
                        if (text == null || text.isBlank()) return Flux.empty();
                        // Emit word-by-word for streaming effect
                        List<String> tokens = new ArrayList<>(Arrays.asList(text.split("(?<=\\s)|(?=\\s)")));
//                        return Flux.fromIterable(tokens).delayElements(Duration.ofMillis(20));
                        return Flux.fromIterable(tokens)
                                .filter(t -> t != null && !t.isBlank())   // prevents empty SSE
                                .delayElements(Duration.ofMillis(20));
                    } catch (Exception e) {
                        return Flux.error(new RuntimeException("Parse error: " + e.getMessage()));
                    }
                })
                .doOnError(e -> log.error("Gemini stream error: {}", e.getMessage()));
    }

    @Override
    public String chat(ChatRequest request) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Gemini API key not configured.");
        ChatRequest clean = sanitize(request);
        String uri = String.format("/models/%s:generateContent?key=%s", clean.getModel(), apiKey);
        try {
            String full = WebClient.builder().baseUrl(baseUrl).defaultHeader("Content-Type", "application/json")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)).build()
                    .post().uri(uri).bodyValue(buildBody(clean)).retrieve().bodyToMono(String.class).block();
            String text = extractText(full);
            return text != null ? text : "(empty response)";
        } catch (Exception e) { throw new RuntimeException("Gemini error: " + e.getMessage()); }
    }

    // ── Sanitize messages ─────────────────────────────────────────────────────
    private ChatRequest sanitize(ChatRequest req) {
        List<ChatRequest.Message> clean = new ArrayList<>();
        for (ChatRequest.Message m : req.getMessages()) {
            String c = m.getContent() == null ? "" : m.getContent().trim();
            boolean bad = c.isEmpty()
                    || c.startsWith("data:")
                    || c.contains("\"done\":true")
                    || c.contains("\"done\":false")
                    || c.contains("\"content\":null")
                    || c.startsWith("{\"content\":");
            if (bad) { log.warn("Skipping corrupted message: {}", c.substring(0, Math.min(60, c.length()))); continue; }
            clean.add(m);
        }
        ChatRequest r = new ChatRequest();
        r.setMessages(clean); r.setProvider(req.getProvider());
        r.setModel(req.getModel()); r.setSessionId(req.getSessionId());
        return r;
    }

    // ── Extract text ──────────────────────────────────────────────────────────
    private String extractText(String raw) throws Exception {
        if (raw == null || raw.isBlank()) return null;
        JsonNode root = objectMapper.readTree(raw.trim());
        if (root.isObject()) return textFromNode(root);
        if (root.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : root) { String t = textFromNode(n); if (t != null) sb.append(t); }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    private String textFromNode(JsonNode node) {
        JsonNode parts = node.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) return null;
        String t = parts.get(0).path("text").asText("");
        return t.isBlank() ? null : t;
    }

    // ── Build request body ────────────────────────────────────────────────────
    private Map<String, Object> buildBody(ChatRequest request) {
        Map<String, Object> body    = new HashMap<>();
        boolean             isGemma = request.getModel().toLowerCase().startsWith("gemma");

        if (!isGemma) {
            Map<String, Object> sys = new HashMap<>();
            sys.put("parts", List.of(Map.of("text", systemPrompt)));
            body.put("system_instruction", sys);
        }

        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatRequest.Message msg : request.getMessages()) {
            if ("system".equals(msg.getRole())) continue;
            String role = "assistant".equals(msg.getRole()) ? "model" : msg.getRole();
            Map<String, Object> c = new HashMap<>();
            c.put("role", role);
            c.put("parts", List.of(Map.of("text", msg.getContent())));
            contents.add(c);
        }
        body.put("contents", contents);
        body.put("generationConfig", Map.of("maxOutputTokens", maxTokens, "temperature", temperature));
        return body;
    }
}