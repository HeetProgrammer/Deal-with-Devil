package org.example.deal_with_devil_csa.web.dto;

import java.util.List;

/**
 * The result of the auto fill button (or of its dry run). assignments are the problems given out, in the order they
 * were given, each at the problem's reserve price. stillShort are the participants who still have fewer than the
 * required number of problems afterwards (no affordable problem with a free slot that they do not already hold);
 * the admin finishes those by hand with a manual assignment. With dryRun = true nothing was saved.
 */
public record AutoFillResponse(
        boolean dryRun,
        int winsPerRound,
        List<Assigned> assignments,
        List<StillShort> stillShort)
{
    public record Assigned(
            Long userId,
            String name,
            String hackerRankId,
            Long problemId,
            String problemTitle,
            long price)
    {
    }

    public record StillShort(
            Long userId,
            String name,
            String hackerRankId,
            int wins,
            int missing)
    {
    }
}
