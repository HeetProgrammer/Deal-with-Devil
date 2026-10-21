package org.example.deal_with_devil_csa.web.controller;

import org.example.deal_with_devil_csa.security.CurrentUser;
import org.example.deal_with_devil_csa.service.AdminWalletService;
import org.example.deal_with_devil_csa.web.dto.CreditAllRequest;
import org.example.deal_with_devil_csa.web.dto.CreditAllResponse;
import org.example.deal_with_devil_csa.web.dto.CustomAmountRequest;
import org.example.deal_with_devil_csa.web.dto.DevilActionRequest;
import org.example.deal_with_devil_csa.web.dto.DevilActionResponse;
import org.example.deal_with_devil_csa.web.dto.HintDeductionRequest;
import org.example.deal_with_devil_csa.web.dto.LedgerEntryResponse;
import org.example.deal_with_devil_csa.web.dto.PresetCreditRequest;
import org.example.deal_with_devil_csa.web.dto.UndoRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin wallet buttons. /api/admin/** is already restricted to ADMIN in SecurityConfig.
 * Each call returns the new ledger entry so the admin panel can show the balance after the change.
 */
@RestController
@RequestMapping("/api/admin/wallet")
@RequiredArgsConstructor
public class AdminWalletController
{
    private final AdminWalletService adminWalletService;

    @PostMapping("/credit-preset")
    public LedgerEntryResponse creditPreset(@AuthenticationPrincipal CurrentUser admin,
                                            @Valid @RequestBody PresetCreditRequest request)
    {
        return adminWalletService.creditSolve(admin.id(), request);
    }

    @PostMapping("/hint-deduction")
    public LedgerEntryResponse deductHint(@AuthenticationPrincipal CurrentUser admin,
                                          @Valid @RequestBody HintDeductionRequest request)
    {
        return adminWalletService.deductHint(admin.id(), request);
    }

    @PostMapping("/custom")
    public LedgerEntryResponse custom(@AuthenticationPrincipal CurrentUser admin,
                                      @Valid @RequestBody CustomAmountRequest request)
    {
        return adminWalletService.applyCustom(admin.id(), request);
    }

    @PostMapping("/devil-deal")
    public DevilActionResponse devilDeal(@AuthenticationPrincipal CurrentUser admin,
                                         @Valid @RequestBody DevilActionRequest request)
    {
        return adminWalletService.devilDeal(admin.id(), request);
    }

    @PostMapping("/redemption")
    public DevilActionResponse redemption(@AuthenticationPrincipal CurrentUser admin,
                                          @Valid @RequestBody DevilActionRequest request)
    {
        return adminWalletService.redeem(admin.id(), request);
    }

    /**
     * "Credit everyone": the same coins for every participant, one ledger entry and one pop-up each. The request id
     * makes a double click or a retry harmless (the repeat gets 409 CONFLICT and credits nobody again).
     */
    @PostMapping("/credit-all")
    public CreditAllResponse creditAll(@AuthenticationPrincipal CurrentUser admin,
                                       @Valid @RequestBody CreditAllRequest request)
    {
        return adminWalletService.creditAll(admin.id(), request);
    }

    @PostMapping("/undo/{entryId}")
    public LedgerEntryResponse undo(@AuthenticationPrincipal CurrentUser admin,
                                    @PathVariable Long entryId,
                                    @Valid @RequestBody UndoRequest request)
    {
        return adminWalletService.undo(admin.id(), entryId, request);
    }
}
