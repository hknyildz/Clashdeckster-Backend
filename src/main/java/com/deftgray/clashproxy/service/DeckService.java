package com.deftgray.clashproxy.service;

import com.deftgray.clashproxy.dto.LlmDeckSuggestion;
import com.deftgray.clashproxy.model.Card;
import com.deftgray.clashproxy.dto.DeckResponse;
import com.deftgray.clashproxy.dto.SimplifiedCard;
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
        List<Card> allCards = clashService.getPlayerCards(playerTag);
        if (allCards.isEmpty()) {
            log.warn("No cards found for player: {}", playerTag);
            return DeckResponse.builder().valid(false).strategy("N/A")
                    .tacticMessage("Player not found or no cards available.").build();
        }
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
                    .generateDeckRecommendation(simplifiedCards);

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
                    boolean llmWantsEvolution = cardSuggestion.isEvolved();

                    originalCard.setEvolved(userHasEvolution && llmWantsEvolution);

                    deck.add(originalCard);
                } else {
                    log.warn("Suggested card '{}' not found in player's collection", name);
                }
            }

            // 4. Validate
            if (isValidDeck(deck)) {
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

    public DeckResponse completeDeck(com.deftgray.clashproxy.dto.DeckCompletionRequest request) {
        log.info("Completing deck for player: {}", request.getPlayerTag());

        // 1. Get Player Cards & All Cards
        List<Card> playerCards = clashService.getPlayerCards(request.getPlayerTag());
        List<Card> allCards = clashService.getAllCards();

        if (playerCards.isEmpty()) {
            return DeckResponse.builder().valid(false).tacticMessage("Player cards not found.").build();
        }

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
            com.deftgray.clashproxy.dto.LlmDeckSuggestion suggestion = llmService.generateDeckCompletion(
                    simplifiedCollection, forcedNames, request.getPlayStyle());

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

            for (com.deftgray.clashproxy.dto.LlmDeckSuggestion.LlmCardSuggestion s : suggestion.getCards()) {
                if (cardMap.containsKey(s.getName())) {
                    Card original = cardMap.get(s.getName());
                    boolean userHasEvolution = Boolean.TRUE.equals(original.getEvolved());
                    boolean llmWantsEvolution = s.isEvolved();
                    original.setEvolved(userHasEvolution && llmWantsEvolution);
                    finalDeck.add(original);
                }
            }

            // Validate and potentially mix in forced cards if missing?
            // For MVP, if size != 8, we retry.
            if (isValidDeck(finalDeck)) {
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

    private DeckResponse buildResponse(List<Card> deck, com.deftgray.clashproxy.dto.LlmDeckSuggestion suggestion) {
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

    private boolean isValidDeck(List<Card> deck) {
        if (deck.size() != 8)
        {
            log.error("deck size can't be greater than 8, current size:{}",deck.size());
            return false;
        }

        long heroCount = deck.stream().filter(c -> Boolean.TRUE.equals(c.getIsHero())).count();
        if (heroCount > 1)
        {
            log.error("Hero count can't be greater then 1, current:{}",heroCount);
            return false;
        }

        long evolvedCount = deck.stream().filter(c -> Boolean.TRUE.equals(c.getEvolved())).count();
        if (evolvedCount > 2)
        {
            log.error("Evolved card count can't be greater then 2, current:{}",evolvedCount);
            return false;
        }
        return true;
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
