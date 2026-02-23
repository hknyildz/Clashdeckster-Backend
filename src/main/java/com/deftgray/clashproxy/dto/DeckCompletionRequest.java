package com.deftgray.clashproxy.dto;

import lombok.Data;
import java.util.List;

@Data
public class DeckCompletionRequest {
    private String playerTag;
    private List<Long> partialDeck;
    private String playStyle;
}
