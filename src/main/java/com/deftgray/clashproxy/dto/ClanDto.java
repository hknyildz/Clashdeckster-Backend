package com.deftgray.clashproxy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClanDto {
    private String tag;
    private String name;
    private Integer badgeId;
}
