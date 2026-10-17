package org.example.deal_with_devil_csa.domain;

/**
 * Why a ledger entry exists. Stored as text so the ledger stays readable in the database.
 */
public enum LedgerType
{
    PRESET_CREDIT,      // solved Bronze / Silver / Gold
    HINT_DEDUCTION,     // admin deducts the generic hint price
    BID_SETTLEMENT,     // winner pays the Vickrey price
    DEVIL_DEAL,         // corruption up, coins up
    REDEMPTION,         // corruption down, coins down
    CUSTOM,             // admin custom amount (credit or deduction)
    REVERSAL            // undo of an earlier entry
}
