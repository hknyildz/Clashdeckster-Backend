package com.deftgray.clashproxy.service;

import com.deftgray.clashproxy.dto.CardDto;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA);

    @org.springframework.beans.factory.annotation.Value("${openrouter.url}")
    private String openRouterUrl;

    @org.springframework.beans.factory.annotation.Value("${openrouter.api.key}")
    private String apiKey;

    @org.springframework.beans.factory.annotation.Value("${openrouter.model}")
    private String modelName;

    // ══════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════

    public LlmDeckSuggestion generateDeckRecommendation(List<SimplifiedCard> cards,
                                                         Integer bestTrophies,
                                                         List<CardDto> supportCards,
                                                         String metaContext,
                                                         List<String> previousErrors,
                                                         String forcedWinCondition,
                                                         String forcedGameType,
                                                         Set<String> mandatoryCombos,
                                                         Map<String, Double> strongAssociations) {
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = " ";
            log.warn("Using hardcoded API key (NOT RECOMMENDED for production)");
        }

        String prompt = createPrompt(cards, supportCards, previousErrors,
                forcedWinCondition, forcedGameType, mandatoryCombos, strongAssociations);
        log.debug("Sending prompt to LLM: {}", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String systemContent = getSystemPromptBase(bestTrophies, false, null, supportCards, metaContext);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(
                        Map.of("role", "system", "content", systemContent),
                        Map.of("role", "user", "content", prompt)));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            String response = restTemplate.postForObject(openRouterUrl, entity, String.class);
            log.info("Received response from LLM: {}", response != null ? response.replaceAll("\\n", "").replaceAll("\\s+", " ").trim() : "null");
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Error calling OpenRouter API", e);
            return null;
        }
    }

    public LlmDeckSuggestion generateDeckCompletion(List<SimplifiedCard> collection,
                                                     List<String> currentDeckNames,
                                                     String playStyle,
                                                     Integer bestTrophies,
                                                     List<CardDto> supportCards,
                                                     String metaContext,
                                                     List<String> previousErrors) {
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = " ";
            log.warn("Using hardcoded API key (NOT RECOMMENDED for production)");
        }

        String prompt = createCompletionPrompt(collection, currentDeckNames, playStyle, supportCards, previousErrors);
        log.debug("Sending completion prompt to LLM: {}", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String systemContent = getSystemPromptBase(bestTrophies, true, playStyle, supportCards, metaContext);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(
                        Map.of("role", "system", "content", systemContent),
                        Map.of("role", "user", "content", prompt)));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            String response = restTemplate.postForObject(openRouterUrl, entity, String.class);
            log.info("Received response from LLM: {}", response != null ? response.replaceAll("\\n", "").replaceAll("\\s+", " ").trim() : "null");
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Error calling OpenRouter API", e);
            return null;
        }
    }

    // ══════════════════════════════════════════════
    //  Prompt Builders
    // ══════════════════════════════════════════════

    private String createPrompt(List<SimplifiedCard> cards, List<CardDto> supportCards,
                                 List<String> previousErrors, String forcedWinCondition,
                                 String forcedGameType, Set<String> mandatoryCombos,
                                 Map<String, Double> strongAssociations) {
        String cardList = cards.stream()
                .sorted((c1, c2) -> Integer.compare(c2.getLevel() != null ? c2.getLevel() : 0,
                        c1.getLevel() != null ? c1.getLevel() : 0))
                .map(c -> String.format("%s (Lvl: %d, Evo: %s, Hero: %s)", c.getName(), c.getLevel(), c.isEvolved(),
                        c.isHero()))
                .collect(Collectors.joining("\n"));

        String towerInfo = buildTowerTroopInfo(supportCards);

        StringBuilder sb = new StringBuilder();
        sb.append("=== PLAYER COLLECTION (sorted by level, highest first) ===\n");
        sb.append(cardList);
        sb.append(towerInfo);

        // ─── BUILD INSTRUCTIONS ───
        sb.append("\n\n=== BUILD INSTRUCTIONS ===\n");
        sb.append("Follow the PRIORITY ORDER from the system prompt exactly.\n\n");

        // Priority 1: Primary Anchor
        if (forcedWinCondition != null && !forcedWinCondition.isEmpty()) {
            sb.append("PRIORITY 1 — PRIMARY ANCHOR: '").append(forcedWinCondition).append("'");
            if (forcedGameType != null && !forcedGameType.isEmpty()) {
                sb.append(" (Archetype: ").append(forcedGameType).append(")");
            }
            sb.append("\nThis card MUST be in the deck. Build everything around it.\n");
            sb.append("The 'strategy' field in your JSON MUST be '")
              .append(forcedGameType != null ? forcedGameType : "Hybrid").append("'.\n\n");
        }

        // Priority 2: Mandatory Combos
        if (mandatoryCombos != null && !mandatoryCombos.isEmpty()) {
            sb.append("PRIORITY 2 — MANDATORY COMBOS (≥70% co-occurrence, MUST INCLUDE):\n");
            for (String combo : mandatoryCombos) {
                sb.append("  ★ ").append(combo).append("\n");
            }
            sb.append("These cards MUST be in the deck even if their level is low.\n\n");
        }

        // Recommended synergies
        if (strongAssociations != null && !strongAssociations.isEmpty()) {
            sb.append("RECOMMENDED SYNERGIES (40-69% co-occurrence, prefer if possible):\n");
            strongAssociations.entrySet().stream().limit(5).forEach(e ->
                    sb.append("  • ").append(e.getKey())
                      .append(" (").append(String.format("%.0f%%", e.getValue())).append(")\n"));
            sb.append("\n");
        }

        // Priority 3: Reference Deck Adherence
        sb.append("PRIORITY 3 — REFERENCE DECK ADHERENCE:\n");
        sb.append("  When filling remaining slots, you MUST strictly prioritize cards that appear in the Reference Meta Decks provided above.\n");
        sb.append("  Do NOT pick random high-level cards to fill a role if a card from the reference decks fits that role.\n\n");

        // Priority 4: Role Balance
        sb.append("PRIORITY 4 — ROLE BALANCE CHECKLIST (MAX 2 SPELLS TOTAL):\n");
        sb.append("  SPELL REFERENCE (these are ALL the spell cards — count them carefully):\n");
        sb.append("    Small Spells: The Log, Zap, Arrows, Giant Snowball,  Royal Delivery\n");
        sb.append("    Big Spells: Fireball, Poison, Rocket, Lightning, Earthquake, Tornado, Freeze, Rage\n");
        sb.append("  □ Include exactly 1 Small Spell and exactly 1 Big Spell = 2 spells total.\n");
        sb.append("  □ HARD LIMIT: NEVER include more than 2 spells. If you already have 2 spells, every other card MUST be a troop or building.\n");
        sb.append("  □ Cards like Graveyard, Goblin Barrel, Mirror are NOT spells for this rule.\n");
        sb.append("  □ Before finalizing, COUNT your spells. If count > 2, remove the weakest spell and add a troop/building.\n");
        sb.append("  □ At least 1 Air Defense card (a troop or building that can target air units)\n\n");

        // Priority 5: Level Optimization
        sb.append("PRIORITY 5 — LEVEL OPTIMIZATION:\n");
        sb.append("  Only after checking reference decks and roles, pick the HIGHEST LEVEL cards that fit the archetype.\n");
        sb.append("  ONLY use cards from the collection above. Do NOT invent cards.\n\n");

        // Previous errors
        if (previousErrors != null && !previousErrors.isEmpty()) {
            sb.append("⚠️ YOUR PREVIOUS ATTEMPT HAD ERRORS. FIX THESE:\n");
            for (String err : previousErrors) {
                sb.append("- ").append(err).append("\n");
            }
            sb.append("Generate a corrected deck that avoids ALL errors above.\n");
        }

        return sb.toString();
    }

    private String createCompletionPrompt(List<SimplifiedCard> collection, List<String> currentDeckNames,
                                           String playStyle, List<CardDto> supportCards,
                                           List<String> previousErrors) {
        String cardList = collection.stream()
                .sorted((c1, c2) -> Integer.compare(c2.getLevel() != null ? c2.getLevel() : 0,
                        c1.getLevel() != null ? c1.getLevel() : 0))
                .map(c -> String.format("%s (Lvl: %d, Evo: %s, Hero: %s)", c.getName(), c.getLevel(), c.isEvolved(),
                        c.isHero()))
                .collect(Collectors.joining("\n"));

        String alreadySelected = String.join(", ", currentDeckNames);
        String towerInfo = buildTowerTroopInfo(supportCards);

        StringBuilder sb = new StringBuilder();
        sb.append("=== PLAYER COLLECTION (sorted by level, highest first) ===\n");
        sb.append(cardList);
        sb.append(towerInfo);
        sb.append("\n\n=== BUILD INSTRUCTIONS ===\n");
        sb.append("I want to build a '").append(playStyle).append("' deck.\n");
        sb.append("I have ALREADY selected these cards: ").append(alreadySelected).append("\n");
        sb.append("Complete the deck to exactly 8 cards from my collection.\n\n");
        sb.append("PRIORITY ORDER:\n");
        sb.append("1. SYNERGY FIRST: Ensure excellent synergy for the ").append(playStyle).append(" archetype.\n");
        sb.append("2. META COMBOS: Include core combo partners from the META DATA if provided.\n");
        sb.append("3. ROLE BALANCE: At least 1 Small Spell, 1 Big Spell, 1 Air Defense.\n");
        sb.append("4. LEVELS SECOND: For flex slots, pick the highest-level cards that fit.\n");
        sb.append("5. ONLY use cards from the collection above.\n");

        if (previousErrors != null && !previousErrors.isEmpty()) {
            sb.append("\n⚠️ YOUR PREVIOUS ATTEMPT HAD ERRORS. FIX THESE:\n");
            for (String err : previousErrors) {
                sb.append("- ").append(err).append("\n");
            }
            sb.append("Generate a corrected deck that avoids ALL errors above.\n");
        }

        return sb.toString();
    }

    // ══════════════════════════════════════════════
    //  System Prompt
    // ══════════════════════════════════════════════

    private String getSystemPromptBase(Integer bestTrophies, boolean isCompletion, String playStyle,
                                        List<CardDto> supportCards, String metaContext) {
        int trophies = bestTrophies != null ? bestTrophies : 0;
        int evoLimit = trophies < 3000 ? 1 : 2;
        int heroLimit = trophies < 3000 ? 1 : 2;
        int totalLimit = trophies < 3000 ? 2 : 3;

        StringBuilder system = new StringBuilder();

        // ─── SECTION A: IDENTITY ───
        system.append("You are an elite Clash Royale Deck Building AI.\n\n");

        // ─── SECTION B: HARD CONSTRAINTS (NON-NEGOTIABLE) ───
        system.append("=== HARD CONSTRAINTS (VIOLATION = INSTANT REJECTION) ===\n\n");

        // B1: Slot Mechanics
        system.append("B1. SLOT MECHANICS (card ordering in the 'cards' array):\n");
        system.append("  - Index 0: EVOLUTION SLOT — If you pick an evolved card, it MUST be here. Otherwise normal card.\n");
        system.append("  - Index 1: HERO SLOT — If you pick a hero card, it MUST be here. Otherwise normal card.\n");
        system.append("  - Index 2: FLEX SPECIAL SLOT — Can hold a 2nd evolution, 2nd hero, or normal card.\n");
        system.append("  - Index 3-7: NORMAL CARDS ONLY — NEVER place an evolution or hero here.\n");
        system.append("  If you have 0 evolutions, Index 0 is a normal card. If 0 heroes, Index 1 is normal.\n\n");

        // B2: Special Card Limits
        system.append("B2. SPECIAL CARD LIMITS:\n");
        system.append("  - Maximum Evolutions: ").append(evoLimit).append("\n");
        system.append("  - Maximum Heroes: ").append(heroLimit).append("\n");
        system.append("  - Maximum Total (Evos + Heroes combined): ").append(totalLimit).append("\n");
        system.append("  - Do NOT force evos/heroes if they break synergy. 0 evos and 0 heroes is valid.\n");
        system.append("  - ONLY evolve cards where Evo=true in collection. ONLY use hero where Hero=true.\n\n");

        // B3: Tower Troop
        if (supportCards != null && !supportCards.isEmpty()) {
            int maxTowerLevel = supportCards.stream()
                    .mapToInt(sc -> sc.getLevel() != null ? sc.getLevel() : 1)
                    .max().orElse(1);
            system.append("B3. TOWER TROOP:\n");
            system.append("  - Pick exactly one tower troop from the player's available list.\n");
            system.append("  - Highest tower troop level: ").append(maxTowerLevel).append(". ");
            system.append("Do NOT pick one more than 2 levels lower.\n\n");
        }

        // B4: Collection Constraint
        system.append("B4. COLLECTION ONLY: Every card MUST exist in the player's collection. ");
        system.append("Do NOT invent cards.\n\n");

        // B5: Spell Limit
        system.append("B5. SPELL LIMIT (HARD CONSTRAINT):\n");
        system.append("  - Maximum 2 spells in the entire deck. Exactly 1 small + 1 big = 2 total.\n");
        system.append("  - Spell cards are ONLY: The Log, Zap, Arrows, Giant Snowball, Barbarian Barrel, Royal Delivery, ");
        system.append("Fireball, Poison, Rocket, Lightning, Earthquake, Tornado, Freeze, Rage.\n");
        system.append("  - Graveyard, Goblin Barrel, Mirror are NOT spells for this rule.\n");
        system.append("  - If you include 3+ spells, the deck WILL BE REJECTED.\n\n");

        // ─── SECTION C: BUILD PRIORITY (FOLLOW THIS ORDER) ───
        system.append("=== BUILD PRIORITY (FOLLOW THIS EXACT ORDER) ===\n\n");
        system.append("Priority 1: PRIMARY ANCHOR — The forced win condition (if specified) MUST be included.\n");
        system.append("Priority 2: MANDATORY COMBOS — Cards with ≥70% co-occurrence with the anchor. ");
        system.append("Include them EVEN IF their level is low (e.g. Level 9). ");
        system.append("Synergy > Levels.\n");
        system.append("Priority 3: ROLE BALANCE — Exactly 1 Small Spell + 1 Big Spell (2 total, NO MORE), 1 Air Defense.\n");
        system.append("Priority 4: LEVEL OPTIMIZATION — Fill remaining slots with HIGHEST LEVEL cards ");
        system.append("that fit the archetype. Only optimize levels AFTER priorities 1-3 are satisfied.\n\n");

        // ─── SECTION D: CHAIN OF THOUGHT ───
        system.append("=== CHAIN OF THOUGHT ===\n");
        system.append("Before outputting JSON, reason through these steps and document in 'reasoning_steps':\n");
        system.append("  Step 1: Place the Primary Anchor card.\n");
        system.append("  Step 2: Add all Mandatory Combo partners (explain why each is included, mention co-occurrence %).\n");
        system.append("  Step 3: Check role balance — do you have a small spell, big spell, air defense?\n");
        system.append("  Step 4: Fill remaining slots with highest-level cards that fit the archetype.\n");
        system.append("  Step 5: Assign slot positions — evos at Index 0, heroes at Index 1, flex at Index 2, rest at 3-7.\n");
        system.append("  Step 6: Self-check — COUNT your spells (must be ≤2), COUNT total cards (must be exactly 8), verify HARD CONSTRAINTS.\n\n");

        // ─── SECTION E: OUTPUT FORMAT ───
        system.append("=== OUTPUT FORMAT ===\n");
        system.append("Return ONLY a raw JSON object (no markdown, no ```json). Exact schema:\n");
        system.append("{\n");
        system.append("  \"reasoning_steps\": [\n");
        system.append("    \"Step 1: Placed Miner as Primary Anchor\",\n");
        system.append("    \"Step 2: Added Balloon (90% combo) despite Level 11 — synergy > levels\",\n");
        system.append("    \"Step 3: Added The Log (small spell) and Fireball (big spell). Musketeer for air defense.\",\n");
        system.append("    \"Step 4: Filled flex slots: Valkyrie(L14), Ice Golem(L13)\",\n");
        system.append("    \"Step 5: Skeletons(Evo) at Index 0, normals at 1-7\",\n");
        system.append("    \"Step 6: Self-check passed — 2 spells, air defense present, 1 evo at Index 0\"\n");
        system.append("  ],\n");
        system.append("  \"selected_evolutions\": [\"CardName\"],\n");
        system.append("  \"selected_heroes\": [],\n");
        system.append("  \"selected_tower_troop\": \"Tower Princess\",\n");
        system.append("  \"cards\": [\n");
        system.append("    {\"name\": \"...\", \"isEvolved\": true, \"isHero\": false, \"level\": 14},\n");
        system.append("    ... (exactly 8 cards, ordered by slot rules above)\n");
        system.append("  ],\n");
        system.append("  \"strategy\": \"Beatdown | Cycle/Control | Bait/Special | Siege | Bridge Spam | Hybrid\",\n");
        system.append("  \"tactic\": \"(SEE TACTIC RULES BELOW)\"\n");
        system.append("}\n\n");

        // ─── SECTION F: TACTIC QUALITY RULES ───
        system.append("=== TACTIC QUALITY RULES ===\n");
        system.append("The 'tactic' field must be an ACTIONABLE GAME PLAN, not a card-by-card description.\n");
        system.append("Write it as if you are a pro coach briefing a player before a match.\n\n");
        system.append("REQUIRED STRUCTURE (cover ALL of these):\n");
        system.append("1. WIN CONDITION TIMING: When and how to deploy the win condition (e.g., 'Place X-Bow at the bridge when opponent's elixir is low after defending a push').\n");
        system.append("2. KEY COMBOS: Specific card pairings and WHY (e.g., 'Use Tesla behind X-Bow to protect it from tank pushes' or 'Freeze + Graveyard on the tower when they waste their small spell').\n");
        system.append("3. DEFENSIVE GAMEPLAN: How to defend common threats (e.g., 'Against Hog Rider, use Tesla + Ice Spirit for a positive elixir trade').\n");
        system.append("4. ELIXIR MANAGEMENT: When to play aggressively vs. passively (e.g., 'Play defensive first minute, then counter-push with Miner + leftover troops').\n");
        system.append("5. SPELL TIMING: When to use spells (e.g., 'Save Fireball for Musketeer/Wizard. Use Log to reset charges and clear swarms at the bridge').\n\n");
        system.append("DO NOT just list what each card does. Every sentence must describe a PLAY, a TIMING, or a COMBO.\n");
        system.append("BAD: 'Tesla provides defense and Electro Spirit offers additional offense.'\n");
        system.append("GOOD: 'Plant Tesla in the center to pull Hog and Giant. After defending, drop X-Bow at the bridge while Tesla is still alive to tank for it.'\n");

        // ─── Completion-specific additions ───
        if (isCompletion) {
            system.append("\nADDITIONAL: Complete the deck to 8 cards. Include all cards the user already selected. ");
            system.append("Respect playstyle: ").append(playStyle).append(".\n");
        }

        // ─── META INTELLIGENCE ───
        system.append(buildMetaIntelligenceBlock(metaContext));

        return system.toString();
    }

    // ══════════════════════════════════════════════
    //  Response Parsing
    // ══════════════════════════════════════════════

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

                int startIndex = content.indexOf("{");
                int endIndex = content.lastIndexOf("}");
                if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
                    content = content.substring(startIndex, endIndex + 1);
                } else {
                    log.error("Could not find raw JSON object in the LLM response. Content: {}", content);
                    return null;
                }
                
                return objectMapper.readValue(content, LlmDeckSuggestion.class);
            }

            log.error("Invalid response structure: {}", jsonResponse);
            return null;
        } catch (Exception e) {
            log.error("Failed to parse LLM response", e);
            return null;
        }
    }

    // ══════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════

    private String buildTowerTroopInfo(List<CardDto> supportCards) {
        if (supportCards == null || supportCards.isEmpty()) {
            return "";
        }
        String towerList = supportCards.stream()
                .map(sc -> String.format("- %s (Level: %d, Max Level: %d, Rarity: %s)",
                        sc.getName(),
                        sc.getLevel() != null ? sc.getLevel() : 1,
                        sc.getMaxLevel() != null ? sc.getMaxLevel() : 1,
                        sc.getRarity() != null ? sc.getRarity() : "unknown"))
                .collect(Collectors.joining("\n"));
        return "\n\nTower Troops available (pick one for 'selected_tower_troop'):\n" + towerList;
    }

    /**
     * Wraps meta-context (reference decks + co-occurrence) with LLM instructions.
     * Returns empty string if no meta context is available.
     */
    private String buildMetaIntelligenceBlock(String metaContext) {
        if (metaContext == null || metaContext.isBlank()) {
            return "";
        }
        return "\n\n=== META INTELLIGENCE (PROVEN DECKS FROM TOP PLAYERS) ===" + metaContext
                + "\nUSE these reference decks as your PRIMARY guide for Priority 2 (Mandatory Combos). "
                + "Adapt the closest matching meta deck to the player's collection. "
                + "Replace cards the player doesn't own with the highest-level "
                + "alternatives that preserve synergy and combo integrity.";
    }
}
