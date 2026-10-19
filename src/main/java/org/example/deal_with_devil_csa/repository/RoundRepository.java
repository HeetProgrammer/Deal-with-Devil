package org.example.deal_with_devil_csa.repository;

import java.util.Optional;

import org.example.deal_with_devil_csa.domain.Round;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoundRepository extends JpaRepository<Round, Long>
{
    /** The current round is always the one with the highest number. */
    Optional<Round> findTopByOrderByNumberDesc();
}
