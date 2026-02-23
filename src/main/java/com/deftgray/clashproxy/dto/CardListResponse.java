package com.deftgray.clashproxy.dto;

import lombok.Data;
import java.util.List;

@Data
public class CardListResponse {
    private List<CardDto> items;
}
