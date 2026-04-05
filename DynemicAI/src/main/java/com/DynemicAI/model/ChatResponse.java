package com.DynemicAI.model;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * Non-streaming response DTO (used by /api/chat endpoint).
 * Streaming uses SSE chunks instead.
 */

/**
 * ChatResponse DTO
 *
 * ROOT CAUSE of content=null in streaming:
 * The @Builder pattern requires ALL fields to be set explicitly.
 * If chunk() only sets "content" but the other fields default to null,
 * Jackson serializes them as null too — which is correct.
 * BUT if chunk() itself wasn't setting content properly, you get null.
 *
 * This fixed version ensures chunk() always sets content correctly.
 * Jackson is the default and most widely used library for processing JSON data.
 * It automatically handles the conversion between Java objects (POJOs) and JSON format,
 * a process known as data binding, which is essential for building RESTful APIs.
 */

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String content;
    private String model;
    private String provider;
    private String sessionId;
    private boolean done;
    private String error;

    // ****Static factory methods****

    /** Full success response (non-streaming) */
    public static ChatResponse success(String content, String model, String provider, String sessionId) {
        return ChatResponse.builder()
                .content(content)
                .model(model)
                .provider(provider)
                .sessionId(sessionId)
                .done(true)
                .error(null)
                .build();
    }
//Error Response
    public static ChatResponse error(String message){
        return ChatResponse.builder().
                error(message).
                done(true).
                build();
    }

    /**
     *Streaming chunk — carries partial text content.
     * This is what gets sent for each SSE event during streaming.
     * "done" must be FALSE so frontend knows more chunks are coming.
     */
    public static ChatResponse chunk(String content) {
        return ChatResponse.builder()
                .content(content)   // ← the actual text chunk
                .done(false)        // ← still streaming, not finished
                .build();
    }
    /** Final SSE signal — tells frontend the stream is complete */
    public static ChatResponse done(){
        return ChatResponse.builder().done(true).build();
    }
}
