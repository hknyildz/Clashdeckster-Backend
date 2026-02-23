package com.deftgray.clashproxy.service;

import com.deftgray.clashproxy.dto.SimplifiedCard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Value("${openrouter.url}")
    private String openRouterUrl;

    @org.springframework.beans.factory.annotation.Value("${openrouter.api.key}")
    private String apiKey;

    @org.springframework.beans.factory.annotation.Value("${openrouter.model}")
    private String modelName;

    public com.deftgray.clashproxy.dto.LlmDeckSuggestion generateDeckRecommendation(List<SimplifiedCard> cards) {
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = " ";
            log.warn("Using hardcoded API key (NOT RECOMMENDED for production)");
            // throw new IllegalStateException("OPENROUTER_API_KEY environment variable is
            // not set");
        }

        String prompt = createPrompt(cards);
        log.debug("Sending prompt to LLM: {}", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        // headers.set("HTTP-Referer", "http://localhost:8080"); // Optional for
        // OpenRouter
        // headers.set("X-Title", "ClashProxy"); // Optional for OpenRouter

        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "You are a Clash Royale expert. Build the best deck (8 cards) from the provided list.\n"
                                        +
                                        "ABSOLUTE STRICT RULES THAT CANNOT BE BROKEN:\n" +
                                        "1. Exactly ONE card can have \"isHero\": true. The other 7 MUST have \"isHero\": false.\n"
                                        +
                                        "2. A MAXIMUM of TWO cards can have \"isEvolved\": true. The rest MUST have \"isEvolved\": false.\n"
                                        +
                                        "Return ONLY a JSON object with keys: 'cards' (array of objects with keys: 'name', 'isEvolved' (boolean), 'isHero' (boolean), 'level' (integer)), 'strategy' (string, MUST be one of: 'Beatdown', 'Control', 'Cycle', 'Bait', 'Siege', 'Bridge Spam', 'Split Lane', 'Hybrid'), and 'tactic' (string, explanation of how to play). No markdown."),
                        Map.of("role", "user", "content", prompt)));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            long startTime = System.currentTimeMillis();
            String response = restTemplate.postForObject(openRouterUrl, entity, String.class);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Received response from LLM (Model: {}) in {} ms", modelName, duration);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Error calling OpenRouter API with model {}", modelName, e);
            e.printStackTrace();
            return null;
        }
    }

    private String createPrompt(List<SimplifiedCard> cards) {
        String cardList = cards.stream()
                .map(c -> String.format("%s (Lvl: %d, Evo: %s, Hero: %s)", c.getName(), c.getLevel(), c.isEvolved(),
                        c.isHero()))
                .collect(Collectors.joining("\n"));

        return "Here is my collection of cards:\n" + cardList
                + "\n\nPick 8 cards for a balanced deck. Maximize card levels. Ensure valid deck composition.";
    }

    public com.deftgray.clashproxy.dto.LlmDeckSuggestion generateDeckCompletion(List<SimplifiedCard> collection,
            List<String> currentDeckNames, String playStyle) {
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = " ";
            log.warn("Using hardcoded API key (NOT RECOMMENDED for production)");
        }

        String prompt = createCompletionPrompt(collection, currentDeckNames, playStyle);
        log.debug("Sending completion prompt to LLM: {}", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "You are a Clash Royale expert. Complete the deck to 8 cards using the player's collection. Respect the user's selected playstyle: "
                                        + playStyle
                                        + ".\nABSOLUTE STRICT RULES THAT CANNOT BE BROKEN:\n"
                                        + "1. Exactly ONE card can have \"isHero\": true in the ENTIRE DECK. The other 7 MUST have \"isHero\": false.\n"
                                        + "2. A MAXIMUM of TWO cards can have \"isEvolved\": true in the ENTIRE DECK. The rest MUST have \"isEvolved\": false.\n"
                                        + "Return ONLY a JSON object with keys: 'cards' (array of objects with keys: 'name', 'isEvolved' (boolean), 'isHero' (boolean), 'level' (integer)), 'strategy' (string enum), and 'tactic' (string)."),
                        Map.of("role", "user", "content", prompt)));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            long startTime = System.currentTimeMillis();
            String response = restTemplate.postForObject(openRouterUrl, entity, String.class);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Received response from LLM (Model: {}) in {} ms", modelName, duration);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Error calling OpenRouter API with model {}", modelName, e);
            e.printStackTrace();
            return null;
        }
    }

    private String createCompletionPrompt(List<SimplifiedCard> collection, List<String> currentDeckNames,
            String playStyle) {
        String cardList = collection.stream()
                .map(c -> String.format("%s (Lvl: %d, Evo: %s, Hero: %s)", c.getName(), c.getLevel(), c.isEvolved(),
                        c.isHero()))
                .collect(Collectors.joining("\n"));

        String alreadySelected = String.join(", ", currentDeckNames);

        return "Here is my collection of cards:\n" + cardList
                + "\n\nI want to build a '" + playStyle + "' deck."
                + "\nI have ALREADY selected these cards: " + alreadySelected
                + "\nPlease pick the remaining cards from my collection to form a complete, competitive 8-card deck. Ensure the final deck includes the cards I selected.";
    }

    private com.deftgray.clashproxy.dto.LlmDeckSuggestion parseResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            // Check for OpenRouter/Provider errors
            if (root.has("error")) {
                log.error("OpenRouter API Error: {}", root.get("error"));
                return null;
            }
            if (root.path("choices").isArray() && !root.path("choices").isEmpty()) {
                JsonNode choice = root.path("choices").get(0);
                if (choice.has("error")) {
                    log.error("Provider Error in choice: {}", choice.get("error"));
                    return null;
                }

                JsonNode message = choice.path("message");
                String content = message.path("content").asText();

                if (content == null || content.trim().isEmpty()) {
                    log.error("LLM returned empty content. Reasoning (if any): {}", message.path("reasoning").asText());
                    return null;
                }

                // Clean up code blocks if present (some LLMs add ```json ... ```)
                content = content.replaceAll("```json", "").replaceAll("```", "").trim();
                return objectMapper.readValue(content, com.deftgray.clashproxy.dto.LlmDeckSuggestion.class);
            }

            log.error("Invalid response structure: {}", jsonResponse);
            return null;
        } catch (Exception e) {
            log.error("Failed to parse LLM response", e);
            return null;
        }
    }
}
