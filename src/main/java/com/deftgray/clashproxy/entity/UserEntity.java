package com.deftgray.clashproxy.entity;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {
    @Id
    private String playerTag;

    private String playerName;

    @Column(nullable = false)
    private Integer deckGenerationCount;

    private Integer currentTrophies;

    private Integer bestTrophies;

    @Column(columnDefinition = "TEXT")
    private String lastCurrentDeck;

    @Column(name = "deck_key", length = 512)
    private String deckKey;

    private LocalDateTime lastOperationDate;
}