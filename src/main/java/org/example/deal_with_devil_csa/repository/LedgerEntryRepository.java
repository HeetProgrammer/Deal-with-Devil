package org.example.deal_with_devil_csa.repository;

import java.util.List;

import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long>
{
    List<LedgerEntry> findByUserIdOrderByCreatedAtDesc(Long userId);
}
