package com.deftgray.clashproxy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BadgeDto {
    private String name;
    private Integer level;
    private Integer maxLevel;
    private Integer progress;
    private Integer target;
    private IconUrls iconUrls;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IconUrls {
        private String large;
    }
}
