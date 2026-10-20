package org.example.deal_with_devil_csa.repository;

import java.util.List;

import org.example.deal_with_devil_csa.domain.AuditLogEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long>
{
    /** Newest entries first, with the actors loaded. */
    @Query("select a from AuditLogEntry a join fetch a.actor order by a.id desc")
    List<AuditLogEntry> findRecent(Pageable pageable);

    /** Newest entries of one actor first. */
    @Query("select a from AuditLogEntry a join fetch a.actor where a.actor.id = :actorId order by a.id desc")
    List<AuditLogEntry> findRecentByActor(@Param("actorId") Long actorId, Pageable pageable);
}
