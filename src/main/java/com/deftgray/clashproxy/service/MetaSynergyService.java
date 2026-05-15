package com.deftgray.clashproxy.service;

import com.deftgray.clashproxy.entity.MetaDeckEntity;
import com.deftgray.clashproxy.model.Card;
import com.deftgray.clashproxy.repository.MetaDeckRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyses meta-deck data to provide synergy intelligence:
 *  - Co-occurrence calculation (which cards appear together?)
 *  - Combo detection (≥80% co-occurrence = protected combo)
 *  - Dynamic win condition detection from meta data
 *  - Meta-deck search by win condition or card names
 *  - Match scoring (how well does a user's collection fit a meta deck?)
 *  - LLM meta-context string building (for fallback strategy)
 *
 * Caches the latest meta decks in memory with a 1-hour TTL to avoid
 * repeated DB queries.  All heavy computation runs against this cache.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetaSynergyService {

    private final MetaDeckRepository metaDeckRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── In-memory cache ───
    private List<MetaDeckEntity> cachedDecks;
    private Map<String, Double> cachedCardFrequency;  // cardName → % of decks it appears in
    private Set<String> cachedDynamicWCs;              // dynamically detected WCs from meta
    private Instant cacheExpiry = Instant.MIN;
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    // ─── Co-occurrence thresholds ───
    /** Cards appearing together in ≥ this % are considered an inseparable combo. */
    public static final double COMBO_THRESHOLD = 70.0;
    /** Cards appearing together in ≥ this % are strong associations. */
    public static final double STRONG_THRESHOLD = 40.0;
    /** Minimum number of meta decks containing a WC before co-occurrence is trustworthy. */
    private static final int MIN_SAMPLE_SIZE = 2;

    // ─── Dynamic WC thresholds ───
    /** Card must appear in at least this % of meta decks to be considered a dynamic WC. */
    private static final double DYNAMIC_WC_MIN_FREQUENCY = 5.0;
    /** Cards appearing in more than this % are "universal" (Log, Arrows) not WCs. */
    private static final double DYNAMIC_WC_MAX_FREQUENCY = 60.0;

    private static final Map<String, String> CARD_TYPE_MAP = Map.ofEntries(
            Map.entry("The Log", "Small Spell"), Map.entry("Zap", "Small Spell"),
            Map.entry("Arrows", "Small Spell"), Map.entry("Fireball", "Big Spell"),
            Map.entry("Poison", "Big Spell"), Map.entry("Rocket", "Big Spell"),
            Map.entry("Tesla", "Building"), Map.entry("Inferno Tower", "Building"),
            Map.entry("Cannon", "Building"), Map.entry("Knight", "Troop"),
            Map.entry("Valkyrie", "Troop"), Map.entry("Musketeer", "Troop"),
            Map.entry("Ice Spirit", "Cycle"), Map.entry("Skeletons", "Cycle")
    );

    // ──────────────────────────────────────────────
    //  1. Cached meta-deck access + dynamic analysis
    // ──────────────────────────────────────────────

    /**
     * Returns the latest snapshot of meta decks, cached for 1 hour.
     * Also triggers dynamic WC detection on cache refresh.
     */
    public List<MetaDeckEntity> getLatestMetaDecks() {
        if (cachedDecks != null && Instant.now().isBefore(cacheExpiry)) {
            return cachedDecks;
        }
        cachedDecks = metaDeckRepository.findLatestMetaDecks();
        cacheExpiry = Instant.now().plus(CACHE_TTL);
        log.info("[MetaSynergy] Refreshed cache — {} meta decks loaded", cachedDecks.size());

        // Rebuild dynamic WC analysis on cache refresh
        rebuildDynamicAnalysis();

        return cachedDecks;
    }

    /**
     * Analyzes meta deck frequency and detects dynamic WCs.
     * Called automatically on cache refresh.
     */
    private void rebuildDynamicAnalysis() {
        if (cachedDecks == null || cachedDecks.isEmpty()) {
            cachedCardFrequency = Map.of();
            cachedDynamicWCs = Set.of();
            return;
        }

        // Count how often each card appears across ALL meta decks
        Map<String, Integer> cardCounts = new HashMap<>();
        int totalDecks = cachedDecks.size();

        for (MetaDeckEntity deck : cachedDecks) {
            Set<String> uniqueCards = new HashSet<>(parseCardNames(deck.getCardsJson()));
            for (String card : uniqueCards) {
                cardCounts.merge(card, 1, Integer::sum);
            }
        }

        // Convert to percentages, sorted descending
        cachedCardFrequency = new LinkedHashMap<>();
        cardCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> cachedCardFrequency.put(
                        e.getKey(), e.getValue() * 100.0 / totalDecks));

        // Dynamic WCs: cards in the "sweet spot" — popular enough to define archetypes,
        // but not so universal that every deck uses them (Log, Arrows, Fireball etc.)
        cachedDynamicWCs = cachedCardFrequency.entrySet().stream()
                .filter(e -> e.getValue() >= DYNAMIC_WC_MIN_FREQUENCY
                          && e.getValue() <= DYNAMIC_WC_MAX_FREQUENCY)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        log.info("[MetaSynergy] Card frequency analysis complete: {} unique cards, {} dynamic WC candidates",
                cachedCardFrequency.size(), cachedDynamicWCs.size());

        // Log top 20 most frequent cards for visibility
        log.info("[MetaSynergy] Top card frequencies:");
        cachedCardFrequency.entrySet().stream().limit(20).forEach(e ->
                log.info("[MetaSynergy]   {} — {}% {}",
                        e.getKey(), String.format("%.1f", e.getValue()),
                        e.getValue() > DYNAMIC_WC_MAX_FREQUENCY ? "[UNIVERSAL - not WC]" : "[DYNAMIC WC candidate]"));
    }

    /**
     * Returns the set of dynamically detected WC-like cards from meta data.
     * These are cards that appear in 5-60% of meta decks — popular enough
     * to define archetypes, but not universal like Log or Arrows.
     */
    public Set<String> getDynamicWinConditions() {
        getLatestMetaDecks(); // ensure cache
        return cachedDynamicWCs != null ? cachedDynamicWCs : Set.of();
    }

    /**
     * Returns the meta frequency of a specific card (0-100%).
     * Useful for deciding how "meta" a user's card is.
     */
    public double getCardMetaFrequency(String cardName) {
        getLatestMetaDecks(); // ensure cache
        return cachedCardFrequency != null
                ? cachedCardFrequency.getOrDefault(cardName, 0.0) : 0.0;
    }

    // ──────────────────────────────────────────────
    //  2. Find meta decks by win condition
    // ──────────────────────────────────────────────

    /**
     * Returns all meta decks whose win_conditions column contains the given name,
     * ordered by popularity (most popular first).
     */
    public List<MetaDeckEntity> findByWinCondition(String wcName) {
        if (wcName == null || wcName.isBlank()) return List.of();
        return getLatestMetaDecks().stream()
                .filter(d -> d.getWinConditions() != null
                          && d.getWinConditions().contains(wcName))
                .toList();
    }

    /**
     * Finds meta decks that contain a specific card in their cards_json,
     * regardless of whether it's registered as a WC in the win_conditions column.
     * This handles the "Bowler isn't a registered WC but appears in many decks" case.
     */
    public List<MetaDeckEntity> findByCardInDeck(String cardName) {
        if (cardName == null || cardName.isBlank()) return List.of();

        List<MetaDeckEntity> result = getLatestMetaDecks().stream()
                .filter(deck -> parseCardNames(deck.getCardsJson()).contains(cardName))
                .toList();

        log.info("[MetaSynergy] findByCardInDeck('{}') → {} meta decks found",
                cardName, result.size());
        return result;
    }

    // ──────────────────────────────────────────────
    //  3. Find meta decks by card names (Advanced Builder)
    // ──────────────────────────────────────────────

    /**
     * Returns meta decks sorted by how many of the given card names they contain
     * (descending).  Decks with zero matches are excluded.
     */
    public List<MetaDeckEntity> findByCards(List<String> cardNames) {
        if (cardNames == null || cardNames.isEmpty()) return List.of();
        Set<String> nameSet = new HashSet<>(cardNames);

        return getLatestMetaDecks().stream()
                .map(deck -> {
                    List<String> deckNames = parseCardNames(deck.getCardsJson());
                    long matchCount = deckNames.stream().filter(nameSet::contains).count();
                    return Map.entry(deck, matchCount);
                })
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    // ──────────────────────────────────────────────
    //  4. Co-occurrence calculation
    // ──────────────────────────────────────────────

    /**
     * For a given anchor card and a set of relevant meta decks, calculates
     * how often each other card appears alongside the anchor.
     *
     * @return Map of cardName → percentage (0-100), sorted descending, filtered ≥10%
     */
    public Map<String, Double> calculateCoOccurrence(String anchorCard,
                                                      List<MetaDeckEntity> relevantDecks) {
        if (relevantDecks == null || relevantDecks.isEmpty()) return Map.of();

        Map<String, Integer> counts = new HashMap<>();
        int total = relevantDecks.size();

        for (MetaDeckEntity deck : relevantDecks) {
            for (String name : parseCardNames(deck.getCardsJson())) {
                if (!name.equals(anchorCard)) {
                    counts.merge(name, 1, Integer::sum);
                }
            }
        }

        return counts.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue() * 100.0 / total))
                .filter(e -> e.getValue() >= 10.0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    // ──────────────────────────────────────────────
    //  5. Combo detection
    // ──────────────────────────────────────────────

    /**
     * Detects cards that are inseparable combo partners of the given WC.
     * A card is a combo partner if it appears in ≥ COMBO_THRESHOLD % of
     * meta decks containing the WC, and the sample size is ≥ MIN_SAMPLE_SIZE.
     *
     * @return Set of combo partner card names (does NOT include the anchor itself)
     */
    public Set<String> detectComboPartners(String wcName,
                                            List<MetaDeckEntity> wcDecks) {
        if (wcDecks.size() < MIN_SAMPLE_SIZE) {
            log.info("[MetaSynergy] Only {} decks for '{}' — skipping combo detection (min {})",
                    wcDecks.size(), wcName, MIN_SAMPLE_SIZE);
            return Set.of();
        }

        Map<String, Double> coOcc = calculateCoOccurrence(wcName, wcDecks);
        Set<String> combos = coOcc.entrySet().stream()
                .filter(e -> e.getValue() >= COMBO_THRESHOLD)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        if (!combos.isEmpty()) {
            log.info("[MetaSynergy] Combo partners for '{}': {}", wcName, combos);
        }
        return combos;
    }

    // ──────────────────────────────────────────────
    //  6. Match scoring
    // ──────────────────────────────────────────────

    /**
     * Scores how well a user's card collection fits a specific meta deck.
     * Now considers evolved/hero form compatibility.
     *
     * Scoring per card:
     *   +3  card exists in user's collection (exact name match)
     *   +1  bonus if meta uses EVOLVED form and user has evo unlocked
     *   +2  bonus if meta uses HERO form and user has hero unlocked
     *   -2  penalty if meta uses evo but user does NOT have that form
     *   -4  penalty if meta uses hero but user does NOT have that form (hero changes mechanics)
     *   +1  user has a card of the same type (potential replacement, card NOT owned)
     *   +0  no viable replacement available
     *
     * Global bonuses:
     *   +2  if meta deck is in the top 10 by popularity
     *
     * @return integer score (higher = better fit)
     */
    public int calculateMatchScore(MetaDeckEntity metaDeck,
                                    List<Card> userCards,
                                    Set<String> usedNames) {
        List<String> metaCardNames = parseCardNames(metaDeck.getCardsJson());
        Map<String, String> metaCardTypes = parseCardTypes(metaDeck.getCardsJson());
        Set<String> evolvedCards = parseEvolvedCardNames(metaDeck.getCardsJson());
        Set<String> heroCards = parseHeroCardNames(metaDeck.getCardsJson());

        // Build user lookup maps
        Map<String, Card> userCardMap = userCards.stream()
                .collect(Collectors.toMap(Card::getName, c -> c, (a, b) -> a));
        Set<String> userCardTypes = userCards.stream()
                .map(Card::getType).filter(Objects::nonNull).collect(Collectors.toSet());

        int score = 0;
        for (String metaCard : metaCardNames) {
            if (usedNames != null && usedNames.contains(metaCard)) {
                score += 3;
            } else if (userCardMap.containsKey(metaCard)) {
                score += 3;

                // Evo form bonus/penalty (stat changes only)
                Card userCard = userCardMap.get(metaCard);
                if (evolvedCards.contains(metaCard)) {
                    if (Boolean.TRUE.equals(userCard.getEvolved())) {
                        score += 1; // User has evo → perfect match
                    } else {
                        score -= 2; // Meta uses evo but user doesn't have it
                    }
                }
                // Hero form bonus/penalty (mechanic-changing — heavier weight)
                if (heroCards.contains(metaCard)) {
                    if (Boolean.TRUE.equals(userCard.getIsHero())) {
                        score += 2; // User has hero → perfect match (higher bonus)
                    } else {
                        score -= 4; // Meta uses hero but user doesn't → deck archetype breaks
                    }
                }
            } else {
                // Card not in collection — check type replacement
                String type = metaCardTypes.get(metaCard);
                if (type != null && userCardTypes.contains(type)) {
                    score += 1;
                }
            }
        }

        // Popularity bonus
        if (metaDeck.getPopularityRank() != null && metaDeck.getPopularityRank() <= 10) {
            score += 2;
        }

        return score;
    }

    /**
     * Calculates how viable a Win Condition is for a specific user's collection.
     * Considers: card level, average match score against meta decks, evo/hero compatibility.
     *
     * @param wcName     Win condition card name
     * @param userCards  User's full card collection
     * @return viability score (higher = better fit for this user)
     */
    public double calculateWCViability(String wcName, List<Card> userCards) {
        // 1. Find the user's WC card
        Card wcCard = userCards.stream()
                .filter(c -> c.getName().equals(wcName))
                .findFirst().orElse(null);
        if (wcCard == null) return 0.0;

        double levelScore = wcCard.getLevel() != null ? wcCard.getLevel() : 0;

        // 2. Find meta decks containing this WC (check all WCs in the deck)
        List<MetaDeckEntity> wcDecks = findByWinCondition(wcName);
        if (wcDecks.isEmpty()) {
            // Also try card-in-deck search
            wcDecks = findByCardInDeck(wcName);
        }

        if (wcDecks.isEmpty()) {
            // No meta reference at all — viability is just the card level (low confidence)
            return levelScore * 0.3;
        }

        // 3. Calculate average match score of TOP 3 best-fitting meta decks
        //    (not all — we don't want bad-fit decks dragging down a good WC)
        double avgMatchScore = wcDecks.stream()
                .mapToInt(d -> calculateMatchScore(d, userCards, null))
                .sorted()              // ascending
                .skip(Math.max(0, wcDecks.size() - 3)) // keep top 3
                .average()
                .orElse(0.0);

        // 4. Composite score: level matters 30%, meta match matters 70%
        double viability = (levelScore * 0.3) + (avgMatchScore * 0.7);

        log.info("[MetaSynergy] WC Viability for '{}': level={}, top3AvgMatch={}, viability={} (from {} meta decks)",
                wcName, (int) levelScore, String.format("%.1f", avgMatchScore),
                String.format("%.1f", viability), wcDecks.size());

        return viability;
    }

    // ──────────────────────────────────────────────
    //  7. LLM meta-context builder (for fallback)
    // ──────────────────────────────────────────────

    /**
     * Builds a human-readable meta-context string to inject into the LLM prompt.
     * Includes top 5 reference decks with game types, average elixir, evo info,
     * and co-occurrence data.
     */
    public String buildMetaContext(String anchorCard,
                                    List<MetaDeckEntity> relevantDecks,
                                    Map<String, com.deftgray.clashproxy.model.Card> userCardMap) {
        if (relevantDecks == null || relevantDecks.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\nMETA DATA (Top players' proven decks):\n");

        // Top 5 reference decks with rich info
        List<MetaDeckEntity> top = relevantDecks.stream().limit(5).toList();
        for (int i = 0; i < top.size(); i++) {
            MetaDeckEntity d = top.get(i);
            List<String> names = parseCardNames(d.getCardsJson());
            Set<String> evolvedCards = parseEvolvedCardNames(d.getCardsJson());
            Map<String, Integer> elixirCosts = parseCardElixirCosts(d.getCardsJson());

            sb.append(String.format("Reference Deck %d (%d users, type: %s, winCons: %s, avg elixir: %.1f):\n",
                    i + 1, d.getUsageCount(),
                    d.getGameType() != null ? d.getGameType() : "Unknown",
                    d.getWinConditions() != null ? d.getWinConditions() : "None",
                    d.getAverageElixir() != null ? d.getAverageElixir() : 0.0));

            for (String name : names) {
                int cost = elixirCosts.getOrDefault(name, 0);
                boolean isEvo = evolvedCards.contains(name);

                // Ownership annotation
                if (userCardMap != null && userCardMap.containsKey(name)) {
                    com.deftgray.clashproxy.model.Card userCard = userCardMap.get(name);
                    int userLevel = userCard.getLevel() != null ? userCard.getLevel() : 0;
                    sb.append(String.format("  - %s (%d elixir%s) ✓ OWNED Lvl:%d\n",
                            name, cost, isEvo ? ", EVOLVED" : "", userLevel));
                } else {
                    sb.append(String.format("  - %s (%d elixir%s) ✗ NOT OWNED → find replacement\n",
                            name, cost, isEvo ? ", EVOLVED" : ""));
                }
            }
        }

        // Co-occurrence
        if (anchorCard != null && relevantDecks.size() >= MIN_SAMPLE_SIZE) {
            Map<String, Double> coOcc = calculateCoOccurrence(anchorCard, relevantDecks);
            if (!coOcc.isEmpty()) {
                sb.append(String.format("\nCO-OCCURRENCE with %s:\n", anchorCard));
                coOcc.entrySet().stream().limit(10).forEach(e ->
                        sb.append(String.format("- %s: %.0f%%\n", e.getKey(), e.getValue())));
            }
        }

        return sb.toString();
    }

    // ──────────────────────────────────────────────
    //  Helpers: parse cards_json
    // ──────────────────────────────────────────────

    /** Extract card names from a cards_json string. */
    public List<String> parseCardNames(String cardsJson) {
        try {
            JsonNode arr = objectMapper.readTree(cardsJson);
            List<String> names = new ArrayList<>();
            for (JsonNode node : arr) {
                if (node.has("name")) {
                    names.add(node.get("name").asText());
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("[MetaSynergy] Failed to parse cards_json", e);
            return List.of();
        }
    }

    /** Extract names of cards that are evolved in the meta deck. */
    public Set<String> parseEvolvedCardNames(String cardsJson) {
        try {
            JsonNode arr = objectMapper.readTree(cardsJson);
            Set<String> evolved = new HashSet<>();
            for (JsonNode node : arr) {
                if (node.has("name") && node.has("evolutionLevel")) {
                    int evoLevel = node.get("evolutionLevel").asInt(0);
                    if (evoLevel == 1) {
                        evolved.add(node.get("name").asText());
                    }
                }
            }
            return evolved;
        } catch (Exception e) {
            log.warn("[MetaSynergy] Failed to parse evolved cards from cards_json", e);
            return Set.of();
        }
    }

    /** Extract names of cards that are in hero form in the meta deck (evolutionLevel=2). */
    public Set<String> parseHeroCardNames(String cardsJson) {
        try {
            JsonNode arr = objectMapper.readTree(cardsJson);
            Set<String> heroes = new HashSet<>();
            for (JsonNode node : arr) {
                if (node.has("name") && node.has("evolutionLevel")) {
                    int evoLevel = node.get("evolutionLevel").asInt(0);
                    if (evoLevel == 2) {
                        heroes.add(node.get("name").asText());
                    }
                }
            }
            return heroes;
        } catch (Exception e) {
            log.warn("[MetaSynergy] Failed to parse hero cards from cards_json", e);
            return Set.of();
        }
    }

    /** Extract card name → type mapping from a cards_json string. */
    private Map<String, String> parseCardTypes(String cardsJson) {
        List<String> names = parseCardNames(cardsJson);
        Map<String, String> types = new HashMap<>();
        for (String name : names) {
            types.put(name, CARD_TYPE_MAP.getOrDefault(name, "Troop")); // Default Troop
        }
        return types;
    }

    /** Extract card name → elixirCost mapping from a cards_json string. */
    public Map<String, Integer> parseCardElixirCosts(String cardsJson) {
        try {
            JsonNode arr = objectMapper.readTree(cardsJson);
            Map<String, Integer> result = new HashMap<>();
            for (JsonNode node : arr) {
                String name = node.has("name") ? node.get("name").asText() : null;
                int cost = node.has("elixirCost") ? node.get("elixirCost").asInt(0) : 0;
                if (name != null) result.put(name, cost);
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
