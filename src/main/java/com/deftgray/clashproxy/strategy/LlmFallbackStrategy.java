package com.deftgray.clashproxy.strategy;

import com.deftgray.clashproxy.dto.CardDto;
import com.deftgray.clashproxy.dto.LlmDeckSuggestion;
import com.deftgray.clashproxy.dto.SimplifiedCard;
import com.deftgray.clashproxy.entity.MetaDeckEntity;
import com.deftgray.clashproxy.meta.WinConditionRegistry;
import com.deftgray.clashproxy.model.Card;
import com.deftgray.clashproxy.service.LlmService;
import com.deftgray.clashproxy.service.MetaSynergyService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fallback deck-building strategy: sends the user's collection to the LLM
 * along with meta-context (reference decks + co-occurrence data) so the LLM
 * can make an informed decision.
 *
 * Used when MetaLlmStrategy cannot produce a valid deck (e.g. no meta
 * data available, or all LLM retries exhausted in primary strategy).
 */
@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class LlmFallbackStrategy implements DeckBuildStrategy {

    private final LlmService llmService;
    private final MetaSynergyService metaSynergyService;

    private static final int MAX_RETRIES = 3;

    @Override
    public String name() {
        return "LlmFallbackStrategy";
    }

    @Override
    public boolean canHandle(DeckBuildContext context) {
        // LLM can always attempt — it's the last resort
        return true;
    }

    @Override
    public DeckBuildResult build(DeckBuildContext context) {
        List<Card> userCards = context.getPlayerCards();
        boolean isAdvancedBuilder = context.getLockedCardNames() != null
                && !context.getLockedCardNames().isEmpty();

        // ─── Build meta context for the LLM prompt ───
        String metaContext = buildMetaContextForLlm(context, userCards);

        // ─── Simplify cards for LLM ───
        List<SimplifiedCard> simplified = userCards.stream()
                .map(this::toSimplified).toList();

        // ─── Card map for result mapping ───
        Map<String, Card> cardMap = userCards.stream()
                .collect(Collectors.toMap(Card::getName, Function.identity(), (a, b) -> a));

        List<String> previousErrors = new ArrayList<>();

        for (int i = 0; i < MAX_RETRIES; i++) {
            log.info("[{}] Attempt {}/{}", name(), i + 1, MAX_RETRIES);

            LlmDeckSuggestion suggestion;
            if (isAdvancedBuilder) {
                suggestion = llmService.generateDeckCompletion(
                        simplified, context.getLockedCardNames(),
                        context.getPlayStyle(), context.getBestTrophies(),
                        context.getSupportCards(), metaContext, previousErrors);
            } else {
                suggestion = llmService.generateDeckRecommendation(
                        simplified, context.getBestTrophies(),
                        context.getSupportCards(), metaContext, previousErrors);
            }

            if (suggestion == null || suggestion.getCards() == null
                    || suggestion.getCards().isEmpty()) {
                log.warn("[{}] LLM returned empty suggestion", name());
                previousErrors.add("LLM returned empty or null response.");
                continue;
            }

            // Map LLM result back to Card objects
            List<Card> deck = new ArrayList<>();
            boolean hasValidationError = false;
            for (LlmDeckSuggestion.LlmCardSuggestion cs : suggestion.getCards()) {
                Card card = cardMap.get(cs.getName());
                if (card != null) {
                    // Apply evo/hero from LLM (respecting user ownership)
                    boolean userHasEvo = Boolean.TRUE.equals(card.getEvolved());
                    boolean userHasHero = Boolean.TRUE.equals(card.getIsHero());

                    List<String> intendedEvos = suggestion.getSelectedEvolutions() != null
                            ? suggestion.getSelectedEvolutions() : List.of();
                    List<String> intendedHeroes = suggestion.getSelectedHeroes() != null
                            ? suggestion.getSelectedHeroes() : List.of();

                    card.setEvolved(userHasEvo && intendedEvos.contains(card.getName()));
                    card.setIsHero(userHasHero && intendedHeroes.contains(card.getName()));

                    deck.add(card);
                } else {
                    log.warn("[{}] LLM suggested '{}' not in collection", name(), cs.getName());
                    previousErrors.add("Card '" + cs.getName() + "' is NOT in the player's collection.");
                    hasValidationError = true;
                }
            }

            if (deck.size() != 8) {
                log.warn("[{}] Deck size {} ≠ 8", name(), deck.size());
                previousErrors.add("Deck size was " + deck.size() + " instead of 8.");
                continue;
            }

            if (hasValidationError) {
                continue;
            }

            log.info("[{}] ✓ Deck built via LLM — strategy: {}", name(),
                    suggestion.getStrategy());

            return DeckBuildResult.builder()
                    .deck(deck)
                    .strategy(suggestion.getStrategy())
                    .tacticMessage(suggestion.getTactic())
                    .producedBy(name())
                    .selectedTowerTroop(suggestion.getSelectedTowerTroop())
                    .build();
        }

        log.error("[{}] All {} retries exhausted", name(), MAX_RETRIES);
        return null;
    }

    // ──────────────────────────────────────────────

    private String buildMetaContextForLlm(DeckBuildContext context, List<Card> userCards) {
        // Try to find an anchor WC for meta context
        String anchorWC = null;

        if (context.getLockedCardNames() != null) {
            anchorWC = context.getLockedCardNames().stream()
                    .filter(WinConditionRegistry.WIN_CONDITIONS::containsKey)
                    .findFirst().orElse(null);
        }

        if (anchorWC == null) {
            anchorWC = userCards.stream()
                    .filter(c -> WinConditionRegistry.WIN_CONDITIONS.containsKey(c.getName()))
                    .max(Comparator.comparingInt(c -> c.getLevel() != null ? c.getLevel() : 0))
                    .map(Card::getName).orElse(null);
        }

        if (anchorWC == null) return "";

        List<MetaDeckEntity> wcDecks = metaSynergyService.findByWinCondition(anchorWC);
        if (wcDecks.isEmpty()) {
            log.info("[{}] No meta decks found for WC: {}", name(), anchorWC);
            return "";
        }

        // Log the exact meta decks we are sending to the LLM (top 3)
        List<MetaDeckEntity> topDecks = wcDecks.stream().limit(3).toList();
        log.info("[{}] Passing {} meta decks to LLM for WC: {}", name(), topDecks.size(), anchorWC);
        for (int i = 0; i < topDecks.size(); i++) {
            List<String> cardNames = metaSynergyService.parseCardNames(topDecks.get(i).getCardsJson());
            log.info("[{}] Reference Meta Deck {}: {}", name(), i + 1, String.join(", ", cardNames));
        }

        return metaSynergyService.buildMetaContext(anchorWC, wcDecks);
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
