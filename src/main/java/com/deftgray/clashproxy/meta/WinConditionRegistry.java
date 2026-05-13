package com.deftgray.clashproxy.meta;

import com.deftgray.clashproxy.dto.CardDto;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry for identifying win conditions and game types from a deck's card list.
 * 
 * A deck can have multiple win conditions (e.g. Giant + Graveyard).
 * The gameType is derived from the dominant category, or "Mixed" if multiple categories exist.
 */
public class WinConditionRegistry {

    // Map<CardName, GameType>
    public static final Map<String, String> WIN_CONDITIONS;

    static {
        Map<String, String> map = new LinkedHashMap<>();

        // Siege
        map.put("X-Bow", "Siege");
        map.put("Mortar", "Siege");

        // Beatdown
        map.put("Golem", "Beatdown");
        map.put("Giant", "Beatdown");
        map.put("Electro Giant", "Beatdown");
        map.put("Goblin Giant", "Beatdown");
        map.put("Lava Hound", "Beatdown");
        map.put("Royal Giant", "Beatdown");

        // Bridge Spam / Pressure
        map.put("P.E.K.K.A", "Bridge Spam");
        map.put("Mega Knight", "Bridge Spam");
        map.put("Elite Barbarians", "Bridge Spam");
        map.put("Ram Rider", "Bridge Spam");
        map.put("Battle Ram", "Bridge Spam");

        // Cycle/Control
        map.put("Hog Rider", "Cycle/Control");
        map.put("Royal Hogs", "Cycle/Control");
        map.put("Balloon", "Cycle/Control");
        map.put("Miner", "Cycle/Control");
        map.put("Graveyard", "Cycle/Control");

        // Bait/Special
        map.put("Goblin Barrel", "Bait/Special");
        map.put("Skeleton Barrel", "Bait/Special");
        map.put("Wall Breakers", "Bait/Special");
        map.put("Three Musketeers", "Bait/Special");
        map.put("Goblin Drill", "Bait/Special");

        // Hybrid / Key Cards (can serve as primary WC in certain archetypes)
        map.put("Sparky", "Hybrid");
        map.put("Prince", "Hybrid");
        map.put("Skeleton King", "Hybrid");

        WIN_CONDITIONS = Collections.unmodifiableMap(map);
    }

    /**
     * Detect all win conditions present in a deck.
     *
     * @param cards List of CardDto from the player's currentDeck
     * @return List of win condition card names found (e.g. ["Hog Rider", "Balloon"])
     */
    public static List<String> detectWinConditions(List<CardDto> cards) {
        if (cards == null || cards.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> deckCardNames = cards.stream()
                .map(CardDto::getName)
                .collect(Collectors.toSet());

        return WIN_CONDITIONS.keySet().stream()
                .filter(deckCardNames::contains)
                .collect(Collectors.toList());
    }

    /**
     * Determine the game type for a deck based on its win conditions.
     * 
     * If all win conditions belong to the same category → return that category.
     * If win conditions span multiple categories → return "Mixed".
     * If no win condition found → return "Unknown".
     *
     * @param winConditions List of win condition card names
     * @return Game type string
     */
    public static String determineGameType(List<String> winConditions) {
        if (winConditions == null || winConditions.isEmpty()) {
            return "Unknown";
        }

        Set<String> gameTypes = winConditions.stream()
                .map(WIN_CONDITIONS::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (gameTypes.size() == 1) {
            return gameTypes.iterator().next();
        } else if (gameTypes.size() > 1) {
            return "Mixed";
        }

        return "Unknown";
    }

    /**
     * Convenience method: detect win conditions and determine game type in one call.
     *
     * @param cards Deck card list
     * @return WinConditionResult with both win conditions and game type
     */
    public static WinConditionResult analyze(List<CardDto> cards) {
        List<String> wcs = detectWinConditions(cards);
        String gameType = determineGameType(wcs);
        return new WinConditionResult(wcs, gameType);
    }

    /**
     * Simple record-like class to hold win condition analysis results.
     */
    public static class WinConditionResult {
        private final List<String> winConditions;
        private final String gameType;

        public WinConditionResult(List<String> winConditions, String gameType) {
            this.winConditions = winConditions;
            this.gameType = gameType;
        }

        public List<String> getWinConditions() {
            return winConditions;
        }

        /** Comma-separated win condition string for DB storage */
        public String getWinConditionsString() {
            return String.join(",", winConditions);
        }

        public String getGameType() {
            return gameType;
        }
    }
}
