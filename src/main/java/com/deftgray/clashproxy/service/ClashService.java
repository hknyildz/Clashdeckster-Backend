package com.deftgray.clashproxy.service;

import com.deftgray.clashproxy.dto.CardDto;
import com.deftgray.clashproxy.dto.CardListResponse;
import com.deftgray.clashproxy.dto.ClashApiResponse;
import com.deftgray.clashproxy.model.Card;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ClashService {

    private final RestTemplate restTemplate = new RestTemplate();

    @org.springframework.beans.factory.annotation.Value("${clash.api.token}")
    private String apiToken;

    private List<Card> cachedCards;

    public ClashApiResponse getPlayerCards(String playerTag) {
        String url = "https://api.clashroyale.com/v1/players/{tag}";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(this.apiToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<ClashApiResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity,
                    ClashApiResponse.class, playerTag);
            log.debug("Successfully fetched cards for player: {}", playerTag);
            return response.getBody();
        } catch (Exception e) {
            log.error("Error fetching player cards for tag: {}", playerTag, e);
            return null;
        }
    }

    public List<Card> getAllCards() {
        if (cachedCards != null && !cachedCards.isEmpty()) {
            return cachedCards;
        }

        String url = "https://api.clashroyale.com/v1/cards";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(this.apiToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<CardListResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity,
                    CardListResponse.class);

            CardListResponse body = response.getBody();
            log.info("Clash API /cards response: {}", body);

            if (body == null || body.getItems() == null) {
                log.warn("Clash API /cards returned null or empty list");
                return new ArrayList<>();
            }

            log.info("Fetched {} cards from Clash API", body.getItems().size());

            List<Card> cards = body.getItems().stream()
                    .map(this::mapToCard)
                    .toList();

            this.cachedCards = cards;
            return cards;
        } catch (Exception e) {
            log.error("Error fetching all cards from Clash API", e);
            return new ArrayList<>();
        }
    }

    public Card mapToCard(CardDto dto) {
        Card card = new Card();
        card.setName(dto.getName());

        // Normalize Level based on Rarity
        // API returns relative level (1-based index). We convert to Standard Level
        // (1-15+).
        Integer rawLevel = dto.getLevel();

        // Handle generic card data where level might be null
        if (rawLevel == null) {
            rawLevel = 1; // Default base level if not specified
        }

        String rarity = dto.getRarity();
        int normalizedLevel = rawLevel; // Default for Common

        if (rarity != null) {
            switch (rarity.toLowerCase()) {
                case "rare":
                    normalizedLevel = rawLevel + 2;
                    break;
                case "epic":
                    normalizedLevel = rawLevel + 5;
                    break;
                case "legendary":
                    normalizedLevel = rawLevel + 8;
                    break;
                case "champion":
                    normalizedLevel = rawLevel + 10;
                    break;
                default:
                    // Common or unknown, keep rawLevel
                    break;
            }
        }
        card.setLevel(normalizedLevel);

        card.setElixirCost(dto.getElixirCost());

        card.setRarity(dto.getRarity());
        card.setType(dto.getType());
        card.setId(dto.getId());

        // Map Icon URLs
        if (dto.getIconUrls() != null) {
            String mediumUrl = dto.getIconUrls().getMedium();
            card.setImageUri(mediumUrl);
            
            String evoUrl = dto.getIconUrls().getEvolutionMedium();
            String heroUrl = dto.getIconUrls().getHeroMedium();
            
            // Fallbacks: If Evolution is null, try Hero (some cards like Barbarian Barrel put evo url there).
            card.setImageUriEvolved(evoUrl != null ? evoUrl : (heroUrl != null ? heroUrl : mediumUrl));
            
            card.setImageUriHero(heroUrl != null ? heroUrl : (evoUrl != null ? evoUrl : mediumUrl));
        }

        boolean isHero = false;
        if (dto.getRarity() != null && dto.getRarity().equalsIgnoreCase("champion")) {
            isHero = true;
        } else if (dto.getIconUrls() != null && dto.getIconUrls().getHeroMedium() != null) {
            isHero = true;
        }
        
        boolean evolved = false;
        if (dto.getIconUrls() != null && dto.getIconUrls().getEvolutionMedium() != null) {
            evolved = true;
        } else if (dto.getMaxEvolutionLevel() != null && dto.getMaxEvolutionLevel() > 0) {
            evolved = true;
        } else if (dto.getEvolutionLevel() != null && dto.getEvolutionLevel() > 0) {
            evolved = true;
        }

        card.setIsHero(isHero);
        card.setEvolved(evolved);

        return card;
    }
}
