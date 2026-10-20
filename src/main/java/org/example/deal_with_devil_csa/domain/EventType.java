package org.example.deal_with_devil_csa.domain;

/**
 * What a notification or pop-up is about. The first group are personal events (they have one recipient),
 * the last two are broadcast to everyone.
 */
public enum EventType
{
    BID_WON,
    BID_LOST,
    BID_UNAFFORDABLE,
    PRESET_CREDIT,
    HINT_DEDUCTION,
    DEVIL_DEAL,
    REDEMPTION,
    CUSTOM_ADJUSTMENT,
    REVERSAL,
    ROUND_STARTED,      // broadcast
    PHASE_CHANGED       // broadcast
}
