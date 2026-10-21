package org.example.deal_with_devil_csa.web.dto;

import java.util.List;

/**
 * Who has bid on how many problems in the current round, so the admin can chase the people who have bid on few or
 * none before bidding closes. missingBids is the number of problems of the round the participant has not bid on yet.
 * participants is sorted with the fewest bids first.
 */
public record BidCoverageResponse(
        Long roundId,
        int roundNumber,
        int problemCount,
        int winsPerRound,
        List<ParticipantBids> participants)
{
    public record ParticipantBids(
            Long userId,
            String name,
            String hackerRankId,
            int bidCount,
            int missingBids)
    {
    }
}
