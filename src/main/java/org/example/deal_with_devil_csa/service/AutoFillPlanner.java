package org.example.deal_with_devil_csa.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The auto fill rule (Phase 9) as a pure function with no database or Spring, so it can be tested exhaustively.
 * After settlement some participants hold fewer than the required number of problems. This decides which unsold
 * slots they are given, at each problem's price (the reserve price).
 *
 * Rules, applied one problem at a time until nobody can be helped any further:
 * <ol>
 *   <li>Take the person with the fewest problems so far (equal: the lower user id) who is still short and not yet
 *       known to be stuck.</li>
 *   <li>Among the problems that still have an unsold slot, that this person does not already hold and whose price
 *       they can afford, give them the one with the most unsold slots (equal: the lower problem id).</li>
 *   <li>If there is no such problem the person is stuck and stays short; they are left for manual assignment.</li>
 * </ol>
 * Helping one problem at a time (instead of finishing one person first) spreads a scarce pool of unsold slots evenly,
 * and the order depends only on the numbers, so the same input always gives the same result.
 *
 * Nobody is ever given more than the target, a problem is never given out more often than it has unsold slots, and a
 * person is never given a problem they already hold or cannot pay for.
 */
public final class AutoFillPlanner
{
    private AutoFillPlanner()
    {
    }

    /** A participant: their coins and the problems they already won in this round. */
    public record Participant(long userId, long balance, Set<Long> heldProblemIds)
    {
    }

    /** A problem of the round: how many slots are still unsold and what one slot costs. */
    public record ProblemState(long problemId, int unsoldSlots, long price)
    {
    }

    /** One problem given to one person at the given price. */
    public record Assignment(long userId, long problemId, long price)
    {
    }

    /** assignments in the order they were made; winsByUser is every participant's number of problems afterwards. */
    public record Plan(List<Assignment> assignments, Map<Long, Integer> winsByUser)
    {
    }

    public static Plan plan(List<Participant> participants, List<ProblemState> problems, int target)
    {
        if (target < 1)
        {
            throw new IllegalArgumentException("The target must be at least one problem per person.");
        }

        // Problems in id order, so "the lower problem id" is simply the first one seen.
        List<ProblemState> ordered = problems.stream()
                .sorted(Comparator.comparingLong(ProblemState::problemId))
                .toList();
        Map<Long, Integer> unsold = new HashMap<>();
        for (ProblemState problem : ordered)
        {
            unsold.put(problem.problemId(), Math.max(0, problem.unsoldSlots()));
        }

        Map<Long, Long> balance = new HashMap<>();
        Map<Long, Set<Long>> held = new HashMap<>();
        Map<Long, Integer> wins = new LinkedHashMap<>();
        for (Participant participant : participants)
        {
            Set<Long> own = new HashSet<>(participant.heldProblemIds());
            balance.put(participant.userId(), participant.balance());
            held.put(participant.userId(), own);
            wins.put(participant.userId(), own.size());
        }

        Set<Long> stuck = new HashSet<>();
        List<Assignment> assignments = new ArrayList<>();
        while (true)
        {
            Long next = null;
            for (Map.Entry<Long, Integer> entry : wins.entrySet())
            {
                long userId = entry.getKey();
                int count = entry.getValue();
                if (count >= target || stuck.contains(userId))
                {
                    continue;
                }
                if (next == null || count < wins.get(next) || (count == wins.get(next) && userId < next))
                {
                    next = userId;
                }
            }
            if (next == null)
            {
                break;
            }

            ProblemState best = null;
            for (ProblemState problem : ordered)
            {
                int free = unsold.get(problem.problemId());
                if (free <= 0
                        || held.get(next).contains(problem.problemId())
                        || balance.get(next) < problem.price())
                {
                    continue;
                }
                if (best == null || free > unsold.get(best.problemId()))
                {
                    best = problem;   // strictly more unsold slots; on a tie the earlier (lower id) problem stays
                }
            }
            if (best == null)
            {
                stuck.add(next);
                continue;
            }

            unsold.merge(best.problemId(), -1, Integer::sum);
            balance.merge(next, -best.price(), Long::sum);
            held.get(next).add(best.problemId());
            wins.merge(next, 1, Integer::sum);
            assignments.add(new Assignment(next, best.problemId(), best.price()));
        }
        return new Plan(List.copyOf(assignments), Map.copyOf(wins));
    }
}
