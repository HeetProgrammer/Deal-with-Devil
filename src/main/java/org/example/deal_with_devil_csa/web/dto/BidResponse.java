package org.example.deal_with_devil_csa.web.dto;

import java.time.Instant;

import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.BidStatus;
import org.example.deal_with_devil_csa.domain.Problem;

/**
 * A participant's own bid and, after settlement, its result. pricePaid is what a winner paid (not their bid).
 * The HackerRank link is only filled in for a WON bid, so it is never sent before the participant has earned it.
 * Needs the bid's problem loaded.
 */
public record BidResponse(
        Long problemId,
        String problemTitle,
        long amount,
        BidStatus status,
        Long pricePaid,
        String hackerRankLink,
        Instant submittedAt)
{
    public static BidResponse from(Bid bid)
    {
        Problem problem = bid.getProblem();
        return new BidResponse(problem.getId(), problem.getTitle(), bid.getAmount(), bid.getStatus(),
                bid.getPricePaid(), bid.getStatus() == BidStatus.WON ? problem.getHackerRankLink() : null,
                bid.getSubmittedAt());
    }
}
