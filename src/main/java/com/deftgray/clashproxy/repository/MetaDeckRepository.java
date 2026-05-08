package com.deftgray.clashproxy.repository;

import com.deftgray.clashproxy.entity.MetaDeckEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetaDeckRepository extends JpaRepository<MetaDeckEntity, Long> {

    /**
     * Get the meta decks from the most recent job run, ordered by popularity.
     */
    @Query("SELECT m FROM MetaDeckEntity m WHERE m.snapshotDate = (SELECT MAX(m2.snapshotDate) FROM MetaDeckEntity m2) ORDER BY m.popularityRank ASC")
    List<MetaDeckEntity> findLatestMetaDecks();

    /**
     * Get top N meta decks by popularity.
     */
    List<MetaDeckEntity> findTopNByOrderByPopularityRankAsc(int n);

    // Optional: fetching meta of a specific date
    List<MetaDeckEntity> findAllBySnapshotDateOrderByPopularityRankAsc(java.time.LocalDate date);
    /**
     * Delete meta decks older than a specific date.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM MetaDeckEntity m WHERE m.lastUpdated < :date")
    void deleteByLastUpdatedBefore(LocalDateTime date);
}
