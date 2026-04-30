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
    public ClashApiResponse getPlayerStats(@PathVariable String tag) {
        log.info("=== Player Stats Request === tag: {}", tag);
        ClashApiResponse response = clashService.getPlayerCards(tag);
        if (response != null) {
            log.info("=== Player Stats Response === name: {}, trophies: {}, bestTrophies: {}, wins: {}, losses: {}, badges: {}",
                    response.getName(), response.getTrophies(), response.getBestTrophies(),
                    response.getWins(), response.getLosses(),
                    response.getBadges() != null ? response.getBadges().size() : 0);
        } else {
            log.warn("=== Player Stats Response === null for tag: {}", tag);
        }
        return response;
    }

    @GetMapping("/cards")
    public List<Card> getAllCards() {
        log.info("=== All Cards Request ===");
        List<Card> cards = clashService.getAllCards();
        log.info("=== All Cards Response === count: {}", cards.size());
        return cards;
    }

    @GetMapping("/freeDeck/{tag}")
    public DeckResponse getFreeDeck(@PathVariable String tag) {
        log.info("=== Free Deck Request === tag: {}", tag);
        DeckResponse response = deckService.generateFreeDeck(tag);
        log.info("=== Free Deck Response === valid: {}, strategy: {}, towerTroop: {}",
                response.isValid(), response.getStrategy(),
                response.getTowerTroopName() != null ? response.getTowerTroopName() : "none");
        return response;
    }

    @PostMapping("/decks/complete")
    public DeckResponse completeDeck(@RequestBody DeckCompletionRequest request) {
        log.info("=== Complete Deck Request === tag: {}, forced: {}, style: {}",
                request.getPlayerTag(),
                request.getPartialDeck() != null ? request.getPartialDeck().size() : 0,
                request.getPlayStyle());
        DeckResponse response = deckService.completeDeck(request);
        log.info("=== Complete Deck Response === valid: {}, strategy: {}",
                response.isValid(), response.getStrategy());
        return response;
    }
}
