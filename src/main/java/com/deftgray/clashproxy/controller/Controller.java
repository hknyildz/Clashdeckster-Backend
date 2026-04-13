package com.deftgray.clashproxy.controller;

import com.deftgray.clashproxy.dto.ClashApiResponse;
import com.deftgray.clashproxy.dto.DeckCompletionRequest;
import com.deftgray.clashproxy.dto.DeckResponse;
import com.deftgray.clashproxy.model.Card;
import com.deftgray.clashproxy.service.ClashService;
import com.deftgray.clashproxy.service.DeckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin
@RequiredArgsConstructor
@Slf4j
public class Controller {

    private final ClashService clashService;

    private final DeckService deckService;

    @GetMapping("/player/{tag}")
    public ClashApiResponse getPlayerCards(@PathVariable String tag) {
        log.info("Received request for player cards with tag: {}", tag);
        return clashService.getPlayerCards(tag);
    }

    @GetMapping("/cards")
    public List<Card> getAllCards() {
        log.info("Received request for all cards");
        return clashService.getAllCards();
    }

    @GetMapping("/freeDeck/{tag}")
    public DeckResponse getFreeDeck(@PathVariable String tag) {
        log.info("Received freeDeck request for tag: {}", tag);
        return deckService.generateFreeDeck(tag);
    }

    @PostMapping("/decks/complete")
    public DeckResponse completeDeck(@RequestBody DeckCompletionRequest request) {
        log.info("Received completeDeck request for tag: {}", request.getPlayerTag());
        return deckService.completeDeck(request);
    }
}
