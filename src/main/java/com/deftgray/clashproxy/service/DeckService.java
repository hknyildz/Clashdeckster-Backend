package com.deftgray.clashproxy.service;

import com.deftgray.clashproxy.dto.ClashApiResponse;
import com.deftgray.clashproxy.dto.LlmDeckSuggestion;
import com.deftgray.clashproxy.model.Card;
import com.deftgray.clashproxy.dto.DeckResponse;
import com.deftgray.clashproxy.dto.SimplifiedCard;
import com.deftgray.clashproxy.dto.DeckCompletionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeckService {

    private final ClashService clashService;
    private final LlmService llmService;
    private static final int MAX_RETRIES = 3;

    public DeckResponse generateFreeDeck(String playerTag) {
        log.info("Generating free deck for player: {}", playerTag);
        // 1. Get Player Cards
        ClashApiResponse playerResponse = clashService.getPlayerCards(playerTag);
        if (playerResponse == null || playerResponse.getCards() == null || playerResponse.getCards().isEmpty()) {
            log.warn("No cards found for player: {}", playerTag);
            return DeckResponse.builder().valid(false).strategy("N/A")
                    .tacticMessage("Player not found or no cards available.").build();
        }
        List<Card> allCards = playerResponse.getCards().stream()
                .map(clashService::mapToCard)
                .toList();
        log.info("Found {} cards for player", allCards.size());

        // 2. Simplify for LLM
        List<SimplifiedCard> simplifiedCards = allCards.stream()
                .map(this::toSimplified)
                .toList();

        // Map for quick lookup
        Map<String, Card> cardMap = allCards.stream()
                .collect(Collectors.toMap(Card::getName, Function.identity(), (a, b) -> a)); // Handle duplicates if any

        // 3. Retry Loop
        for (int i = 0; i < MAX_RETRIES; i++) {
            log.info("Attempt {}/{} to generate deck via LLM", i + 1, MAX_RETRIES);
            LlmDeckSuggestion suggestion = llmService
                    .generateDeckRecommendation(simplifiedCards, playerResponse.getBestTrophies());

            if (suggestion == null || suggestion.getCards() == null || suggestion.getCards().isEmpty()) {
                log.error("LLM returned empty suggestion");
                continue;
            }
            log.info("LLM suggested cards: {}", suggestion.getCards());

            List<Card> deck = new ArrayList<>();
            for (LlmDeckSuggestion.LlmCardSuggestion cardSuggestion : suggestion
                    .getCards()) {
                String name = cardSuggestion.getName();

                if (cardMap.containsKey(name)) {
                    Card originalCard = cardMap.get(name);

                    boolean userHasEvolution = Boolean.TRUE.equals(originalCard.getEvolved());
                    List<String> intendedEvos = suggestion.getSelectedEvolutions() != null ? suggestion.getSelectedEvolutions() : new ArrayList<>();
                    
                    // Only grant evolution if user owns it AND the LLM explicitly intended it in the selected_evolutions array
                    originalCard.setEvolved(userHasEvolution && intendedEvos.contains(name));

                    deck.add(originalCard);
                } else {
                    log.warn("Suggested card '{}' not found in player's collection", name);
                }
            }


            enforceSmartConstraints(deck, playerResponse.getBestTrophies());

            // 4. Validate
            if (isValidDeck(deck, playerResponse.getBestTrophies())) {
                log.info("Valid deck generated: {}", suggestion.getStrategy());

                double avgElixir = deck.stream()
                        .mapToInt(c -> c.getElixirCost() != null ? c.getElixirCost() : 0)
                        .average().orElse(0.0);

                // Format:
                // https://link.clashroyale.com/en/?clashroyale://copyDeck?deck=id;id;id;id;id;id;id;id
                String cardIds = deck.stream()
                        .map(c -> String.valueOf(c.getId()))
                        .collect(Collectors.joining(";"));
                // Using the user-verified format which seems to wrap the deep link schema
                String deepLink = "https://link.clashroyale.com/en/?clashroyale://copyDeck?deck=" + cardIds
                        + "&l=Royals";

                return DeckResponse.builder()
                        .deck(deck)
                        .valid(true)
                        .strategy(suggestion.getStrategy())
                        .tacticMessage(suggestion.getTactic())
                        .averageElixir(Math.round(avgElixir * 10.0) / 10.0) // Round to 1 decimal place
                        .deepLink(deepLink)
                        .build();
            }
            log.warn("Generated deck validation failed.");
            // If invalid, loop again
        }

        log.error("Failed to generate valid deck after retries");
        return DeckResponse.builder()
                .valid(false)
                .strategy("N/A")
                .tacticMessage("Failed to generate a valid deck after " + MAX_RETRIES + " attempts.")
                .build();
    }

    public DeckResponse completeDeck(DeckCompletionRequest request) {
        log.info("Completing deck for player: {}", request.getPlayerTag());

        // 1. Get Player Cards & All Cards
        ClashApiResponse playerResponse = clashService.getPlayerCards(request.getPlayerTag());
        if (playerResponse == null || playerResponse.getCards() == null || playerResponse.getCards().isEmpty()) {
            return DeckResponse.builder().valid(false).tacticMessage("Player cards not found.").build();
        }
        List<Card> playerCards = playerResponse.getCards().stream()
                .map(clashService::mapToCard)
                .toList();

        List<Card> allCards = clashService.getAllCards();

        // 2. Process Partial Deck & Substitutions
        List<Card> forcedUpdates = new ArrayList<>();
        List<String> forcedNames = new ArrayList<>();

        if (request.getPartialDeck() != null) {
            for (Long id : request.getPartialDeck()) {
                // Check if user owns it
                Card owned = playerCards.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);

                if (owned != null) {
                    forcedUpdates.add(owned);
                    forcedNames.add(owned.getName());
                } else {
                    // Find substitute
                    Card substitute = findSubstitute(id, playerCards, allCards);
                    if (substitute != null) {
                        log.info("Substituting missing card ID {} with {}", id, substitute.getName());
                        forcedUpdates.add(substitute);
                        forcedNames.add(substitute.getName());
                    } else {
                        log.warn("Could not find substitute for ID {}", id);
                    }
                }
            }
        }

        // 3. Simplify & Call LLM
        List<SimplifiedCard> simplifiedCollection = playerCards.stream().map(this::toSimplified).toList();

        // Retry Loop
        for (int i = 0; i < MAX_RETRIES; i++) {
            LlmDeckSuggestion suggestion = llmService.generateDeckCompletion(
                    simplifiedCollection, forcedNames, request.getPlayStyle(), playerResponse.getBestTrophies());

            if (suggestion == null || suggestion.getCards() == null)
                continue;

            // Map back to Cards
            Map<String, Card> cardMap = playerCards.stream()
                    .collect(Collectors.toMap(Card::getName, Function.identity(), (a, b) -> a));

            List<Card> finalDeck = new ArrayList<>();

            // First add forced cards (to ensure they are present even if LLM hallucinates)
            // Actually, easier to just accept LLM result but validate?
            // Let's trust LLM result but fall back to replacements if LLM suggestions fail
            // validation

            for (LlmDeckSuggestion.LlmCardSuggestion s : suggestion.getCards()) {
                if (cardMap.containsKey(s.getName())) {
                    Card originalCard = cardMap.get(s.getName());
                    boolean userHasEvolution = Boolean.TRUE.equals(originalCard.getEvolved());
                    List<String> intendedEvos = suggestion.getSelectedEvolutions() != null ? suggestion.getSelectedEvolutions() : new ArrayList<>();
                    
                    originalCard.setEvolved(userHasEvolution && intendedEvos.contains(originalCard.getName()));
                    finalDeck.add(originalCard);
                }
            }


            enforceSmartConstraints(finalDeck, playerResponse.getBestTrophies());

            // Validate and potentially mix in forced cards if missing?
            // For MVP, if size != 8, we retry.
            if (isValidDeck(finalDeck,playerResponse.getBestTrophies())) {
                return buildResponse(finalDeck, suggestion);
            }
        }

        return DeckResponse.builder().valid(false).tacticMessage("Failed to complete deck.").build();
    }

    private Card findSubstitute(Long targetId, List<Card> playerCards, List<Card> allCards) {
        Card target = allCards.stream().filter(c -> c.getId().equals(targetId)).findFirst().orElse(null);
        if (target == null)
            return null;

        // Filter by same type if possible
        List<Card> candidates = playerCards.stream()
                .filter(c -> c.getType() != null && c.getType().equals(target.getType()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            candidates = new ArrayList<>(playerCards); // Fallback to all cards
        }

        // Find closest elixir cost
        final int targetCost = target.getElixirCost() != null ? target.getElixirCost() : 3;

        return candidates.stream()
                .min((c1, c2) -> {
                    int dist1 = Math.abs((c1.getElixirCost() != null ? c1.getElixirCost() : 0) - targetCost);
                    int dist2 = Math.abs((c2.getElixirCost() != null ? c2.getElixirCost() : 0) - targetCost);
                    if (dist1 != dist2)
                        return dist1 - dist2;
                    // Secondary sort: Rarity match? High level?
                    // Just use level for now
                    return (c2.getLevel() != null ? c2.getLevel() : 0) - (c1.getLevel() != null ? c1.getLevel() : 0);
                })
                .orElse(null);
    }

    private DeckResponse buildResponse(List<Card> deck, LlmDeckSuggestion suggestion) {
        double avgElixir = deck.stream()
                .mapToInt(c -> c.getElixirCost() != null ? c.getElixirCost() : 0)
                .average().orElse(0.0);

        String cardIds = deck.stream()
                .map(c -> String.valueOf(c.getId()))
                .collect(Collectors.joining(";"));
        String deepLink = "https://link.clashroyale.com/en/?clashroyale://copyDeck?deck=" + cardIds
                + "&l=Royals";

        return DeckResponse.builder()
                .deck(deck)
                .valid(true)
                .strategy(suggestion.getStrategy())
                .tacticMessage(suggestion.getTactic())
                .averageElixir(Math.round(avgElixir * 10.0) / 10.0)
                .deepLink(deepLink)
                .build();
    }

    private boolean isValidDeck(List<Card> deck, Integer maxTrophies ) {
        if (deck.size() != 8) {
            log.error("deck size can't be greater than 8, current size:{}", deck.size());
            return false;
        }

        long heroCount = deck.stream().filter(c -> Boolean.TRUE.equals(c.getIsHero())).count();
        long evolvedCount = deck.stream().filter(c -> Boolean.TRUE.equals(c.getEvolved())).count();

        int trophies = maxTrophies != null ? maxTrophies : 0;

        if (trophies < 3000) {
            if (heroCount > 1 || evolvedCount > 1) {
                log.error("Under 3000 trophies: max 1 hero & max 1 evo. Current Hero: {}, Evolved: {}", heroCount, evolvedCount);
                return false;
            }
        } else {
            if (heroCount + evolvedCount > 3) {
                log.error("Over 3000 trophies: max 3 special cards total. Current Hero: {}, Evolved: {}", heroCount, evolvedCount);
                return false;
            }
            if (heroCount > 2) {
                log.error("Hero count can't be greater than 2, current: {}", heroCount);
                return false;
            }
            if (evolvedCount > 2) {
                log.error("Evolved count can't be greater than 2, current: {}", evolvedCount);
                return false;
            }
        }

        return true;
    }

    private void enforceSmartConstraints(List<Card> deck, Integer maxTrophies) {
        int limit = (maxTrophies != null && maxTrophies >= 3000) ? 3 : 2;
        int maxEvos = (maxTrophies != null && maxTrophies >= 3000) ? 2 : 1;
        
        long heroCount = deck.stream().filter(c -> Boolean.TRUE.equals(c.getIsHero())).count();
        long availableEvoSlots = Math.min(maxEvos, limit - heroCount);
        
        int grantedEvoCount = 0;
        for (Card card : deck) {
            if (Boolean.TRUE.equals(card.getEvolved())) {
                if (grantedEvoCount < availableEvoSlots) {
                    grantedEvoCount++;
                } else {
                    card.setEvolved(false);
                    log.info("Smart-clamped excess evolution for {} to strictly obey limit", card.getName());
                }
            }
        }
    }



    private SimplifiedCard toSimplified(Card card) {
        return SimplifiedCard.builder()
                .name(card.getName())
                .level(card.getLevel())
                .isEvolved(Boolean.TRUE.equals(card.getEvolved()))
                .isHero(Boolean.TRUE.equals(card.getIsHero()))
                .elixirCost(card.getElixirCost())
                .build();
    }
}
