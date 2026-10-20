package org.example.deal_with_devil_csa.web.dto;

import java.time.Instant;

import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.LedgerType;
import org.example.deal_with_devil_csa.domain.User;

/**
 * A ledger entry as the admin ledger viewer shows it: the normal fields plus who it belongs to, which admin did it
 * (null for system actions such as settlement) and whether it has already been undone (so the UI can hide "Undo").
 * Needs the entry's user and actor loaded.
 */
public record AdminLedgerEntryResponse(
        Long id,
        LedgerType type,
        long amount,
        long balanceAfter,
        String reason,
        Long reversalOf,
        Instant createdAt,
        Long userId,
        String userName,
        String actorName,
        boolean undone)
{
    public static AdminLedgerEntryResponse from(LedgerEntry entry, boolean undone)
    {
        User user = entry.getUser();
        User actor = entry.getActor();
        return new AdminLedgerEntryResponse(entry.getId(), entry.getType(), entry.getAmount(), entry.getBalanceAfter(),
                entry.getReason(), entry.getReversalOf(), entry.getCreatedAt(),
                user.getId(), user.getName(), actor == null ? null : displayName(actor), undone);
    }

    private static String displayName(User actor)
    {
        return actor.getName() != null && !actor.getName().isBlank() ? actor.getName() : actor.getEmail();
    }
}
