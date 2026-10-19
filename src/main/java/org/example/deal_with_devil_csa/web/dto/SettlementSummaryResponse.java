package org.example.deal_with_devil_csa.web.dto;

import java.util.List;

/**
 * What the admin sees after running settlement: one line per problem of the round, plus the coins collected.
 * unaffordableCount is the number of would-be winners whose balance no longer covered the price.
 */
public record SettlementSummaryResponse(
        Long roundId,
        int roundNumber,
        long totalCollected,
        List<ProblemResult> problems)
{
    public record ProblemResult(
            Long problemId,
            String title,
            int bidCount,
            int winnerCount,
            int unaffordableCount,
            long clearingPrice)
    {
    }
}
