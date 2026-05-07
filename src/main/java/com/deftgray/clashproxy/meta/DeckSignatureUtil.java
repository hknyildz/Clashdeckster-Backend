package com.deftgray.clashproxy.meta;

import com.deftgray.clashproxy.dto.CardDto;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for generating unique deck signatures.
 * 
 * Signature format: "cardId:evoLevel_cardId:evoLevel_..._T:towerTroopId"
 * Cards are sorted by cardId ascending so that identical decks with
 * different card orderings produce the same signature.
 * 
 * The evolutionLevel distinguishes between:
 *   0 = Normal card
 *   1 = Evolved form
 *   2 = Hero form
 * 
 * Tower Troop ID is appended at the end to differentiate decks
 * that use the same 8 cards but different tower troops.
 */
public class DeckSignatureUtil {

    /**
     * Generate a unique signature for a deck.
     *
     * @param cards       8-card deck from player's currentDeck
     * @param towerTroopId ID of the tower troop (may be null)
     * @return Normalized signature string
     */
    public static String generateSignature(List<CardDto> cards, Long towerTroopId) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }

        String cardPart = cards.stream()
                .sorted(Comparator.comparingLong(c -> c.getId() != null ? c.getId() : 0L))
                .map(c -> {
                    long id = c.getId() != null ? c.getId() : 0L;
                    int evoLevel = c.getEvolutionLevel() != null ? c.getEvolutionLevel() : 0;
                    return id + ":" + evoLevel;
                })
                .collect(Collectors.joining("_"));

        if (towerTroopId != null) {
            return cardPart + "_T:" + towerTroopId;
        }

        return cardPart;
    }

    /**
     * Calculate average elixir cost for a deck.
     *
     * @param cards 8-card deck
     * @return Average elixir cost, or 0.0 if empty
     */
    public static double calculateAverageElixir(List<CardDto> cards) {
        if (cards == null || cards.isEmpty()) {
            return 0.0;
        }

        return cards.stream()
                .mapToInt(c -> c.getElixirCost() != null ? c.getElixirCost() : 0)
                .average()
                .orElse(0.0);
    }

    /**
     * Convert a deck's cards into a JSON array string for DB storage.
     * Format: [{"id":26000000,"name":"Knight","evolutionLevel":0}, ...]
     *
     * @param cards 8-card deck
     * @return JSON string representation
     */
    public static String cardsToJson(List<CardDto> cards) {
        if (cards == null || cards.isEmpty()) {
            return "[]";
        }

        String entries = cards.stream()
                .map(c -> {
                    long id = c.getId() != null ? c.getId() : 0L;
                    String name = c.getName() != null ? c.getName().replace("\"", "\\\"") : "Unknown";
                    int evoLevel = c.getEvolutionLevel() != null ? c.getEvolutionLevel() : 0;
                    int elixirCost = c.getElixirCost() != null ? c.getElixirCost() : 0;
                    return String.format("{\"id\":%d,\"name\":\"%s\",\"evolutionLevel\":%d,\"elixirCost\":%d}",
                            id, name, evoLevel, elixirCost);
                })
                .collect(Collectors.joining(","));

        return "[" + entries + "]";
    }
}
