package com.deftgray.clashproxy.strategy;

import com.deftgray.clashproxy.dto.CardDto;
import com.deftgray.clashproxy.model.Card;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Carries all the information a DeckBuildStrategy needs to generate a deck.
 * Built once per request and passed through the strategy chain.
 */
@Data
@Builder
public class DeckBuildContext {

    /** Player's full card collection (enriched with type, elixir, images). */
    private List<Card> playerCards;

    /** Player's best trophies — used for evo/hero limit calculations. */
    private Integer bestTrophies;

    /** Player's support (tower troop) cards from Clash API. */
    private List<CardDto> supportCards;

    /** Player tag for logging / user persistence. */
    private String playerTag;

    // ─── Advanced Builder specific fields ───

    /** Card names the user has already locked in (Advanced Builder only). */
    private List<String> lockedCardNames;

    /** User's chosen play style label (Advanced Builder only). */
    private String playStyle;
}
