package com.deftgray.clashproxy.strategy;

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
 * Primary deck-building strategy: collects meta intelligence, sends to LLM,
 * then validates the LLM output against the user's actual card collection.
 *
 * Flow:
 *  1. Detect the user's best win condition (highest level among known WCs)
 *  2. Fetch meta decks containing that WC, deduplicate by card name set
 *  3. Calculate co-occurrence data for context
 *  4. Build rich meta context and send to LLM with user's card collection
 *  5. Validate LLM response:
 *     - All 8 cards must exist in user's collection
 *     - Evo/Hero flags must match user's actual unlocks
 *     - Evo/Hero limits must respect trophy-based rules
 *  6. If validation fails, retry with error feedback (max 3 attempts)
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class MetaLlmStrategy implements DeckBuildStrategy {

    private final LlmService llmService;
    private final MetaSynergyService metaSynergyService;

    private static final int MAX_RETRIES = 3;
    private static final int MAX_SPELLS = 2;

    // Comprehensive spell list — API type field is null so we maintain this manually.
    // Cards like Graveyard, Goblin Barrel, Mirror are win conditions / support, NOT counted as "spells" here.
    private static final Set<String> SPELL_CARDS = Set.of(
            // Small Spells
            "The Log", "Zap", "Arrows", "Giant Snowball", "Royal Delivery",
            // Big Spells
            "Fireball", "Poison", "Rocket", "Lightning", "Earthquake", "Tornado", "Freeze",
            // Rage is technically a spell
            "Rage"
    );

    // ─── Strategy Pattern interface ───

    @Override
    public String name() {
        return "MetaLlmStrategy";
    }

    @Override
    public boolean canHandle(DeckBuildContext context) {
        // We can handle if there are meta decks in the DB
        return !metaSynergyService.getLatestMetaDecks().isEmpty();
    }

    @Override
    public DeckBuildResult build(DeckBuildContext context) {
        List<Card> userCards = context.getPlayerCards();
        boolean isAdvancedBuilder = context.getLockedCardNames() != null
                && !context.getLockedCardNames().isEmpty();

        // Thread-safe log tag: includes forced WC for parallel multi-deck tracing
        String logTag = context.getForcedWinCondition() != null
                ? name() + "|" + context.getForcedWinCondition()
                : name();

        log.info("[{}] ═══ BUILD START ═══ player={}, cards={}, advanced={}, trophies={}",
                logTag, context.getPlayerTag(), userCards.size(), isAdvancedBuilder,
                context.getBestTrophies());

        // ─── Step 1: Collect meta intelligence ───
        String anchorWC = findAnchorWC(context, userCards, isAdvancedBuilder);
        List<MetaDeckEntity> metaDecks = findRelevantMetaDecks(anchorWC, context, userCards, isAdvancedBuilder);

        if (metaDecks.isEmpty()) {
            log.info("[{}] Step 1: ✗ No meta decks found → falling back", logTag);
            return null;
        }

        // Deduplicate meta decks by card name set (different evo combos = same deck for us)
        List<MetaDeckEntity> uniqueMetaDecks = deduplicateByCardNames(metaDecks);
        
        // Score and sort decks by how well they match the user's collection
        uniqueMetaDecks.sort((a, b) -> Integer.compare(
                metaSynergyService.calculateMatchScore(b, userCards, null),
                metaSynergyService.calculateMatchScore(a, userCards, null)));

        // Filter out low-match-score decks (keep minimum 2 as safety net)
        int MIN_MATCH_SCORE = 8; // At least ~3 cards in common
        int MIN_REFERENCE_DECKS = 2;
        List<MetaDeckEntity> filteredDecks = new ArrayList<>();
        for (MetaDeckEntity d : uniqueMetaDecks) {
            int score = metaSynergyService.calculateMatchScore(d, userCards, null);
            if (score >= MIN_MATCH_SCORE || filteredDecks.size() < MIN_REFERENCE_DECKS) {
                filteredDecks.add(d);
            }
        }
        uniqueMetaDecks = filteredDecks;

        log.info("[{}] Step 1: {} meta decks found, {} unique after dedup, {} after match score filter (threshold={})",
                logTag, metaDecks.size(), filteredDecks.size(), uniqueMetaDecks.size(), MIN_MATCH_SCORE);

        // Build card lookup map (needed for combo ownership filtering and validation)
        Map<String, Card> cardMap = userCards.stream()
                .collect(Collectors.toMap(Card::getName, Function.identity(), (a, b) -> a));

        // ─── Step 2: Build meta context ───
        Map<String, Double> coOccurrence = anchorWC != null
                ? metaSynergyService.calculateCoOccurrence(anchorWC, metaDecks)
                : Map.of();

        // Compute mandatory combos (≥70%) and strong associations (40-69%), filtered by user ownership
        Set<String> mandatoryCombos = anchorWC != null
                ? metaSynergyService.detectComboPartners(anchorWC, metaDecks)
                : Set.of();
        mandatoryCombos = new HashSet<>(mandatoryCombos);
        mandatoryCombos.retainAll(cardMap.keySet()); // only cards user owns

        Map<String, Double> strongAssociations = coOccurrence.entrySet().stream()
                .filter(e -> e.getValue() >= MetaSynergyService.STRONG_THRESHOLD
                          && e.getValue() < MetaSynergyService.COMBO_THRESHOLD)
                .filter(e -> cardMap.containsKey(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        String metaContext = metaSynergyService.buildMetaContext(anchorWC, uniqueMetaDecks, cardMap);

        // Log the meta decks being sent to LLM
        log.info("[{}] Step 2: Sending {} reference decks to LLM for WC: '{}'",
                logTag, Math.min(uniqueMetaDecks.size(), 5), anchorWC);
        for (int i = 0; i < Math.min(uniqueMetaDecks.size(), 5); i++) {
            MetaDeckEntity d = uniqueMetaDecks.get(i);
            List<String> names = metaSynergyService.parseCardNames(d.getCardsJson());
            log.info("[{}] Reference Deck {}: [{}] (usage={}, type={}, winCons={})",
                    logTag, i + 1, String.join(", ", names), d.getUsageCount(), d.getGameType(), d.getWinConditions());
        }

        if (!mandatoryCombos.isEmpty()) {
            log.info("[{}] Step 2: MANDATORY COMBOS for '{}': {}", logTag, anchorWC, mandatoryCombos);
        }
        if (!strongAssociations.isEmpty()) {
            log.info("[{}] Step 2: Strong associations for '{}': {}", logTag, anchorWC,
                    strongAssociations.entrySet().stream().limit(5)
                            .map(e -> String.format("%s=%.0f%%", e.getKey(), e.getValue()))
                            .collect(Collectors.joining(", ")));
        }

        // ─── Step 3: Prepare user cards for LLM ───
        List<SimplifiedCard> simplified = userCards.stream()
                .map(this::toSimplified).toList();

        // ─── Step 4: LLM call + validation loop ───
        List<String> previousErrors = new ArrayList<>();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            log.info("[{}] Step 4: LLM attempt {}/{}", logTag, attempt + 1, MAX_RETRIES);

            LlmDeckSuggestion suggestion;
            if (isAdvancedBuilder) {
                suggestion = llmService.generateDeckCompletion(
                        simplified, context.getLockedCardNames(),
                        context.getPlayStyle(), context.getBestTrophies(),
                        context.getSupportCards(), metaContext,
                        previousErrors);
            } else {
                suggestion = llmService.generateDeckRecommendation(
                        simplified, context.getBestTrophies(),
                        context.getSupportCards(), metaContext,
                        previousErrors, context.getForcedWinCondition(),
                        context.getForcedGameType(), mandatoryCombos, strongAssociations);
            }

            if (suggestion == null || suggestion.getCards() == null
                    || suggestion.getCards().isEmpty()) {
                log.warn("[{}] LLM returned empty suggestion", logTag);
                previousErrors.add("LLM returned empty or null response. Please try again.");
                continue;
            }

            // Log reasoning steps for traceability
            if (suggestion.getReasoningSteps() != null && !suggestion.getReasoningSteps().isEmpty()) {
                log.info("[{}] LLM reasoning:", logTag);
                for (String step : suggestion.getReasoningSteps()) {
                    log.info("[{}]   → {}", logTag, step);
                }
            }

            // ─── Step 5: Validate LLM output ───
            List<String> validationErrors = new ArrayList<>();
            List<Card> deck = validateAndMapDeck(suggestion, cardMap, context.getBestTrophies(),
                    validationErrors, mandatoryCombos, anchorWC);

            if (!validationErrors.isEmpty()) {
                log.warn("[{}] Step 5: Validation failed ({} errors): {}",
                        logTag, validationErrors.size(), validationErrors);
                previousErrors.addAll(validationErrors);
                continue;
            }

            if (deck.size() != 8) {
                String err = "Deck size is " + deck.size() + " instead of 8. You must select exactly 8 cards.";
                log.warn("[{}] Step 5: {}", logTag, err);
                previousErrors.add(err);
                continue;
            }

            // ─── Step 6: Promote eligible cards to special slots ───
            promoteSpecialForms(deck, cardMap, context.getBestTrophies(), logTag);

            // ─── Success! ───
            log.info("[{}] ═══ BUILD SUCCESS ═══ strategy={}, deck=[{}]",
                    logTag, suggestion.getStrategy(),
                    deck.stream().map(c -> c.getName() + "(L" + c.getLevel() + ")" + 
                                    (Boolean.TRUE.equals(c.getEvolved()) ? " (Evo)" : "") + 
                                    (Boolean.TRUE.equals(c.getIsHero()) ? " (Hero)" : ""))
                            .collect(Collectors.joining(", ")));

            return DeckBuildResult.builder()
                    .deck(deck)
                    .strategy(suggestion.getStrategy())
                    .tacticMessage(suggestion.getTactic())
                    .producedBy(name())
                    .selectedTowerTroop(suggestion.getSelectedTowerTroop())
                    .winCondition(anchorWC)
                    .build();
        }

        log.error("[{}] ═══ BUILD FAILED ═══ All {} retries exhausted", logTag, MAX_RETRIES);
        return null;
    }

    // ══════════════════════════════════════════════
    //  Step 1: WC Detection & Meta Deck Fetching
    // ══════════════════════════════════════════════

    private String findAnchorWC(DeckBuildContext context, List<Card> userCards, boolean isAdvanced) {
        // If a forced WC was specified (multi-deck generation), use it directly
        if (context.getForcedWinCondition() != null) {
            log.info("[{}] Step 1: Using forced WC: '{}'", name(), context.getForcedWinCondition());
            return context.getForcedWinCondition();
        }

        if (isAdvanced && context.getLockedCardNames() != null) {
            String wc = context.getLockedCardNames().stream()
                    .filter(WinConditionRegistry.WIN_CONDITIONS::containsKey)
                    .findFirst().orElse(null);
            if (wc != null) {
                log.info("[{}] Step 1: Found WC in locked cards: '{}'", name(), wc);
                return wc;
            }
        }

        // Quick Generate: find highest-level WC in user's collection
        // Check static WCs first
        Card bestStaticWC = userCards.stream()
                .filter(c -> WinConditionRegistry.WIN_CONDITIONS.containsKey(c.getName()))
                .max(Comparator.comparingInt(c -> c.getLevel() != null ? c.getLevel() : 0))
                .orElse(null);

        // Check dynamic WCs from meta data
        Set<String> dynamicWCs = metaSynergyService.getDynamicWinConditions();
        Card bestDynamicWC = userCards.stream()
                .filter(c -> dynamicWCs.contains(c.getName()))
                .max(Comparator.comparingInt(c -> c.getLevel() != null ? c.getLevel() : 0))
                .orElse(null);

        // Pick the one with the highest level
        if (bestStaticWC != null && bestDynamicWC != null) {
            int staticLvl = bestStaticWC.getLevel() != null ? bestStaticWC.getLevel() : 0;
            int dynamicLvl = bestDynamicWC.getLevel() != null ? bestDynamicWC.getLevel() : 0;
            if (staticLvl >= dynamicLvl) {
                log.info("[{}] Step 1: WC detection: static '{}' (lvl={}) wins over dynamic '{}' (lvl={})",
                        name(), bestStaticWC.getName(), staticLvl, bestDynamicWC.getName(), dynamicLvl);
                return bestStaticWC.getName();
            } else {
                log.info("[{}] Step 1: WC detection: dynamic '{}' (lvl={}) wins over static '{}' (lvl={})",
                        name(), bestDynamicWC.getName(), dynamicLvl, bestStaticWC.getName(), staticLvl);
                return bestDynamicWC.getName();
            }
        }

        if (bestStaticWC != null) {
            log.info("[{}] Step 1: Highest-level WC: '{}'", name(), bestStaticWC.getName());
            return bestStaticWC.getName();
        }
        if (bestDynamicWC != null) {
            log.info("[{}] Step 1: Dynamic WC: '{}'", name(), bestDynamicWC.getName());
            return bestDynamicWC.getName();
        }

        log.info("[{}] Step 1: No known WC found in user's collection", name());
        return null;
    }

    /**
     * Returns up to {@code count} distinct win conditions from the user's collection,
     * sorted by card level descending. Used by DeckService for multi-deck generation.
     */
    public List<String> findTopWinConditions(List<Card> userCards, int count) {
        // Compute viability score for each WC the user owns
        // Viability = (card level × 0.3) + (avg meta match score × 0.7)
        List<Card> wcCards = userCards.stream()
                .filter(c -> WinConditionRegistry.WIN_CONDITIONS.containsKey(c.getName()))
                .toList();

        if (wcCards.isEmpty()) {
            log.info("[{}] No WC cards found in user collection", name());
            return List.of();
        }

        // Calculate viability for each WC
        record WCCandidate(String name, String gameType, double viability) {}

        List<WCCandidate> candidates = wcCards.stream()
                .map(c -> {
                    double viability = metaSynergyService.calculateWCViability(c.getName(), userCards);
                    String gameType = WinConditionRegistry.WIN_CONDITIONS.get(c.getName());
                    return new WCCandidate(c.getName(), gameType, viability);
                })
                .sorted(Comparator.comparingDouble(WCCandidate::viability).reversed())
                .toList();

        // Log all candidates for transparency
        log.info("[{}] WC candidates ranked by viability:", name());
        for (int i = 0; i < Math.min(candidates.size(), 8); i++) {
            WCCandidate c = candidates.get(i);
            log.info("[{}]   #{} {} ({}) viability={}", name(), i + 1, c.name, c.gameType,
                    String.format("%.1f", c.viability));
        }

        // Select top WCs by viability (no game type restriction)
        double MIN_VIABILITY = 5.0; // Skip WCs with very poor meta fit
        List<String> selectedWCs = new ArrayList<>();

        for (WCCandidate c : candidates) {
            if (c.viability < MIN_VIABILITY) continue;
            if (!selectedWCs.contains(c.name)) {
                selectedWCs.add(c.name);
                if (selectedWCs.size() == count) break;
            }
        }

        // Fallback: if not enough above threshold, take best available
        if (selectedWCs.size() < count) {
            for (WCCandidate c : candidates) {
                if (!selectedWCs.contains(c.name)) {
                    selectedWCs.add(c.name);
                    if (selectedWCs.size() == count) break;
                }
            }
        }

        log.info("[{}] Selected WCs (viability-based): {}", name(), selectedWCs);
        return selectedWCs;
    }

    private List<MetaDeckEntity> findRelevantMetaDecks(String anchorWC, DeckBuildContext context,
                                                        List<Card> userCards, boolean isAdvanced) {
        if (isAdvanced && anchorWC == null && context.getLockedCardNames() != null) {
            return metaSynergyService.findByCards(context.getLockedCardNames());
        }

        if (anchorWC != null) {
            List<MetaDeckEntity> wcDecks = new ArrayList<>(metaSynergyService.findByWinCondition(anchorWC));
            
            // If we found very few unique decks, broaden search to any deck containing the card
            if (deduplicateByCardNames(wcDecks).size() <= 1) {
                log.info("[{}] Step 1: Few unique decks found for WC '{}'. Broadening search...", name(), anchorWC);
                List<MetaDeckEntity> moreDecks = metaSynergyService.findByCardInDeck(anchorWC);
                Set<Long> seenIds = wcDecks.stream().map(MetaDeckEntity::getId).collect(Collectors.toSet());
                for (MetaDeckEntity d : moreDecks) {
                    if (!seenIds.contains(d.getId())) {
                        wcDecks.add(d);
                    }
                }
            }
            
            if (wcDecks.isEmpty()) {
                wcDecks = metaSynergyService.findByCardInDeck(anchorWC);
            }
            return wcDecks;
        }

        // No-WC fallback: search by user's top 5 highest-level cards
        List<String> topCardNames = userCards.stream()
                .sorted(Comparator.comparingInt(
                        (Card c) -> c.getLevel() != null ? c.getLevel() : 0).reversed())
                .limit(5)
                .map(Card::getName)
                .toList();

        log.info("[{}] Step 1: No-WC fallback searching by top cards: {}", name(), topCardNames);
        return metaSynergyService.findByCards(topCardNames);
    }

    // ══════════════════════════════════════════════
    //  Deduplication
    // ══════════════════════════════════════════════

    /**
     * Removes meta decks that contain the exact same set of card names
     * (regardless of evo levels or ordering). Keeps the one with highest usage.
     */
    private List<MetaDeckEntity> deduplicateByCardNames(List<MetaDeckEntity> decks) {
        Map<String, MetaDeckEntity> seen = new LinkedHashMap<>();
        for (MetaDeckEntity deck : decks) {
            List<String> names = metaSynergyService.parseCardNames(deck.getCardsJson());
            String key = names.stream().sorted().collect(Collectors.joining(","));
            seen.merge(key, deck, (existing, incoming) ->
                    (existing.getUsageCount() != null ? existing.getUsageCount() : 0)
                            >= (incoming.getUsageCount() != null ? incoming.getUsageCount() : 0)
                            ? existing : incoming);
        }
        return new ArrayList<>(seen.values());
    }

    // ══════════════════════════════════════════════
    //  Step 5: Validation
    // ══════════════════════════════════════════════

    /**
     * Validates LLM suggestion against user's collection and maps to Card objects.
     * Returns the valid deck (may be <8 cards) and populates validationErrors.
     * Also validates slot positions and mandatory combo inclusion.
     */
    private List<Card> validateAndMapDeck(LlmDeckSuggestion suggestion,
                                           Map<String, Card> cardMap,
                                           Integer bestTrophies,
                                           List<String> validationErrors,
                                           Set<String> mandatoryCombos,
                                           String anchorWC) {
        List<Card> deck = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        List<String> intendedEvos = suggestion.getSelectedEvolutions() != null
                ? suggestion.getSelectedEvolutions() : List.of();
        List<String> intendedHeroes = suggestion.getSelectedHeroes() != null
                ? suggestion.getSelectedHeroes() : List.of();

        // Truncate to first 8 cards — LLM sometimes returns 9-10 cards.
        // The first 8 are always the intentional deck (ordered by slot rules),
        // so we safely ignore overflow cards instead of wasting a retry.
        List<LlmDeckSuggestion.LlmCardSuggestion> cardSuggestions = suggestion.getCards();
        if (cardSuggestions.size() > 8) {
            log.warn("[Validation] LLM returned {} cards, truncating to first 8", cardSuggestions.size());
            cardSuggestions = cardSuggestions.subList(0, 8);
        }

        for (LlmDeckSuggestion.LlmCardSuggestion cs : cardSuggestions) {
            String cardName = cs.getName();

            // Check duplicate
            if (usedNames.contains(cardName)) {
                validationErrors.add("Duplicate card '" + cardName + "' in deck. Each card can only appear once.");
                continue;
            }

            // Check ownership
            Card userCard = cardMap.get(cardName);
            if (userCard == null) {
                validationErrors.add("Card '" + cardName + "' is NOT in the player's collection. " +
                        "You MUST only use cards from the provided collection list.");
                continue;
            }

            // Clone card to avoid mutating the original
            Card deckCard = cloneCard(userCard);

            // Validate evo
            if (intendedEvos.contains(cardName)) {
                if (!Boolean.TRUE.equals(userCard.getEvolved())) {
                    validationErrors.add("You tried to evolve '" + cardName +
                            "' but the player has NOT unlocked its evolution. " +
                            "Only evolve cards where Evo=true in the collection.");
                    deckCard.setEvolved(false);
                } else {
                    deckCard.setEvolved(true);
                }
            } else {
                deckCard.setEvolved(false);
            }

            // Validate hero
            if (intendedHeroes.contains(cardName)) {
                if (!Boolean.TRUE.equals(userCard.getIsHero())) {
                    validationErrors.add("You tried to use hero form of '" + cardName +
                            "' but the player does NOT have this hero form. " +
                            "Only use heroes where Hero=true in the collection.");
                    deckCard.setIsHero(false);
                } else {
                    deckCard.setIsHero(true);
                }
            } else {
                deckCard.setIsHero(false);
            }

            deck.add(deckCard);
            usedNames.add(cardName);
        }

        // Validate evo/hero limits
        if (deck.size() == 8) {
            int trophies = bestTrophies != null ? bestTrophies : 0;
            int maxEvos = trophies < 3000 ? 1 : 2;
            int maxHeroes = trophies < 3000 ? 1 : 2;
            int maxTotal = trophies < 3000 ? 2 : 3;

            long evoCount = deck.stream().filter(c -> Boolean.TRUE.equals(c.getEvolved())).count();
            long heroCount = deck.stream().filter(c -> Boolean.TRUE.equals(c.getIsHero())).count();

            if (evoCount > maxEvos) {
                validationErrors.add("Too many evolutions: " + evoCount + " but max allowed is " + maxEvos +
                        " for " + trophies + " trophies.");
            }
            if (heroCount > maxHeroes) {
                validationErrors.add("Too many heroes: " + heroCount + " but max allowed is " + maxHeroes +
                        " for " + trophies + " trophies.");
            }
            if (evoCount + heroCount > maxTotal) {
                validationErrors.add("Total special cards (evo+hero): " + (evoCount + heroCount) +
                        " but max allowed is " + maxTotal + " for " + trophies + " trophies.");
            }

            // ─── Slot Position Validation ───
            for (int i = 0; i < deck.size(); i++) {
                Card c = deck.get(i);
                boolean isEvo = Boolean.TRUE.equals(c.getEvolved());
                boolean isHero = Boolean.TRUE.equals(c.getIsHero());

                if (i == 0 && isHero) {
                    validationErrors.add("SLOT VIOLATION: Index 0 is the EVOLUTION slot, not Hero. " +
                            "Move '" + c.getName() + "' (Hero) to Index 1 or 2.");
                }
                if (i == 1 && isEvo) {
                    validationErrors.add("SLOT VIOLATION: Index 1 is the HERO slot, not Evolution. " +
                            "Move '" + c.getName() + "' (Evo) to Index 0 or 2.");
                }
                if (i >= 3 && (isEvo || isHero)) {
                    validationErrors.add("SLOT VIOLATION: Index " + i + " must be a NORMAL card. " +
                            "'" + c.getName() + "' (" + (isEvo ? "Evolution" : "Hero") + ") cannot be here. " +
                            "Move it to Index 0, 1, or 2.");
                }
            }

            // ─── Spell Count Validation (max 2 spells) ───
            List<String> spellsInDeck = deck.stream()
                    .map(Card::getName)
                    .filter(SPELL_CARDS::contains)
                    .toList();
            if (spellsInDeck.size() > MAX_SPELLS) {
                validationErrors.add("SPELL VIOLATION: Deck contains " + spellsInDeck.size() +
                        " spells " + spellsInDeck + " but maximum allowed is " + MAX_SPELLS +
                        ". Remove " + (spellsInDeck.size() - MAX_SPELLS) + " spell(s) and replace with a troop or building. " +
                        "Keep exactly 1 small spell and 1 big spell.");
            }

            // ─── Mandatory Combo Validation ───
            if (mandatoryCombos != null && !mandatoryCombos.isEmpty()) {
                for (String combo : mandatoryCombos) {
                    if (!usedNames.contains(combo)) {
                        validationErrors.add("MANDATORY COMBO VIOLATION: '" + combo +
                                "' has ≥70% co-occurrence with your anchor card and is in the player's collection, " +
                                "but was NOT included. You MUST add it to the deck.");
                    }
                }
            }

            // ─── Anchor WC Validation (CRITICAL) ───
            if (anchorWC != null && !usedNames.contains(anchorWC)) {
                validationErrors.add("CRITICAL: The forced win condition '" + anchorWC +
                        "' is NOT in the generated deck! The anchor card MUST be included. " +
                        "Add '" + anchorWC + "' to the deck and remove the weakest card.");
            }
        }

        return deck;
    }

    // ══════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════

    private Card cloneCard(Card original) {
        Card clone = new Card();
        clone.setName(original.getName());
        clone.setId(original.getId());
        clone.setLevel(original.getLevel());
        clone.setElixirCost(original.getElixirCost());
        clone.setRarity(original.getRarity());
        clone.setType(original.getType());
        clone.setImageUri(original.getImageUri());
        clone.setImageUriEvolved(original.getImageUriEvolved());
        clone.setImageUriHero(original.getImageUriHero());
        clone.setEvolved(original.getEvolved());
        clone.setIsHero(original.getIsHero());
        return clone;
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

    // ══════════════════════════════════════════════
    //  Step 6: Post-Processing — Promote to Special Slots
    // ══════════════════════════════════════════════

    /**
     * After the LLM builds a valid 8-card deck, this method checks if any cards
     * in normal slots (3-7) have unlocked Evo/Hero forms in the player's collection.
     * If special slots (0=Evo, 1=Hero, 2=Flex) are occupied by normal cards,
     * we swap in the eligible card and activate its special form.
     *
     * This is a "free power upgrade" — same 8 cards, same synergy, just better forms.
     */
    private void promoteSpecialForms(List<Card> deck, Map<String, Card> cardMap,
                                      Integer bestTrophies, String logTag) {
        if (deck.size() != 8) return;

        int trophies = bestTrophies != null ? bestTrophies : 0;
        int maxEvos = trophies < 3000 ? 1 : 2;
        int maxHeroes = trophies < 3000 ? 1 : 2;
        int maxTotal = trophies < 3000 ? 2 : 3;

        // Count currently active evos and heroes
        long activeEvos = deck.stream().filter(c -> Boolean.TRUE.equals(c.getEvolved())).count();
        long activeHeroes = deck.stream().filter(c -> Boolean.TRUE.equals(c.getIsHero())).count();

        // ─── Promote Evos to Index 0 ───
        if (!Boolean.TRUE.equals(deck.get(0).getEvolved()) && activeEvos < maxEvos
                && (activeEvos + activeHeroes) < maxTotal) {
            // First: check if the card already at Index 0 has an evo form
            Card atSlot0 = deck.get(0);
            Card playerCard0 = cardMap.get(atSlot0.getName());
            if (playerCard0 != null && Boolean.TRUE.equals(playerCard0.getEvolved())) {
                atSlot0.setEvolved(true);
                activeEvos++;
                log.info("[{}] ⬆ Activated in-place Evo for '{}' at Index 0", logTag, atSlot0.getName());
            } else {
                // Search other positions for an evo candidate
                Card bestEvo = null;
                int bestEvoIdx = -1;
                for (int i = 1; i < deck.size(); i++) {
                    if (Boolean.TRUE.equals(deck.get(i).getIsHero())) continue;
                    Card playerCard = cardMap.get(deck.get(i).getName());
                    if (playerCard != null && Boolean.TRUE.equals(playerCard.getEvolved())
                            && !Boolean.TRUE.equals(deck.get(i).getEvolved())) {
                        if (bestEvo == null || (deck.get(i).getLevel() != null
                                && deck.get(i).getLevel() > (bestEvo.getLevel() != null ? bestEvo.getLevel() : 0))) {
                            bestEvo = deck.get(i);
                            bestEvoIdx = i;
                        }
                    }
                }
                if (bestEvo != null) {
                    Card displaced = deck.get(0);
                    deck.set(0, bestEvo);
                    deck.set(bestEvoIdx, displaced);
                    bestEvo.setEvolved(true);
                    activeEvos++;
                    log.info("[{}] ⬆ Promoted '{}' to Evo slot (Index 0)", logTag, bestEvo.getName());
                }
            }
        }

        // ─── Promote Heroes to Index 1 ───
        if (!Boolean.TRUE.equals(deck.get(1).getIsHero()) && activeHeroes < maxHeroes
                && (activeEvos + activeHeroes) < maxTotal) {
            // First: check if the card already at Index 1 has a hero form
            Card atSlot1 = deck.get(1);
            Card playerCard1 = cardMap.get(atSlot1.getName());
            if (playerCard1 != null && Boolean.TRUE.equals(playerCard1.getIsHero())) {
                atSlot1.setIsHero(true);
                activeHeroes++;
                log.info("[{}] ⬆ Activated in-place Hero for '{}' at Index 1", logTag, atSlot1.getName());
            } else {
                // Search positions 2-7 for a hero candidate
                Card bestHero = null;
                int bestHeroIdx = -1;
                for (int i = 2; i < deck.size(); i++) {
                    if (Boolean.TRUE.equals(deck.get(i).getEvolved())) continue;
                    Card playerCard = cardMap.get(deck.get(i).getName());
                    if (playerCard != null && Boolean.TRUE.equals(playerCard.getIsHero())
                            && !Boolean.TRUE.equals(deck.get(i).getIsHero())) {
                        if (bestHero == null || (deck.get(i).getLevel() != null
                                && deck.get(i).getLevel() > (bestHero.getLevel() != null ? bestHero.getLevel() : 0))) {
                            bestHero = deck.get(i);
                            bestHeroIdx = i;
                        }
                    }
                }
                if (bestHero != null) {
                    Card displaced = deck.get(1);
                    deck.set(1, bestHero);
                    deck.set(bestHeroIdx, displaced);
                    bestHero.setIsHero(true);
                    activeHeroes++;
                    log.info("[{}] ⬆ Promoted '{}' to Hero slot (Index 1)", logTag, bestHero.getName());
                }
            }
        }

        // ─── Promote to Flex slot (Index 2) ───
        if (!Boolean.TRUE.equals(deck.get(2).getEvolved()) && !Boolean.TRUE.equals(deck.get(2).getIsHero())
                && (activeEvos + activeHeroes) < maxTotal) {
            // First: check if the card already at Index 2 has an evo or hero form
            Card atSlot2 = deck.get(2);
            Card playerCard2 = cardMap.get(atSlot2.getName());
            boolean activated = false;
            if (playerCard2 != null) {
                if (Boolean.TRUE.equals(playerCard2.getEvolved()) && activeEvos < maxEvos) {
                    atSlot2.setEvolved(true);
                    activeEvos++;
                    activated = true;
                    log.info("[{}] ⬆ Activated in-place Evo for '{}' at Flex slot (Index 2)", logTag, atSlot2.getName());
                } else if (Boolean.TRUE.equals(playerCard2.getIsHero()) && activeHeroes < maxHeroes) {
                    atSlot2.setIsHero(true);
                    activeHeroes++;
                    activated = true;
                    log.info("[{}] ⬆ Activated in-place Hero for '{}' at Flex slot (Index 2)", logTag, atSlot2.getName());
                }
            }

            if (!activated) {
                // Search positions 3-7 for an evo or hero candidate
                Card bestFlex = null;
                int bestFlexIdx = -1;
                boolean flexIsEvo = false;

                for (int i = 3; i < deck.size(); i++) {
                    if (Boolean.TRUE.equals(deck.get(i).getEvolved()) || Boolean.TRUE.equals(deck.get(i).getIsHero())) continue;
                    Card playerCard = cardMap.get(deck.get(i).getName());
                    if (playerCard == null) continue;

                    if (Boolean.TRUE.equals(playerCard.getEvolved()) && activeEvos < maxEvos) {
                        if (bestFlex == null || (deck.get(i).getLevel() != null
                                && deck.get(i).getLevel() > (bestFlex.getLevel() != null ? bestFlex.getLevel() : 0))) {
                            bestFlex = deck.get(i);
                            bestFlexIdx = i;
                            flexIsEvo = true;
                        }
                    } else if (Boolean.TRUE.equals(playerCard.getIsHero()) && activeHeroes < maxHeroes && bestFlex == null) {
                        bestFlex = deck.get(i);
                        bestFlexIdx = i;
                        flexIsEvo = false;
                    }
                }
                if (bestFlex != null) {
                    Card displaced = deck.get(2);
                    deck.set(2, bestFlex);
                    deck.set(bestFlexIdx, displaced);
                    if (flexIsEvo) {
                        bestFlex.setEvolved(true);
                        log.info("[{}] ⬆ Promoted '{}' to Flex Evo slot (Index 2)", logTag, bestFlex.getName());
                    } else {
                        bestFlex.setIsHero(true);
                        log.info("[{}] ⬆ Promoted '{}' to Flex Hero slot (Index 2)", logTag, bestFlex.getName());
                    }
                }
            }
        }
    }
}
