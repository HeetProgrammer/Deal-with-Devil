package org.example.deal_with_devil_csa.service;

import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.LedgerType;
import org.example.deal_with_devil_csa.domain.Tier;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.web.dto.CustomAmountRequest;
import org.example.deal_with_devil_csa.web.dto.HintDeductionRequest;
import org.example.deal_with_devil_csa.web.dto.LedgerEntryResponse;
import org.example.deal_with_devil_csa.web.dto.PresetCreditRequest;
import org.example.deal_with_devil_csa.web.dto.UndoRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * The admin panel's pre-built wallet buttons. Each one turns a button press into a WalletService call with
 * the right amount (from GameProperties), ledger type and reason, so admins never type amounts by hand
 * except for the custom action. All coin movement still goes through WalletService.
 */
@Service
@RequiredArgsConstructor
public class AdminWalletService
{
    private final WalletService walletService;
    private final GameProperties gameProperties;

    /** "Solved Bronze / Silver / Gold": credits the configured solve reward for the tier. */
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
        return LedgerEntryResponse.from(entry);
    }

    /** "Hint": deducts the generic hint price (same for every problem). */
    public LedgerEntryResponse deductHint(Long adminId, HintDeductionRequest request)
    {
        LedgerEntry entry = walletService.deduct(request.userId(), gameProperties.hintPrice(),
                LedgerType.HINT_DEDUCTION, "Hint for problem #" + request.problemNumber(), adminId);
        return LedgerEntryResponse.from(entry);
    }

    /** "Custom": positive amount credits, negative deducts. Zero is rejected. */
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
        return LedgerEntryResponse.from(entry);
    }

    /** "Undo": appends a reversing entry. The original stays in the ledger. */
    public LedgerEntryResponse undo(Long adminId, Long ledgerEntryId, UndoRequest request)
    {
        LedgerEntry entry = walletService.reverse(ledgerEntryId,
                "Undo of entry #" + ledgerEntryId + ": " + request.reason().trim(), adminId);
        return LedgerEntryResponse.from(entry);
    }
}
