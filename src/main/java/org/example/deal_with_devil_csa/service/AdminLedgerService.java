package org.example.deal_with_devil_csa.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.repository.LedgerEntryRepository;
import org.example.deal_with_devil_csa.web.dto.AdminLedgerEntryResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Read-only ledger viewer for the admin panel. */
@Service
@RequiredArgsConstructor
public class AdminLedgerService
{
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final LedgerEntryRepository ledgerEntryRepository;

    /** Newest entries first, for one participant or (userId null) for everyone. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminLedgerEntryResponse> recent(Long userId, Integer limit)
    {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        PageRequest page = PageRequest.of(0, size);
        List<LedgerEntry> entries = userId == null
                ? ledgerEntryRepository.findRecent(page)
                : ledgerEntryRepository.findRecentByUser(userId, page);

        Set<Long> undone = entries.isEmpty()
                ? Set.of()
                : new HashSet<>(ledgerEntryRepository.findReversedIds(entries.stream().map(LedgerEntry::getId).toList()));

        return entries.stream()
                .map(e -> AdminLedgerEntryResponse.from(e, undone.contains(e.getId())))
                .toList();
    }
}
