package org.example.deal_with_devil_csa.web.dto;

/** The ledger entry the devil action created, and the participant's corruption level after it. */
public record DevilActionResponse(LedgerEntryResponse entry, int corruptionLevel)
{
}
