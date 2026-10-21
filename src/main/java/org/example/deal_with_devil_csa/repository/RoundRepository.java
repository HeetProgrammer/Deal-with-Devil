package org.example.deal_with_devil_csa.repository;

import java.util.Optional;

import org.example.deal_with_devil_csa.domain.Round;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RoundRepository extends JpaRepository<Round, Long>
{
    /** The current round is always the one with the highest number. */
    Optional<Round> findTopByOrderByNumberDesc();

    /**
     * Row-locked read of one round. The auto fill and manual assignment take it first, so two admins (or one admin
     * double-clicking) cannot top people up at the same moment: the second request waits, then sees the finished
     * result of the first one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Round r where r.id = :id")
    Optional<Round> findByIdForUpdate(@Param("id") Long id);
}
