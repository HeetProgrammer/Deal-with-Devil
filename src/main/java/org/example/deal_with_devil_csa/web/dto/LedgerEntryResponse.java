package org.example.deal_with_devil_csa.web.dto;

import java.time.Instant;

import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.LedgerType;

/**
 * One coin movement as the frontend sees it. Used for the participant's history, the pop-ups after an admin
 * action and (later) the admin ledger viewer. Only plain columns are read, so it is safe to build outside a transaction.
 */
public record LedgerEntryResponse(
        Long id,
        LedgerType type,
        long amount,
        long balanceAfter,
        String reason,
        Long reversalOf,
        Instant createdAt)
{
    public static LedgerEntryResponse from(LedgerEntry entry)
    {
        return new LedgerEntryResponse(entry.getId(), entry.getType(), entry.getAmount(), entry.getBalanceAfter(),
                entry.getReason(), entry.getReversalOf(), entry.getCreatedAt());
    }
}
