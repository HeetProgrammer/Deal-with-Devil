package org.example.deal_with_devil_csa.web.dto;

import java.time.Instant;

/** Set the bidding deadline (an ISO-8601 instant such as 2026-11-02T10:30:00Z), or send null to clear it. */
public record BiddingDeadlineRequest(Instant deadline)
{
}
