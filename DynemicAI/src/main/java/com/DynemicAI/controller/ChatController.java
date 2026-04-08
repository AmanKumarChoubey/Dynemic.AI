package com.DynemicAI.controller;

import com.DynemicAI.model.ChatRequest;
import com.DynemicAI.model.ChatResponse;
import com.DynemicAI.service.AIService;
import com.DynemicAI.service.AIServiceFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final AIServiceFactory aiServiceFactory;
    private final ObjectMapper     objectMapper;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest request) {
        log.info("Stream request → provider={} model={} messages={}",
                request.getProvider(), request.getModel(), request.getMessages().size());

        AIService service = aiServiceFactory.getService(request.getProvider());

        return service.streamChat(request)
                .doOnNext(chunk -> log.info("=== CONTROLLER GOT CHUNK: [{}]", chunk))
//                .map(chunk -> {
//                    //  Each chunk is a plain text string from the AI service
//                    // Wrap it in SSE format: "data: {json}\n\n"
//                    try {
//                        // Build a simple JSON manually to avoid any ObjectMapper issues
//                        String json = "{\"content\":" + objectMapper.writeValueAsString(chunk)
//                                + ",\"done\":false}";
//                        log.info("=== CONTROLLER SENDING SSE: data: {}", json);
//                        return "data: " + json + "\n\n";
//                    } catch (Exception ex) {
//                        return "data: {\"content\":" + safeJsonString(chunk) + ",\"done\":false}\n\n";
//                    }
//                })
//                //  Send done signal at the end
//                .concatWith(Flux.just("data: {\"done\":true}\n\n"))

                .map(chunk -> {
                    try {
                        String json = "{\"content\":" + objectMapper.writeValueAsString(chunk)
                                + ",\"done\":false}";
                        log.info("=== CONTROLLER SENDING SSE: {}", json);
                        return json;   //  NO "data:"
                    } catch (Exception ex) {
                        return "{\"content\":" + safeJsonString(chunk) + ",\"done\":false}";
                    }
                })
                .concatWith(Flux.just("{\"done\":true}"))
                .onErrorResume(e -> {
                    log.error("Stream error: {}", e.getMessage());
                    String safe = safeJsonString(e.getMessage());
                    return Flux.just("data: {\"error\":" + safe + ",\"done\":true}\n\n");
                });
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request → provider={} model={}", request.getProvider(), request.getModel());
        try {
            AIService service = aiServiceFactory.getService(request.getProvider());
            String content = service.chat(request);
            return ResponseEntity.ok(
                    ChatResponse.success(content, request.getModel(), request.getProvider(), request.getSessionId())
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ChatResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Chat error: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(ChatResponse.error("AI service error: " + e.getMessage()));
        }
    }

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getModels() {
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("openai", Map.of(
                "label", "OpenAI",
                "models", List.of(
                        Map.of("id", "gpt-4o",        "label", "GPT-4o"),
                        Map.of("id", "gpt-4-turbo",   "label", "GPT-4 Turbo"),
                        Map.of("id", "gpt-3.5-turbo", "label", "GPT-3.5 Turbo")
                )
        ));
        models.put("gemini", Map.of(
                "label", "Google Gemini",
                "models", List.of(
                        Map.of("id", "gemini-2.0-flash",      "label", "Gemini 2.0 Flash"),
                        Map.of("id", "gemini-2.0-flash-lite", "label", "Gemini 2.0 Flash Lite"),
                        Map.of("id", "gemini-1.5-flash",      "label", "Gemini 1.5 Flash"),
                        Map.of("id", "gemini-1.5-flash-8b",   "label", "Gemini 1.5 Flash 8B"),
                        Map.of("id", "gemma-3-27b-it",        "label", "Gemma 3 27B")
                )
        ));
        models.put("anthropic", Map.of(
                "label", "Anthropic",
                "models", List.of(
                        Map.of("id", "claude-3-5-sonnet-20241022", "label", "Claude 3.5 Sonnet"),
                        Map.of("id", "claude-3-opus-20240229",     "label", "Claude 3 Opus"),
                        Map.of("id", "claude-3-haiku-20240307",    "label", "Claude 3 Haiku")
                )
        ));
        return ResponseEntity.ok(Map.of(
                "providers", models,
                "supported", aiServiceFactory.getSupportedProviders()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "app", "DynemicAI",
                "providers", aiServiceFactory.getSupportedProviders().toString()
        ));
    }

    /** Safely escape a string for inline JSON without ObjectMapper */
    private String safeJsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }
}