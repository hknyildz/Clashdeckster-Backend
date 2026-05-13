package com.deftgray.clashproxy.strategy;

import com.deftgray.clashproxy.model.Card;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result returned by a DeckBuildStrategy after successful deck generation.
 * Contains the final 8-card deck plus metadata for the response.
 */
@Data
@Builder
public class DeckBuildResult {

    /** The final 8-card deck. */
    private List<Card> deck;

    /** Strategy archetype label (e.g. "Beatdown", "Cycle/Control"). */
    private String strategy;

    /** Human-readable tactic / explanation. */
    private String tacticMessage;

    /** Name of the strategy that produced this result (for logging). */
    private String producedBy;

    /** Selected tower troop name (may be null). */
    private String selectedTowerTroop;
}
