package com.deftgray.clashproxy.dto;

import com.deftgray.clashproxy.model.Card;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DeckResponse {
    private List<Card> deck;
    private boolean valid;
    private String strategy;
    private String tacticMessage;
    private Double averageElixir;
    private String deepLink;
    private Long towerTroopId;
    private String towerTroopName;
    private String towerTroopImageUrl;
}
