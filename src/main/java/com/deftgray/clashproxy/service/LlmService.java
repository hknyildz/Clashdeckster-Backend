package com.deftgray.clashproxy.service;

import com.deftgray.clashproxy.dto.LlmDeckSuggestion;
import com.deftgray.clashproxy.dto.SimplifiedCard;
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

    public LlmDeckSuggestion generateDeckRecommendation(List<SimplifiedCard> cards, Integer bestTrophies) {
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = " ";
            log.warn("Using hardcoded API key (NOT RECOMMENDED for production)");
        }

        String prompt = createPrompt(cards);
        log.debug("Sending prompt to LLM: {}", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String systemContent = getSystemPromptBase(bestTrophies, false, null);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(
                        Map.of("role", "system", "content", systemContent),
                        Map.of("role", "user", "content", prompt)));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            String response = restTemplate.postForObject(openRouterUrl, entity, String.class);
            log.info("Received response from LLM: {}", response);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Error calling OpenRouter API", e);
            return null;
        }
    }

    private String createPrompt(List<SimplifiedCard> cards) {
        String cardList = cards.stream()
                .sorted((c1, c2) -> Integer.compare(c2.getLevel() != null ? c2.getLevel() : 0, 
                                                    c1.getLevel() != null ? c1.getLevel() : 0))
                .map(c -> String.format("%s (Lvl: %d, Evo: %s, Hero: %s)", c.getName(), c.getLevel(), c.isEvolved(),
                        c.isHero()))
                .collect(Collectors.joining("\n"));

        return "Here is my collection of cards, sorted by level (highest first):\n" + cardList
                + "\n\nPick 8 cards for a balanced, competitive deck. \n"
                + "CRITICAL REQUIREMENT - BALANCE SYNERGY AND LEVELS:\n"
                + "1. DECK SYNERGY IS PARAMOUNT: The deck must have a clear win condition, good defense, and spell support. Do not blindly pick all high-level cards if they ruin the synergy.\n"
                + "2. MAXIMIZE LEVELS WITHIN SYNERGY: Whenever you have multiple viable cards for a slot that fit the deck's archetype, YOU MUST select the one with the highest level (13, 14, 15). Avoid level 9-10 cards if a level 14 viable alternative exists.";
    }

    public LlmDeckSuggestion generateDeckCompletion(List<SimplifiedCard> collection,
            List<String> currentDeckNames, String playStyle, Integer bestTrophies) {
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = " ";
            log.warn("Using hardcoded API key (NOT RECOMMENDED for production)");
        }

        String prompt = createCompletionPrompt(collection, currentDeckNames, playStyle);
        log.debug("Sending completion prompt to LLM: {}", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String systemContent = getSystemPromptBase(bestTrophies, true, playStyle);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(
                        Map.of("role", "system", "content", systemContent),
                        Map.of("role", "user", "content", prompt)));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            String response = restTemplate.postForObject(openRouterUrl, entity, String.class);
            log.info("Received response from LLM: {}", response);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Error calling OpenRouter API", e);
            return null;
        }
    }

    private String createCompletionPrompt(List<SimplifiedCard> collection, List<String> currentDeckNames,
            String playStyle) {
        String cardList = collection.stream()
                .sorted((c1, c2) -> Integer.compare(c2.getLevel() != null ? c2.getLevel() : 0, 
                                                    c1.getLevel() != null ? c1.getLevel() : 0))
                .map(c -> String.format("%s (Lvl: %d, Evo: %s, Hero: %s)", c.getName(), c.getLevel(), c.isEvolved(),
                        c.isHero()))
                .collect(Collectors.joining("\n"));

        String alreadySelected = String.join(", ", currentDeckNames);

        return "Here is my collection of cards, sorted by level (highest first):\n" + cardList
                + "\n\nI want to build a '" + playStyle + "' deck."
                + "\nI have ALREADY selected these cards: " + alreadySelected
                + "\nPlease pick the remaining cards from my collection to form a complete, competitive 8-card deck. Ensure the final deck includes the cards I selected.\n"
                + "CRITICAL REQUIREMENT - BALANCE SYNERGY AND LEVELS:\n"
                + "1. SYNERGY FIRST: Make sure the final deck has excellent synergy for a " + playStyle + " archetype.\n"
                + "2. LEVELS SECOND: When deciding between two cards that both fit the synergy, YOU MUST pick the one with the higher level (13, 14, 15). Avoid adding low-level cards if a high-level alternative exists that preserves the deck's power.";
    }

    private String getSystemPromptBase(Integer bestTrophies, boolean isCompletion, String playStyle) {
        int trophies = bestTrophies != null ? bestTrophies : 0;
        int evoLimit = trophies < 3000 ? 1 : 2;
        int heroLimit = trophies < 3000 ? 1 : 2;
        int totalLimit = trophies < 3000 ? 2 : 3;

        String constraints = 
            "- MAXIMUM Evolutions Allowed: " + evoLimit + "\n" +
            "- MAXIMUM Heroes Allowed: " + heroLimit + "\n" +
            "- TOTAL SPECIAL CARDS (Heroes + Evolutions combined): " + totalLimit + "\n";

        String coreInstructions = 
              "1. STEP 1: Decide which cards to evolve. List their names in the 'selected_evolutions' array. Its length MUST NOT exceed the Maximum Evolutions Allowed.\n"
            + "2. STEP 2: Decide your heroes. List their names in the 'selected_heroes' array. Its length MUST NOT exceed the Maximum Heroes Allowed.\n"
            + "3. STEP 3: Verify that the sum of lengths of both arrays does NOT exceed the TOTAL SPECIAL CARDS limit.\n"
            + "4. STEP 4: Build exactly 8 cards. ONLY set 'isEvolved': true if the card is in 'selected_evolutions'.\n"
            + "5. ONLY evolve cards if the player collection indicates they have it unlocked (Evo: true or Level covers it).\n";

        String jsonFormat = 
              "Return ONLY a raw JSON object string with no markdown (no ```json). Format:\n"
            + "{\n"
            + "  \"selected_evolutions\": [\"CardName1\"],\n"
            + "  \"selected_heroes\": [],\n"
            + "  \"cards\": [ {\"name\": \"...\", \"isEvolved\": true/false, \"isHero\": true/false, \"level\": 14} ],\n"
            + "  \"strategy\": \"Beatdown | Control | Cycle | Bait | Siege | Bridge Spam | Split Lane | Hybrid\",\n"
            + "  \"tactic\": \"Explanation...\"\n"
            + "}";

        if (isCompletion) {
            constraints += "These limits apply to the ENTIRE FINAL DECK, including cards already selected.\n";
            return "You are an elite Clash Royale Deck Building AI.\n\n"
                 + "YOUR TASK:\n"
                 + "Complete the deck to precisely 8 cards using the player's collection.\n"
                 + "Respect this playstyle: " + playStyle + ".\n\n"
                 + "ABSOLUTE STRICT CONSTRAINTS:\n"
                 + constraints
                 + "\nINSTRUCTIONS:\n"
                 + coreInstructions
                 + "\nOUTPUT FORMAT:\n"
                 + jsonFormat;
        } else {
            return "You are an elite Clash Royale Deck Building AI.\n\n"
                 + "YOUR TASK:\n"
                 + "Select exactly 8 cards from the provided collection to form a competitive deck.\n\n"
                 + "ABSOLUTE STRICT CONSTRAINTS:\n"
                 + constraints
                 + "\nINSTRUCTIONS:\n"
                 + coreInstructions
                 + "\nOUTPUT FORMAT:\n"
                 + jsonFormat;
        }
    }

    private LlmDeckSuggestion parseResponse(String jsonResponse) {
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
                return objectMapper.readValue(content, LlmDeckSuggestion.class);
            }

            log.error("Invalid response structure: {}", jsonResponse);
            return null;
        } catch (Exception e) {
            log.error("Failed to parse LLM response", e);
            return null;
        }
    }
}
