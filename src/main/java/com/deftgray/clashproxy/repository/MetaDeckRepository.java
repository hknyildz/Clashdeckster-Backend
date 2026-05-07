package com.deftgray.clashproxy.repository;

import com.deftgray.clashproxy.entity.MetaDeckEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetaDeckRepository extends JpaRepository<MetaDeckEntity, Long> {

    /**
     * Get the meta decks from the most recent job run, ordered by popularity.
     */
    @Query("SELECT m FROM MetaDeckEntity m WHERE m.lastUpdated = (SELECT MAX(m2.lastUpdated) FROM MetaDeckEntity m2) ORDER BY m.popularityRank ASC")
    List<MetaDeckEntity> findLatestMetaDecks();

    /**
     * Get top N meta decks by popularity.
     */
    List<MetaDeckEntity> findTopNByOrderByPopularityRankAsc(int n);

    /**
     * Delete meta decks older than a specific date.
     */
    @Modifying
    @Query("DELETE FROM MetaDeckEntity m WHERE m.lastUpdated < :date")
    void deleteByLastUpdatedBefore(LocalDateTime date);
}
