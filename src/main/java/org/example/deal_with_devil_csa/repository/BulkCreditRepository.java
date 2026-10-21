package org.example.deal_with_devil_csa.repository;

import org.example.deal_with_devil_csa.domain.BulkCredit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkCreditRepository extends JpaRepository<BulkCredit, Long>
{
    /** True if a "credit everyone" press with this request id was already recorded. */
    boolean existsByRequestId(String requestId);
}
