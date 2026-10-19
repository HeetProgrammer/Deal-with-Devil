package org.example.deal_with_devil_csa.web.dto;

import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Tier;

/**
 * What a participant may see about a problem in the round preview. Deliberately has no HackerRank link,
 * hints or passwords: those are given out later (Phase 5 onwards), never in the preview.
 */
public record ProblemPreviewResponse(
        Long id,
        String title,
        String category,
        Tier tier,
        String teaser,
        int slots,
        long basePrice)
{
    public static ProblemPreviewResponse from(Problem problem)
    {
        return new ProblemPreviewResponse(problem.getId(), problem.getTitle(), problem.getCategory(),
                problem.getTier(), problem.getTeaser(), problem.getSlots(), problem.getBasePrice());
    }
}
