package com.DynemicAI.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AIServiceFactory — Resolves the correct AIService implementation
 * based on the provider name (e.g. "openai", "gemini", "anthropic").
 *
 * This is the heart of the plug-and-play architecture.
 * Spring auto-injects ALL AIService beans, and the factory
 * selects the right one by matching getProviderName().
 *
 * Adding a new provider = just implement AIService. No factory changes needed.
 */

@Slf4j
@Component
public class AIServiceFactory {
    private final Map<String, AIService> services;

    public AIServiceFactory(List<AIService> aiServices) {
        this.services = aiServices.stream()
                .collect(Collectors.toMap(
                        AIService::getProviderName,
                        Function.identity()
                ));
        log.info("Registered AI providers: {}", services.keySet());
    }

    /**
     * Get the AI service for the given provider name.
     *
     * @param provider - "openai" | "gemini" | "anthropic"
     * @throws IllegalArgumentException if provider not found
     */
    public AIService getService(String provider) {
        AIService service = services.get(provider.toLowerCase());
        if (service == null) {
            throw new IllegalArgumentException(
                    "Unknown AI provider: '" + provider + "'. " +
                            "Available: " + services.keySet()
            );
        }
        return service;
    }

    public boolean isProviderSupported(String provider) {
        return services.containsKey(provider.toLowerCase());
    }

    public Set<String> getSupportedProviders() {
        return services.keySet();
    }
}
