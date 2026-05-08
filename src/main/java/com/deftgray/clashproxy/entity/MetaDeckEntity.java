package com.deftgray.clashproxy.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JPA Entity representing an aggregated meta deck from top player analysis.
 * Table is truncated and repopulated on each daily job run.
 */
@Entity
@Table(name = "meta_decks", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"deck_key", "snapshot_date"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaDeckEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * deck signature in format: "cardId:evoLevel_cardId:evoLevel_..._T:towerTroopId"
     * Cards are sorted by cardId ascending for normalization.
     */
    @Column(name = "deck_key", nullable = false,  length = 512)
    private String deckKey;

    @Column(name = "snapshot_date", nullable = false)
    private java.time.LocalDate snapshotDate;

    /**
     * Comma-separated list of win conditions found in the deck.
     * e.g. "Hog Rider,Balloon" or "Golem"
     */
    @Column(name = "win_conditions", length = 512)
    private String winConditions;

    /**
     * Game type derived from win conditions.
     * One of: Siege, Beatdown, Cycle/Control, Bait/Special, Mixed, Unknown
     */
    @Column(name = "game_type", length = 64)
    private String gameType;

    /**
     * JSON array of 8 cards with id, name, and evolutionLevel.
     * e.g. [{"id":26000000,"name":"Knight","evolutionLevel":0}, ...]
     */
    @Column(name = "cards_json", columnDefinition = "TEXT", nullable = false)
    private String cardsJson;

    /**
     * Tower Troop (Support Card) used with this deck.
     */
    @Column(name = "tower_troop_id")
    private Long towerTroopId;

    @Column(name = "tower_troop_name", length = 128)
    private String towerTroopName;

    /**
     * Number of top players using this exact deck configuration.
     */
    @Column(name = "usage_count", nullable = false)
    private Integer usageCount;

    /**
     * Rank by popularity (1 = most used).
     */
    @Column(name = "popularity_rank", nullable = false)
    private Integer popularityRank;

    /**
     * Average elixir cost of the deck.
     */
    @Column(name = "average_elixir")
    private Double averageElixir;

    /**
     * Timestamp of last job run that produced this record.
     */
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;
}
