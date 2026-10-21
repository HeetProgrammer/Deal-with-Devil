package org.example.deal_with_devil_csa.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.domain.BulkCredit;
import org.example.deal_with_devil_csa.domain.EventType;
import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.LedgerType;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.Tier;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.BulkCreditRepository;
import org.example.deal_with_devil_csa.repository.LedgerEntryRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.web.dto.CreditAllRequest;
import org.example.deal_with_devil_csa.web.dto.CreditAllResponse;
import org.example.deal_with_devil_csa.web.dto.CustomAmountRequest;
import org.example.deal_with_devil_csa.web.dto.DevilActionRequest;
import org.example.deal_with_devil_csa.web.dto.DevilActionResponse;
import org.example.deal_with_devil_csa.web.dto.HintDeductionRequest;
import org.example.deal_with_devil_csa.web.dto.LedgerEntryResponse;
import org.example.deal_with_devil_csa.web.dto.PresetCreditRequest;
import org.example.deal_with_devil_csa.web.dto.UndoRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The admin panel's pre-built wallet buttons. Each one turns a button press into a WalletService call with
 * the right amount (from GameProperties), ledger type and reason, so admins never type amounts by hand
 * except for the custom action. All coin movement still goes through WalletService.
 *
 * Every button runs in one transaction together with the participant's notification (and, for the devil
 * buttons, the corruption level change), so the coins, the level and the pop-up can never disagree.
 */
@Service
@RequiredArgsConstructor
public class AdminWalletService
{
    private final WalletService walletService;
    private final GameProperties gameProperties;
    private final EventService eventService;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final BulkCreditRepository bulkCreditRepository;

    /** "Solved Bronze / Silver / Gold": credits the configured solve reward for the tier. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') and @guard.isSelf(#adminId)")
    public LedgerEntryResponse creditSolve(Long adminId, PresetCreditRequest request)
    {
        Tier tier = request.tier();
        long reward = switch (tier)
        {
            case BRONZE -> gameProperties.solveReward().bronze();
            case SILVER -> gameProperties.solveReward().silver();
            case GOLD -> gameProperties.solveReward().gold();
        };
        LedgerEntry entry = walletService.credit(request.userId(), reward, LedgerType.PRESET_CREDIT,
                "Solved " + tier.getLabel() + " problem", adminId);
        eventService.personal(request.userId(), EventType.PRESET_CREDIT,
                "You earned " + signed(reward) + " coins for solving a " + tier.getLabel() + " problem.",
                reward, entry.getBalanceAfter(), null, null);
        return LedgerEntryResponse.from(entry);
    }

    /** "Hint": deducts the generic hint price (same for every problem). */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') and @guard.isSelf(#adminId)")
    public LedgerEntryResponse deductHint(Long adminId, HintDeductionRequest request)
    {
        long price = gameProperties.hintPrice();
        LedgerEntry entry = walletService.deduct(request.userId(), price,
                LedgerType.HINT_DEDUCTION, "Hint for problem #" + request.problemNumber(), adminId);
        eventService.personal(request.userId(), EventType.HINT_DEDUCTION,
                "You bought a hint for problem #" + request.problemNumber() + ": " + signed(-price) + " coins.",
                -price, entry.getBalanceAfter(), null, null);
        return LedgerEntryResponse.from(entry);
    }

    /** "Custom": positive amount credits, negative deducts. Zero is rejected. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') and @guard.isSelf(#adminId)")
    public LedgerEntryResponse applyCustom(Long adminId, CustomAmountRequest request)
    {
        long amount = request.amount();
        LedgerEntry entry;
        if (amount > 0)
        {
            entry = walletService.credit(request.userId(), amount, LedgerType.CUSTOM, request.reason(), adminId);
        }
        else if (amount < 0)
        {
            // Long.MIN_VALUE negates to itself (still negative); WalletService rejects it as an invalid amount.
            entry = walletService.deduct(request.userId(), -amount, LedgerType.CUSTOM, request.reason(), adminId);
        }
        else
        {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "The amount cannot be zero.");
        }
        eventService.personal(request.userId(), EventType.CUSTOM_ADJUSTMENT,
                "An admin adjusted your coins by " + signed(amount) + ": " + request.reason().trim(),
                amount, entry.getBalanceAfter(), null, null);
        return LedgerEntryResponse.from(entry);
    }

    /**
     * "Devil's deal": the participant takes one corruption level and receives the configured payout.
     * Not possible at the highest level.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') and @guard.isSelf(#adminId)")
    public DevilActionResponse devilDeal(Long adminId, DevilActionRequest request)
    {
        Long userId = request.userId();
        User user = lockAndLoadUser(userId);
        int level = user.getCorruptionLevel();
        if (level >= User.MAX_CORRUPTION_LEVEL)
        {
            throw new ApiException(ErrorCode.CORRUPTION_LIMIT_REACHED, "Already at the highest corruption level.");
        }

        long payout = gameProperties.devil().dealPayout();
        LedgerEntry entry = walletService.credit(userId, payout, LedgerType.DEVIL_DEAL,
                "Devil's deal: corruption level " + level + " to " + (level + 1), adminId);
        user.setCorruptionLevel(level + 1);

        eventService.personal(userId, EventType.DEVIL_DEAL,
                "You made a deal with the devil: " + signed(payout) + " coins. Your corruption level is now "
                        + (level + 1) + ".",
                payout, entry.getBalanceAfter(), level + 1, null);
        return new DevilActionResponse(LedgerEntryResponse.from(entry), level + 1);
    }

    /**
     * "Redemption": the participant pays the configured cost to go down one corruption level.
     * Not possible at the lowest level, or without enough coins.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') and @guard.isSelf(#adminId)")
    public DevilActionResponse redeem(Long adminId, DevilActionRequest request)
    {
        Long userId = request.userId();
        User user = lockAndLoadUser(userId);
        int level = user.getCorruptionLevel();
        if (level <= User.MIN_CORRUPTION_LEVEL)
        {
            throw new ApiException(ErrorCode.CORRUPTION_LIMIT_REACHED, "Already at the lowest corruption level.");
        }

        long cost = gameProperties.devil().redemptionCost();
        LedgerEntry entry = walletService.deduct(userId, cost, LedgerType.REDEMPTION,
                "Redemption: corruption level " + level + " to " + (level - 1), adminId);
        user.setCorruptionLevel(level - 1);

        eventService.personal(userId, EventType.REDEMPTION,
                "You bought back some of your soul: " + signed(-cost) + " coins. Your corruption level is now "
                        + (level - 1) + ".",
                -cost, entry.getBalanceAfter(), level - 1, null);
        return new DevilActionResponse(LedgerEntryResponse.from(entry), level - 1);
    }

    /**
     * "Undo": appends a reversing entry. The original stays in the ledger. Undoing a devil's deal or a redemption
     * also puts the corruption level back where it was.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') and @guard.isSelf(#adminId)")
    public LedgerEntryResponse undo(Long adminId, Long ledgerEntryId, UndoRequest request)
    {
        LedgerEntry original = ledgerEntryRepository.findById(ledgerEntryId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Ledger entry not found."));
        Long userId = original.getUser().getId();
        LedgerType originalType = original.getType();

        LedgerEntry entry = walletService.reverse(ledgerEntryId,
                "Undo of entry #" + ledgerEntryId + ": " + request.reason().trim(), adminId);

        Integer restoredLevel = null;
        if (originalType == LedgerType.DEVIL_DEAL || originalType == LedgerType.REDEMPTION)
        {
            // reverse() has locked the wallet by now, so this read sees the latest level.
            User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
            int level = user.getCorruptionLevel() + (originalType == LedgerType.DEVIL_DEAL ? -1 : 1);
            if (level < User.MIN_CORRUPTION_LEVEL || level > User.MAX_CORRUPTION_LEVEL)
            {
                // Throwing rolls the whole undo back, coins included.
                throw new ApiException(ErrorCode.CORRUPTION_LIMIT_REACHED,
                        "The corruption level cannot be put back, so this entry cannot be undone.");
            }
            user.setCorruptionLevel(level);
            restoredLevel = level;
        }

        eventService.personal(userId, EventType.REVERSAL,
                "A previous change was undone: " + signed(entry.getAmount()) + " coins.",
                entry.getAmount(), entry.getBalanceAfter(), restoredLevel, null);
        return LedgerEntryResponse.from(entry);
    }

    /**
     * "Credit everyone": gives every participant (role PARTICIPANT, profile complete or not) the same number of coins
     * in one transaction, for the economy between rounds. Each participant gets their own ledger entry (type CUSTOM,
     * the admin, the reason, the balance after) through WalletService.credit, plus their own pop-up, so a wrong run is
     * undone entry by entry with the normal undo. People who sign up after the press do not get the coins.
     *
     * The request id is claimed first, before any coin moves. It has a unique constraint, so a double click, a retry or
     * a second tab with the same id is refused with CONFLICT and credits nobody a second time (a request that arrives
     * at the same moment waits for the first to finish, then fails the same way). Wallets are locked in user id order,
     * so two different presses at once cannot deadlock.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') and @guard.isSelf(#adminId)")
    public CreditAllResponse creditAll(Long adminId, CreditAllRequest request)
    {
        String requestId = request.requestId().trim();
        String reason = request.reason().trim();
        long amount = request.amount();

        List<Wallet> wallets = walletRepository.findAllByUserRole(Role.PARTICIPANT).stream()
                .sorted(Comparator.comparing((Wallet wallet) -> wallet.getUser().getId()))
                .toList();
        if (wallets.isEmpty())
        {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                    Map.of("participants", "There are no participants to credit yet."));
        }

        if (bulkCreditRepository.existsByRequestId(requestId))
        {
            throw alreadyApplied();
        }
        BulkCredit press = new BulkCredit();
        press.setRequestId(requestId);
        press.setAdminId(adminId);
        press.setAmountEach(amount);
        press.setParticipants(wallets.size());
        press.setReason(reason);
        try
        {
            bulkCreditRepository.saveAndFlush(press);
        }
        catch (DataIntegrityViolationException e)
        {
            // Another request with the same id got in first (it was still running when we checked above).
            throw alreadyApplied();
        }

        for (Wallet wallet : wallets)
        {
            Long userId = wallet.getUser().getId();
            LedgerEntry entry = walletService.credit(userId, amount, LedgerType.CUSTOM, reason, adminId);
            eventService.personal(userId, EventType.CUSTOM_ADJUSTMENT,
                    "An admin credited every participant " + signed(amount) + " coins: " + reason,
                    amount, entry.getBalanceAfter(), null, null);
        }
        return new CreditAllResponse(wallets.size(), amount, amount * wallets.size());
    }

    private static ApiException alreadyApplied()
    {
        return new ApiException(ErrorCode.CONFLICT,
                "This credit was already applied (the same request id was used twice). Nobody was credited again.");
    }

    // ---- Internals ----

    /** Locks the participant's wallet first (the same lock every coin movement takes), then reads the user fresh. */
    private User lockAndLoadUser(Long userId)
    {
        walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    private static String signed(long amount)
    {
        return amount > 0 ? "+" + amount : String.valueOf(amount);
    }
}
