package com.DynemicAI.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;


import java.util.List;

//* Request DTO sent from the frontend for a chat completion.
@Data
public class ChatRequest {
    /** Full conversation history: [{role:"user", content:"...."},....]*/
    @NotEmpty(message = "Messages cannot be empty")
    private List<Message> messages;

    /** Provider: "openai" | "gemini" | "anthropic" */
    @NotBlank(message = "Provider is required")
    private String provider;

    /** Model name e.g. "gpt-4o", "gemini-1.5-pro", "claude-3-5-sonnet" */
    @NotBlank(message = "Model is required")
    private String model;

    /** Optional session ID for chat history tracking */
    private String sessionId;

//    Nested Message
    @Data
    public static class Message{
    /** "user" | "assistant" | "system" */
    private String role;
    private String content;
   }
}
