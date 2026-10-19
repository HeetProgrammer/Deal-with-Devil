package org.example.deal_with_devil_csa.domain;

/**
 * Where a bid stands. Every bid starts PENDING and is resolved exactly once, by settlement.
 */
public enum BidStatus
{
    PENDING,        // placed, round not settled yet
    WON,            // got a slot and paid the clearing price
    LOST,           // did not get a slot
    UNAFFORDABLE    // would have won, but the participant's balance no longer covered the price at settlement
}
