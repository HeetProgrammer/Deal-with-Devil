package org.example.deal_with_devil_csa.repository;

import java.util.List;

import org.example.deal_with_devil_csa.domain.Problem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemRepository extends JpaRepository<Problem, Long>
{
    List<Problem> findAllByOrderByIdAsc();

    List<Problem> findByRoundIdOrderByIdAsc(Long roundId);

    long countByRoundId(Long roundId);
}
