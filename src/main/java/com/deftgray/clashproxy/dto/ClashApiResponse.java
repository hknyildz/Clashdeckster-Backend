package com.deftgray.clashproxy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClashApiResponse {

    private String tag;
    private String name;
    private String expLevel;
    private String trophies;
    private Integer bestTrophies;
    private List<CardDto> cards;
    private List<CardDto> supportCards;

    // Battle stats
    private Integer wins;
    private Integer losses;
    private Integer battleCount;
    private Integer threeCrownWins;
    private Integer currentWinLoseStreak;

    // Social
    private Integer donations;
    private Integer donationsReceived;
    private Integer totalDonations;
    private Integer warDayWins;
    private String role;

    // Challenge & Tournament
    private Integer challengeCardsWon;
    private Integer challengeMaxWins;
    private Integer tournamentCardsWon;
    private Integer tournamentBattleCount;

    // Experience
    private Integer totalExpPoints;

    // Favourite card
    private CardDto currentFavouriteCard;

    // Clan
    private ClanDto clan;

    // Badges (mastery, years played, etc.)
    private List<BadgeDto> badges;
}
