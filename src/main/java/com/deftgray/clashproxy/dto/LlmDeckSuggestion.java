package com.deftgray.clashproxy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmDeckSuggestion {
    @JsonProperty("selected_evolutions")
    private List<String> selectedEvolutions;
    
    @JsonProperty("selected_heroes")
    private List<String> selectedHeroes;

    private List<LlmCardSuggestion> cards;
    private String strategy;
    private String tactic;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmCardSuggestion {
        private String name;

        @JsonProperty("isEvolved")
        private boolean isEvolved;

        @JsonProperty("isHero")
        private boolean isHero;

        private Integer level;
    }
}
