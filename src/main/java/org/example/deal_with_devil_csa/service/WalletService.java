package org.example.deal_with_devil_csa.service;

import java.util.List;

import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.LedgerType;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.LedgerEntryRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The only place coins move. Every change goes through applyTo(), which locks the wallet row, checks the
 * balance, updates it and appends a LedgerEntry in the same transaction, so the wallet and the ledger can
 * never disagree. Credit, deduct and undo are thin wrappers around it.
 *
 * Callers that run inside a bigger transaction (for example settlement) join it, so a failure anywhere
 * rolls back every coin movement in that operation.
 */
@Service
@RequiredArgsConstructor
public class WalletService
{
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;

    /** Adds coins. amount must be positive. actorId is the admin doing it, or null for system actions. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public LedgerEntry credit(Long userId, long amount, LedgerType type, String reason, Long actorId)
    {
        requirePositive(amount);
        requireNotReversal(type);
        return applyTo(lockWallet(userId), type, amount, reason, actorId, null);
    }

    /** Removes coins. amount must be positive (it is stored negative). Fails if the balance would go below zero. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public LedgerEntry deduct(Long userId, long amount, LedgerType type, String reason, Long actorId)
    {
        requirePositive(amount);
        requireNotReversal(type);
        return applyTo(lockWallet(userId), type, -amount, reason, actorId, null);
    }

    /**
     * Undo: appends a REVERSAL entry with the opposite amount. The original is never touched.
     * An entry can be reversed once, and a reversal itself cannot be reversed.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public LedgerEntry reverse(Long ledgerEntryId, String reason, Long actorId)
    {
        LedgerEntry original = ledgerEntryRepository.findById(ledgerEntryId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Ledger entry not found."));

        if (original.getType() == LedgerType.REVERSAL)
        {
            throw new ApiException(ErrorCode.CONFLICT, "A reversal cannot be reversed.");
        }

        // Lock BEFORE the "already reversed" check, so two simultaneous undo clicks cannot both pass it.
        Wallet wallet = lockWallet(original.getUser().getId());

        if (ledgerEntryRepository.existsByReversalOf(original.getId()))
        {
            throw new ApiException(ErrorCode.CONFLICT, "That entry has already been reversed.");
        }

        return applyTo(wallet, LedgerType.REVERSAL, -original.getAmount(), reason, actorId, original.getId());
    }

    /** A participant's own ledger, newest first. */
    @Transactional(readOnly = true)
    @PreAuthorize("@guard.isSelf(#userId)")
    public List<LedgerEntry> history(Long userId)
    {
        return ledgerEntryRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    // ---- Internals ----

    private Wallet lockWallet(Long userId)
    {
        return walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    /** The single chokepoint: check, update the balance, write the ledger entry. */
    private LedgerEntry applyTo(Wallet wallet, LedgerType type, long signedAmount, String reason,
                                Long actorId, Long reversalOf)
    {
        if (signedAmount == 0)
        {
            throw new ApiException(ErrorCode.INVALID_AMOUNT);
        }
        if (reason == null || reason.isBlank())
        {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A reason is required for every coin change.");
        }

        long newBalance;
        try
        {
            newBalance = Math.addExact(wallet.getBalance(), signedAmount);
        }
        catch (ArithmeticException e)
        {
            throw new ApiException(ErrorCode.INVALID_AMOUNT);
        }
        if (newBalance < 0)
        {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        wallet.setBalance(newBalance);

        LedgerEntry entry = new LedgerEntry();
        entry.setUser(wallet.getUser());
        entry.setType(type);
        entry.setAmount(signedAmount);
        entry.setBalanceAfter(newBalance);
        entry.setReason(reason.trim());
        entry.setActor(actorId == null ? null : userRepository.getReferenceById(actorId));
        entry.setReversalOf(reversalOf);
        return ledgerEntryRepository.save(entry);
    }

    private void requirePositive(long amount)
    {
        if (amount <= 0)
        {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "The amount must be greater than zero.");
        }
    }

    /** REVERSAL entries are only created by reverse(), so they always point at the entry they undo. */
    private void requireNotReversal(LedgerType type)
    {
        if (type == LedgerType.REVERSAL)
        {
            throw new IllegalArgumentException("Use reverse() to create a REVERSAL entry.");
        }
    }
}
