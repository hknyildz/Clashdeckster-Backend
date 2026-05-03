package com.deftgray.clashproxy.service;

import com.deftgray.clashproxy.dto.CardDto;
import com.deftgray.clashproxy.dto.CardListResponse;
import com.deftgray.clashproxy.dto.ClashApiResponse;
import com.deftgray.clashproxy.model.Card;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ClashService {

    private final RestTemplate restTemplate = new RestTemplate();

    @org.springframework.beans.factory.annotation.Value("${clash.api.token}")
    private String apiToken;

    private List<Card> cachedCards;

    // Caffeine cache: player tag -> ClashApiResponse, TTL 5 min, max 3000 entries
    private Cache<String, ClashApiResponse> playerCache;

    @PostConstruct
    public void initCache() {
        playerCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(3000)
                .recordStats()
                .build();
        log.info("Player cache initialized: TTL=5min, maxSize=3000");
    }

    public ClashApiResponse getPlayerCards(String playerTag) {
        // Check cache first
        ClashApiResponse cached = playerCache.getIfPresent(playerTag);
        if (cached != null) {
            log.info("Cache HIT for player: {} ({})", playerTag, cached.getName());
            return cached;
        }

        log.info("Cache MISS for player: {}, fetching from Clash API", playerTag);
        String url = "https://api.clashroyale.com/v1/players/{tag}";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(this.apiToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<ClashApiResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity,
                    ClashApiResponse.class, playerTag);
            ClashApiResponse body = response.getBody();
            if (body != null) {
                playerCache.put(playerTag, body);
                log.info("Cached player data for: {} ({})", playerTag, body.getName());
            }
            return body;
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

    /**
     * Fetch clan detail by clan tag (pass-through raw JSON).
     */
    public Object getClanInfo(String clanTag) {
        String url = "https://api.clashroyale.com/v1/clans/{clanTag}";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(this.apiToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            log.info("Fetching clan info from Clash API for tag: {}", clanTag);
            ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.GET, entity,
                    Object.class, clanTag);
            log.info("Successfully fetched clan info for tag: {}", clanTag);
            return response.getBody();
        } catch (Exception e) {
            log.error("Error fetching clan info for tag: {}", clanTag, e);
            return null;
        }
    }

    /**
     * Search clans by name, or fetch top clans by filters (pass-through raw JSON).
     */
    public Object searchClans(String name, Integer minMembers, Integer minScore, Integer limit) {
        StringBuilder urlBuilder = new StringBuilder("https://api.clashroyale.com/v1/clans?");
        boolean hasParam = false;

        if (name != null && !name.isBlank()) {
            urlBuilder.append("name=").append(java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8));
            hasParam = true;
        }
        if (minMembers != null) {
            if (hasParam) urlBuilder.append("&");
            urlBuilder.append("minMembers=").append(minMembers);
            hasParam = true;
        }
        if (minScore != null) {
            if (hasParam) urlBuilder.append("&");
            urlBuilder.append("minScore=").append(minScore);
            hasParam = true;
        }
        if (limit != null) {
            if (hasParam) urlBuilder.append("&");
            urlBuilder.append("limit=").append(limit);
        }

        String url = urlBuilder.toString();
        log.info("Searching clans with URL: {}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(this.apiToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            log.info("Executing clan search request to Clash API...");
            ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.GET, entity, Object.class);
            log.info("Successfully received clan search results");
            return response.getBody();
        } catch (Exception e) {
            log.error("Error searching clans", e);
            return null;
        }
    }

    /**
     * Fetch player battle log (pass-through raw JSON array).
     */
    public Object getPlayerBattleLog(String playerTag) {
        String url = "https://api.clashroyale.com/v1/players/{tag}/battlelog";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(this.apiToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            log.info("Fetching battle log from Clash API for player: {}", playerTag);
            ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.GET, entity,
                    Object.class, playerTag);
            log.info("Successfully fetched battle log for player: {}", playerTag);
            return response.getBody();
        } catch (Exception e) {
            log.error("Error fetching battle log for tag: {}", playerTag, e);
            return null;
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
