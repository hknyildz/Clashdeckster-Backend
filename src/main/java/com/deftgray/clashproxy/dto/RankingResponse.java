package com.deftgray.clashproxy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * DTO for Path of Legend ranking API response.
 * Endpoint: /v1/locations/global/pathoflegend/{season}/rankings/players
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RankingResponse {

    private List<RankedPlayer> items;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RankedPlayer {
        private String tag;
        private String name;
        private Integer expLevel;
        private Integer eloRating;
        private Integer rank;
    }
}
