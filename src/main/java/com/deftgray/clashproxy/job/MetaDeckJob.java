package com.deftgray.clashproxy.job;

import com.deftgray.clashproxy.dto.CardDto;
import com.deftgray.clashproxy.dto.ClashApiResponse;
import com.deftgray.clashproxy.entity.MetaDeckEntity;
import com.deftgray.clashproxy.meta.DeckSignatureUtil;
import com.deftgray.clashproxy.meta.WinConditionRegistry;
import com.deftgray.clashproxy.repository.MetaDeckRepository;
import com.deftgray.clashproxy.service.ClashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Daily scheduled job that scrapes top Path of Legend players' decks,
 * aggregates them by unique signature, and persists the top N to the database.
 *
 * Schedule: Every day at 03:00 UTC (06:00 Turkey time)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MetaDeckJob {

    private final ClashService clashService;
    private final MetaDeckRepository metaDeckRepository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Value("${meta.job.player-limit:1000}")
    private int playerLimit;

    @Value("${meta.job.request-delay-ms:100}")
    private int requestDelayMs;

    @Value("${meta.job.top-deck-limit:100}")
    private int topDeckLimit;

    /**
     * Runs daily at 03:00 UTC = 06:00 Turkey time.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void runScheduled() {
        log.info("===== META DECK JOB TRIGGERED BY SCHEDULE =====");
        run();
    }

    /**
     * Main job logic — can also be triggered manually via /meta/run endpoint.
     */
    public void run() {
        Instant start = Instant.now();
        log.info("===== META DECK JOB STARTED =====");

        // ─── Step 1: Fetch top player tags ───
        List<String> playerTags = clashService.fetchTopPlayerTags(playerLimit);
        if (playerTags.isEmpty()) {
            log.error("[MetaDeckJob] No player tags fetched. Aborting job.");
            return;
        }
        log.info("[MetaDeckJob] Fetched {} player tags", playerTags.size());

        // ─── Step 2: Scrape each player's currentDeck ───
        Map<String, AggregatedDeck> deckMap = new LinkedHashMap<>();
        int successCount = 0;
        int skipCount = 0;

        for (int i = 0; i < playerTags.size(); i++) {
            String tag = playerTags.get(i);

            try {
                ClashApiResponse playerData = clashService.fetchPlayerRaw(tag);

                if (playerData == null || playerData.getCurrentDeck() == null
                        || playerData.getCurrentDeck().size() < 8) {
                    log.warn("[MetaDeckJob] Player {}/{} SKIPPED (no valid deck): {}",
                            i + 1, playerTags.size(), tag);
                    skipCount++;
                    continue;
                }
                log.info("[MetaDeckJob] Player {}/{} FETCHED: {}",i+1,playerTags.size(),tag);

                List<CardDto> currentDeck = playerData.getCurrentDeck();

                // Extract tower troop info
                Long towerTroopId = null;
                String towerTroopName = null;
                if (playerData.getCurrentDeckSupportCards() != null
                        && !playerData.getCurrentDeckSupportCards().isEmpty()) {
                    CardDto towerTroop = playerData.getCurrentDeckSupportCards().get(0);
                    towerTroopId = towerTroop.getId();
                    towerTroopName = towerTroop.getName();
                }

                // ─── Step 3: Generate signature & detect win conditions ───
                String signature = DeckSignatureUtil.generateSignature(currentDeck, towerTroopId);

                if (deckMap.containsKey(signature)) {
                    // Aggregate: increment count
                    deckMap.get(signature).usageCount++;
                } else {
                    // New deck
                    WinConditionRegistry.WinConditionResult wcResult =
                            WinConditionRegistry.analyze(currentDeck);

                    AggregatedDeck agg = new AggregatedDeck();
                    agg.deckKey = signature;
                    agg.cards = currentDeck;
                    agg.towerTroopId = towerTroopId;
                    agg.towerTroopName = towerTroopName;
                    agg.winConditions = wcResult.getWinConditionsString();
                    agg.gameType = wcResult.getGameType();
                    agg.averageElixir = DeckSignatureUtil.calculateAverageElixir(currentDeck);
                    agg.usageCount = 1;

                    deckMap.put(signature, agg);
                }

                successCount++;

                // Progress log every 100 players
                if ((i + 1) % 100 == 0) {
                    log.info("[MetaDeckJob] Progress: {}/{} players processed ({} successful, {} skipped)",
                            i + 1, playerTags.size(), successCount, skipCount);
                }

            } catch (Exception e) {
                log.warn("[MetaDeckJob] Player {}/{} ERROR: {} — {}",
                        i + 1, playerTags.size(), tag, e.getMessage());
                skipCount++;
            }

            // Delay between requests to avoid rate limiting
            if (i < playerTags.size() - 1) {
                try {
                    Thread.sleep(requestDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[MetaDeckJob] Interrupted during delay, stopping job");
                    return;
                }
            }
        }

        log.info("[MetaDeckJob] Processing complete: {}/{} successful, {} skipped",
                successCount, playerTags.size(), skipCount);
        log.info("[MetaDeckJob] Unique decks found: {}", deckMap.size());

        // ─── Step 4: Sort by usage count & ensure WC coverage ───
        List<AggregatedDeck> allSorted = deckMap.values().stream()
                .sorted(Comparator.comparingInt((AggregatedDeck d) -> d.usageCount).reversed())
                .collect(Collectors.toList());

        // Take top N by popularity first
        List<AggregatedDeck> selectedDecks = new ArrayList<>(
                allSorted.stream().limit(topDeckLimit).toList());
        Set<String> selectedKeys = selectedDecks.stream()
                .map(d -> d.deckKey).collect(Collectors.toSet());

        // WC Coverage Guarantee: ensure at least 3 decks per WC in WinConditionRegistry
        int minDecksPerWC = 3;
        Map<String, Long> wcCoverage = new HashMap<>();
        for (String wc : WinConditionRegistry.WIN_CONDITIONS.keySet()) {
            long count = selectedDecks.stream()
                    .filter(d -> d.winConditions != null && d.winConditions.contains(wc))
                    .count();
            wcCoverage.put(wc, count);
        }

        List<String> underRepresented = wcCoverage.entrySet().stream()
                .filter(e -> e.getValue() < minDecksPerWC)
                .map(Map.Entry::getKey)
                .toList();

        if (!underRepresented.isEmpty()) {
            log.info("[MetaDeckJob] Under-represented WCs (< {} decks): {}", minDecksPerWC, underRepresented);

            for (String wc : underRepresented) {
                long currentCount = wcCoverage.get(wc);
                long needed = minDecksPerWC - currentCount;

                // Find decks containing this WC from the remaining pool
                List<AggregatedDeck> candidates = allSorted.stream()
                        .filter(d -> !selectedKeys.contains(d.deckKey))
                        .filter(d -> d.winConditions != null && d.winConditions.contains(wc))
                        .limit(needed)
                        .toList();

                for (AggregatedDeck candidate : candidates) {
                    selectedDecks.add(candidate);
                    selectedKeys.add(candidate.deckKey);
                    log.info("[MetaDeckJob]   + Added deck for WC '{}': [{}] ({} users)",
                            wc, candidate.cards.stream().map(CardDto::getName)
                                    .collect(Collectors.joining(", ")), candidate.usageCount);
                }
            }
        }

        log.info("[MetaDeckJob] Final deck count: {} (top {} + {} WC coverage additions)",
                selectedDecks.size(), topDeckLimit,
                selectedDecks.size() - Math.min(topDeckLimit, allSorted.size()));

        // Log top 5
        for (int i = 0; i < Math.min(5, selectedDecks.size()); i++) {
            AggregatedDeck d = selectedDecks.get(i);
            String cardNames = d.cards.stream()
                    .map(CardDto::getName)
                    .collect(Collectors.joining(", "));
            log.info("[MetaDeckJob]   #{} ({} users): {} [{}] Tower: {}",
                    i + 1, d.usageCount, cardNames, d.gameType,
                    d.towerTroopName != null ? d.towerTroopName : "N/A");
        }

        // ─── Step 5: Save new data and clean up old data (Keep last 90 days) ───
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime ninetyDaysAgo = now.minusDays(90);

        log.info("[MetaDeckJob] Cleaning up records older than 90 days ({})", ninetyDaysAgo);
        metaDeckRepository.deleteByLastUpdatedBefore(ninetyDaysAgo);

        List<MetaDeckEntity> entities = new ArrayList<>();
        for (int i = 0; i < selectedDecks.size(); i++) {
            AggregatedDeck d = selectedDecks.get(i);
            entities.add(MetaDeckEntity.builder()
                    .deckKey(d.deckKey)
                            .snapshotDate(LocalDate.now())
                    .winConditions(d.winConditions)
                    .gameType(d.gameType)
                    .cardsJson(DeckSignatureUtil.cardsToJson(d.cards))
                    .towerTroopId(d.towerTroopId)
                    .towerTroopName(d.towerTroopName)
                    .usageCount(d.usageCount)
                    .popularityRank(i + 1)
                    .averageElixir(d.averageElixir)
                    .lastUpdated(now)
                    .build());
        }

        metaDeckRepository.saveAll(entities);

        // ─── Step 6: Save historical log to a local JSON file ───
        saveHistoricalLog(now, entities);

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("[MetaDeckJob] Saved {} decks to DB", entities.size());
        log.info("===== META DECK JOB COMPLETED in {}m {}s =====",
                elapsed.toMinutes(), elapsed.toSecondsPart());
    }

    /**
     * Saves the list of meta decks to a local JSON file for historical tracking.
     */
    private void saveHistoricalLog(LocalDateTime now, List<MetaDeckEntity> entities) {
        try {
            File dir = new File("meta-logs");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            File logFile = new File(dir, "meta_decks_" + dateStr + ".json");
            
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(logFile, entities);
            log.info("[MetaDeckJob] Historical log saved to: {}", logFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("[MetaDeckJob] Failed to save historical log file", e);
        }
    }

    /**
     * Internal helper class for aggregation before DB persistence.
     */
    private static class AggregatedDeck {
        String deckKey;
        List<CardDto> cards;
        Long towerTroopId;
        String towerTroopName;
        String winConditions;
        String gameType;
        double averageElixir;
        int usageCount;
    }
}
