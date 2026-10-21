package org.example.deal_with_devil_csa.web.dto;

import java.util.List;

/**
 * The admin's allocation view after settlement: every participant's number of problems (anyone not at exactly
 * winsPerRound is flagged) and the unsold slots of every problem. participants lists the people who are not at the
 * target first.
 *
 * sold counts the WON bids of a problem. A slot whose winner turned out to be unaffordable counts as unsold (it is
 * shown in unaffordable as well), so auto fill can hand it to somebody else. overCapacity is the number of winners
 * above the problem's slots, which only a manual assignment can cause.
 */
public record AllocationViewResponse(
        Long roundId,
        int roundNumber,
        int winsPerRound,
        int participantCount,
        int atTargetCount,
        List<ParticipantAllocation> participants,
        List<ProblemAllocation> problems)
{
    public record ParticipantAllocation(
            Long userId,
            String name,
            String hackerRankId,
            long coins,
            int wins,
            int unaffordableBids,
            int missing,
            int excess,
            boolean atTarget)
    {
    }

    public record ProblemAllocation(
            Long problemId,
            String title,
            int slots,
            int sold,
            int unaffordable,
            int unsold,
            int overCapacity)
    {
    }
}
