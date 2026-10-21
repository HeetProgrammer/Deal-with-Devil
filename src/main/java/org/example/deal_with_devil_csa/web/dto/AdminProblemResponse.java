package org.example.deal_with_devil_csa.web.dto;

import java.util.List;

import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.ProblemHint;
import org.example.deal_with_devil_csa.domain.Tier;

/** Full problem for the admin panel: link, reserve price, round and all hint versions with passwords. */
public record AdminProblemResponse(
        Long id,
        String title,
        String category,
        Tier tier,
        int slots,
        String hackerRankLink,
        long basePrice,
        String teaser,
        String preview,
        Long roundId,
        List<HintResponse> hints)
{
    /** Call inside a transaction: reads the lazy round reference. */
    public static AdminProblemResponse from(Problem problem, List<ProblemHint> hints)
    {
        return new AdminProblemResponse(problem.getId(), problem.getTitle(), problem.getCategory(),
                problem.getTier(), problem.getSlots(), problem.getHackerRankLink(), problem.getBasePrice(),
                problem.getTeaser(), problem.getPreview(),
                problem.getRound() == null ? null : problem.getRound().getId(),
                hints.stream().map(HintResponse::from).toList());
    }
}
