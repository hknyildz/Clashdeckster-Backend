package com.deftgray.clashproxy.service;


import com.deftgray.clashproxy.dto.CardDto;
import com.deftgray.clashproxy.dto.ClashApiResponse;
import com.deftgray.clashproxy.entity.UserEntity;
import com.deftgray.clashproxy.meta.DeckSignatureUtil;
import com.deftgray.clashproxy.model.Card;
import com.deftgray.clashproxy.dto.DeckResponse;
import com.deftgray.clashproxy.dto.DeckCompletionRequest;
import com.deftgray.clashproxy.repository.UserRepository;
import com.deftgray.clashproxy.strategy.DeckBuildContext;
import com.deftgray.clashproxy.strategy.DeckBuildResult;
import com.deftgray.clashproxy.strategy.DeckBuildStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrator for deck generation.
 * Delegates deck building to a chain of strategies (MetaBased → LlmFallback)
 * and handles player data fetching, user persistence, and response formatting.
 */
@Service
@Slf4j
public class DeckService {

    private final ClashService clashService;
    private final UserRepository userRepository;
    private final List<DeckBuildStrategy> strategies;

    /**
     * Spring injects all DeckBuildStrategy beans.
     * We order them: MetaLlmStrategy first, LlmFallbackStrategy last.
     */
    public DeckService(ClashService clashService,
                       UserRepository userRepository,
                       List<DeckBuildStrategy> strategies) {
        this.clashService = clashService;
        this.userRepository = userRepository;
        // Sort: MetaLlmStrategy first (@Order(1)), LlmFallbackStrategy last (@Order(2))
        // If we need explicit ordering later, use @Order annotation
        this.strategies = strategies;
        log.info("DeckService initialized with {} strategies: {}",
                strategies.size(),
                strategies.stream().map(DeckBuildStrategy::name)
                        .collect(Collectors.joining(" → ")));
    }

    // ══════════════════════════════════════════════
    //  Quick Generate
    // ══════════════════════════════════════════════

    public DeckResponse generateFreeDeck(String playerTag) {
        log.info("Generating free deck for player: {}", playerTag);

        // 1. Fetch & enrich player cards
        ClashApiResponse playerResponse = clashService.getPlayerCards(playerTag);
        if (playerResponse == null || playerResponse.getCards() == null
                || playerResponse.getCards().isEmpty()) {
            log.warn("No cards found for player: {}", playerTag);
            return DeckResponse.builder().valid(false).strategy("N/A")
                    .tacticMessage("Player not found or no cards available.").build();
        }

        List<Card> playerCards = enrichPlayerCards(playerResponse);
        List<CardDto> supportCards = playerResponse.getSupportCards();

        // 2. Build context
        DeckBuildContext context = DeckBuildContext.builder()
                .playerCards(playerCards)
                .bestTrophies(playerResponse.getBestTrophies())
                .supportCards(supportCards)
                .playerTag(playerTag)
                .build();

        // 3. Run strategy chain
        DeckBuildResult result = runStrategyChain(context);

        if (result == null) {
            log.error("All strategies failed for player: {}", playerTag);
            return DeckResponse.builder()
                    .valid(false).strategy("N/A")
                    .tacticMessage("Failed to generate a valid deck, please try again.")
                    .build();
        }

        // 4. Save user info
        saveUserInfo(playerTag, playerResponse);

        // 5. Build response
        return buildResponse(result, supportCards);
    }

    // ══════════════════════════════════════════════
    //  Advanced Builder (Complete Deck)
    // ══════════════════════════════════════════════

    public DeckResponse completeDeck(DeckCompletionRequest request) {
        log.info("Completing deck for player: {}", request.getPlayerTag());

        // 1. Fetch & enrich
        ClashApiResponse playerResponse = clashService.getPlayerCards(request.getPlayerTag());
        if (playerResponse == null || playerResponse.getCards() == null
                || playerResponse.getCards().isEmpty()) {
            return DeckResponse.builder().valid(false)
                    .tacticMessage("Player cards not found.").build();
        }

        List<Card> playerCards = enrichPlayerCards(playerResponse);
        List<CardDto> supportCards = playerResponse.getSupportCards();

        // 2. Resolve locked card names from IDs
        List<String> lockedNames = resolveLockedCards(request.getPartialDeck(),
                playerCards, clashService.getAllCards());

        // 3. Build context
        DeckBuildContext context = DeckBuildContext.builder()
                .playerCards(playerCards)
                .bestTrophies(playerResponse.getBestTrophies())
                .supportCards(supportCards)
                .playerTag(request.getPlayerTag())
                .lockedCardNames(lockedNames)
                .playStyle(request.getPlayStyle())
                .build();

        // 4. Run strategy chain
        DeckBuildResult result = runStrategyChain(context);

        if (result == null) {
            return DeckResponse.builder().valid(false)
                    .tacticMessage("Failed to complete deck.").build();
        }

        // 5. Build response
        return buildResponse(result, supportCards);
    }

    // ══════════════════════════════════════════════
    //  Strategy Chain
    // ══════════════════════════════════════════════

    /**
     * Tries each strategy in order. Returns the first successful result,
     * or null if all strategies fail.
     */
    private DeckBuildResult runStrategyChain(DeckBuildContext context) {
        for (DeckBuildStrategy strategy : strategies) {
            if (!strategy.canHandle(context)) {
                log.info("[StrategyChain] {} cannot handle this context, skipping",
                        strategy.name());
                continue;
            }

            log.info("[StrategyChain] Trying {}...", strategy.name());
            DeckBuildResult result = strategy.build(context);

            if (result != null && result.getDeck() != null && result.getDeck().size() == 8) {
                log.info("[StrategyChain] ✓ {} produced a valid deck", strategy.name());
                return result;
            }

            log.info("[StrategyChain] {} did not produce a valid deck, trying next",
                    strategy.name());
        }
        return null;
    }

    // ══════════════════════════════════════════════
    //  Response builder
    // ══════════════════════════════════════════════

    private DeckResponse buildResponse(DeckBuildResult result, List<CardDto> supportCards) {
        List<Card> deck = result.getDeck();

        double avgElixir = deck.stream()
                .mapToInt(c -> c.getElixirCost() != null ? c.getElixirCost() : 0)
                .average().orElse(0.0);

        // Sort for deep link: Evolved first, then Hero, then by elixir cost
        List<Card> sortedDeck = deck.stream()
                .sorted((a, b) -> {
                    int aEvo = Boolean.TRUE.equals(a.getEvolved()) ? 1 : 0;
                    int bEvo = Boolean.TRUE.equals(b.getEvolved()) ? 1 : 0;
                    if (aEvo != bEvo) return bEvo - aEvo;
                    int aHero = Boolean.TRUE.equals(a.getIsHero()) ? 1 : 0;
                    int bHero = Boolean.TRUE.equals(b.getIsHero()) ? 1 : 0;
                    if (aHero != bHero) return bHero - aHero;
                    return Integer.compare(
                            a.getElixirCost() != null ? a.getElixirCost() : 0,
                            b.getElixirCost() != null ? b.getElixirCost() : 0);
                })
                .toList();

        String cardIds = sortedDeck.stream()
                .map(c -> String.valueOf(c.getId()))
                .collect(Collectors.joining(";"));

        // Resolve tower troop
        Long towerTroopId = null;
        String towerTroopName = null;
        String towerTroopImageUrl = null;

        if (result.getSelectedTowerTroop() != null && supportCards != null) {
            String selectedName = result.getSelectedTowerTroop();
            CardDto matched = supportCards.stream()
                    .filter(sc -> sc.getName() != null
                            && sc.getName().equalsIgnoreCase(selectedName))
                    .findFirst().orElse(null);
            if (matched != null) {
                towerTroopId = matched.getId();
                towerTroopName = matched.getName();
                if (matched.getIconUrls() != null) {
                    towerTroopImageUrl = matched.getIconUrls().getMedium();
                }
            }
        }

        // Fallback: pick highest-level tower troop
        if (towerTroopId == null && supportCards != null && !supportCards.isEmpty()) {
            CardDto fallback = supportCards.stream()
                    .max((a, b) -> Integer.compare(
                            a.getLevel() != null ? a.getLevel() : 0,
                            b.getLevel() != null ? b.getLevel() : 0))
                    .orElse(supportCards.get(0));
            towerTroopId = fallback.getId();
            towerTroopName = fallback.getName();
            if (fallback.getIconUrls() != null) {
                towerTroopImageUrl = fallback.getIconUrls().getMedium();
            }
        }

        // Build deep link
        String deepLink = "https://link.clashroyale.com/en/?clashroyale://copyDeck?deck="
                + cardIds + "&l=Royals";
        if (towerTroopId != null) {
            deepLink += "&tt=" + towerTroopId;
        }

        return DeckResponse.builder()
                .deck(deck)
                .valid(true)
                .strategy(result.getStrategy())
                .tacticMessage(result.getTacticMessage())
                .averageElixir(Math.round(avgElixir * 10.0) / 10.0)
                .deepLink(deepLink)
                .towerTroopId(towerTroopId)
                .towerTroopName(towerTroopName)
                .towerTroopImageUrl(towerTroopImageUrl)
                .build();
    }

    // ══════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════

    /**
     * Enriches player cards from the API response with full card data
     * (elixir cost, images, type, rarity) from the cached all-cards list.
     */
    private List<Card> enrichPlayerCards(ClashApiResponse playerResponse) {
        List<Card> cachedAllCards = clashService.getAllCards();
        return playerResponse.getCards().stream()
                .map(dto -> {
                    Card playerCard = clashService.mapToCard(dto);
                    Card fullCard = cachedAllCards.stream()
                            .filter(c -> c.getId() != null && c.getId().equals(playerCard.getId()))
                            .findFirst().orElse(null);
                    if (fullCard != null) {
                        playerCard.setElixirCost(fullCard.getElixirCost());
                        playerCard.setImageUri(fullCard.getImageUri());
                        playerCard.setImageUriEvolved(fullCard.getImageUriEvolved());
                        playerCard.setImageUriHero(fullCard.getImageUriHero());
                        playerCard.setType(fullCard.getType());
                        playerCard.setRarity(fullCard.getRarity());
                    }
                    return playerCard;
                })
                .toList();
    }

    /**
     * Resolves partial deck card IDs to card names.
     * If a card ID is not in the user's collection, finds a substitute.
     */
    private List<String> resolveLockedCards(List<Long> partialDeckIds,
                                             List<Card> playerCards,
                                             List<Card> allCards) {
        List<String> names = new ArrayList<>();
        if (partialDeckIds == null) return names;

        for (Long id : partialDeckIds) {
            Card owned = playerCards.stream()
                    .filter(c -> c.getId().equals(id)).findFirst().orElse(null);
            if (owned != null) {
                names.add(owned.getName());
            } else {
                Card substitute = findSubstitute(id, playerCards, allCards);
                if (substitute != null) {
                    log.info("Substituting missing card ID {} with {}", id, substitute.getName());
                    names.add(substitute.getName());
                } else {
                    log.warn("Could not find substitute for ID {}", id);
                }
            }
        }
        return names;
    }

    private Card findSubstitute(Long targetId, List<Card> playerCards, List<Card> allCards) {
        Card target = allCards.stream()
                .filter(c -> c.getId().equals(targetId)).findFirst().orElse(null);
        if (target == null) return null;

        List<Card> candidates = playerCards.stream()
                .filter(c -> c.getType() != null && c.getType().equals(target.getType()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            candidates = new ArrayList<>(playerCards);
        }

        final int targetCost = target.getElixirCost() != null ? target.getElixirCost() : 3;

        return candidates.stream()
                .min((c1, c2) -> {
                    int dist1 = Math.abs((c1.getElixirCost() != null ? c1.getElixirCost() : 0) - targetCost);
                    int dist2 = Math.abs((c2.getElixirCost() != null ? c2.getElixirCost() : 0) - targetCost);
                    if (dist1 != dist2) return dist1 - dist2;
                    return (c2.getLevel() != null ? c2.getLevel() : 0)
                            - (c1.getLevel() != null ? c1.getLevel() : 0);
                })
                .orElse(null);
    }

    private void saveUserInfo(String playerTag, ClashApiResponse playerResponse) {
        try {
            UserEntity userEntity = userRepository.findById(playerTag)
                    .orElse(new UserEntity());

            Long towerTroopId = null;
            if (playerResponse.getCurrentDeckSupportCards() != null
                    && !playerResponse.getCurrentDeckSupportCards().isEmpty()) {
                towerTroopId = playerResponse.getCurrentDeckSupportCards().get(0).getId();
            }

            mapToUserEntity(userEntity,
                    playerResponse.getTag(),
                    playerResponse.getName(),
                    Integer.valueOf(playerResponse.getTrophies()),
                    playerResponse.getBestTrophies(),
                    playerResponse.getCurrentDeck(),
                    towerTroopId);
            userRepository.save(userEntity);
            log.info("User info saved: {}", userEntity);
        } catch (Exception e) {
            log.error("Failed to save user info for {}", playerTag, e);
        }
    }

    private UserEntity mapToUserEntity(UserEntity userEntity, String tag, String name,
                                        Integer trophies, Integer bestTrophies,
                                        List<CardDto> currentDeck, Long towerTroopId) {
        if (userEntity.getPlayerTag() == null) {
            userEntity.setPlayerTag(tag);
            userEntity.setDeckGenerationCount(1);
        } else {
            userEntity.setDeckGenerationCount(userEntity.getDeckGenerationCount() + 1);
        }

        userEntity.setPlayerName(name);
        userEntity.setCurrentTrophies(trophies);
        userEntity.setBestTrophies(bestTrophies);
        userEntity.setLastCurrentDeck(DeckSignatureUtil.cardsToJson(currentDeck));
        userEntity.setDeckKey(DeckSignatureUtil.generateSignature(currentDeck, towerTroopId));
        userEntity.setLastOperationDate(LocalDateTime.now());
        return userEntity;
    }
}
