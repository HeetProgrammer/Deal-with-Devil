package org.example.deal_with_devil_csa.web.controller;

import java.util.List;

import org.example.deal_with_devil_csa.service.AdminLedgerService;
import org.example.deal_with_devil_csa.web.dto.AdminLedgerEntryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Ledger viewer for the admin panel (with the "undone" flag so the UI knows when Undo is still possible). */
@RestController
@RequestMapping("/api/admin/ledger")
@RequiredArgsConstructor
public class AdminLedgerController
{
    private final AdminLedgerService adminLedgerService;

    /** Newest first. Optional ?userId= for one participant; ?limit= defaults to 100, at most 500. */
    @GetMapping
    public List<AdminLedgerEntryResponse> ledger(@RequestParam(required = false) Long userId,
                                                 @RequestParam(required = false) Integer limit)
    {
        return adminLedgerService.recent(userId, limit);
    }
}
