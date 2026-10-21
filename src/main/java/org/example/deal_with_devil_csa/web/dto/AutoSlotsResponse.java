package org.example.deal_with_devil_csa.web.dto;

import java.util.List;

/**
 * The result of the auto slots button. participants is N (participants with a completed profile), problemCount is P,
 * slotsNeeded is wins-per-round x N and slotsAssigned is what the problems got (equal to slotsNeeded whenever the
 * round has at least wins-per-round problems). With dryRun = true nothing was saved: this is what the button would do.
 */
public record AutoSlotsResponse(
        boolean dryRun,
        int participants,
        int problemCount,
        int slotsNeeded,
        int slotsAssigned,
        List<ProblemSlots> slotsPerProblem)
{
    public record ProblemSlots(Long problemId, String title, int slots)
    {
    }
}
