package org.example.deal_with_devil_csa.repository;

import java.util.List;
import java.util.Optional;

import org.example.deal_with_devil_csa.domain.ProblemHint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemHintRepository extends JpaRepository<ProblemHint, Long>
{
    List<ProblemHint> findByProblemIdOrderByCorruptionLevelAsc(Long problemId);

    Optional<ProblemHint> findByProblemIdAndCorruptionLevel(Long problemId, int corruptionLevel);

    /** Must run inside a transaction (the service that calls it is @Transactional). */
    void deleteByProblemId(Long problemId);
}
