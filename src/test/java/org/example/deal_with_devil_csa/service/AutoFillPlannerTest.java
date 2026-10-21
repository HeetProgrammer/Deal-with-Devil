package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.example.deal_with_devil_csa.service.AutoFillPlanner.Assignment;
import org.example.deal_with_devil_csa.service.AutoFillPlanner.Participant;
import org.example.deal_with_devil_csa.service.AutoFillPlanner.Plan;
import org.example.deal_with_devil_csa.service.AutoFillPlanner.ProblemState;
import org.junit.jupiter.api.Test;

/** The auto fill rule is a pure function, so these tests need no database, no Spring and no mocks. */
class AutoFillPlannerTest
{
    private static final int TARGET = 3;

    @Test
    void nobodyIsShort_nothingIsAssigned()
    {
        Plan plan = AutoFillPlanner.plan(
                List.of(person(1, 500, 10, 20, 30), person(2, 500, 10, 20, 30)),
                List.of(problem(10, 2, 50), problem(40, 5, 50)),
                TARGET);

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.winsByUser()).containsEntry(1L, 3).containsEntry(2L, 3);
    }

    @Test
    void aShortPersonGetsTheMissingProblemsAtTheReservePrice()
    {
        Plan plan = AutoFillPlanner.plan(
                List.of(person(1, 500, 10)),
                List.of(problem(10, 3, 50), problem(20, 1, 40), problem(30, 1, 60)),
                TARGET);

        // Problem 10 is already held. The two others each have one unsold slot, so the lower id comes first.
        assertThat(pairs(plan)).containsExactly("1:20@40", "1:30@60");
        assertThat(plan.winsByUser()).containsEntry(1L, 3);
    }

    @Test
    void theProblemWithTheMostUnsoldSlotsIsChosenFirst_andTheLowerIdBreaksTies()
    {
        Plan plan = AutoFillPlanner.plan(
                List.of(person(1, 500)),
                List.of(problem(10, 1, 50), problem(20, 4, 50), problem(30, 4, 50), problem(40, 2, 50)),
                TARGET);

        // 20 and 30 tie on 4 unsold slots, so the lower id (20) comes first. Then 30 has the most (4). Then 20 and 30
        // are held, so 40 (2 slots) beats 10 (1 slot).
        assertThat(pairs(plan)).containsExactly("1:20@50", "1:30@50", "1:40@50");
    }

    @Test
    void aPersonIsNeverGivenAProblemTheyAlreadyHold_soTheyCanEndUpStuck()
    {
        // The only unsold slot is on a problem the short person already won.
        Plan plan = AutoFillPlanner.plan(
                List.of(person(1, 500, 10, 20)),
                List.of(problem(10, 1, 50), problem(20, 0, 50), problem(30, 0, 50)),
                TARGET);

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.winsByUser()).containsEntry(1L, 2);
    }

    @Test
    void aPersonWhoCannotAffordAProblemIsSkippedForIt_butCanTakeACheaperOne()
    {
        Plan plan = AutoFillPlanner.plan(
                List.of(person(1, 60)),
                List.of(problem(10, 5, 100), problem(20, 1, 50), problem(30, 1, 50)),
                TARGET);

        // Problem 10 has the most unsold slots but costs 100; after paying 50 for problem 20 there are only 10 coins left.
        assertThat(pairs(plan)).containsExactly("1:20@50");
        assertThat(plan.winsByUser()).containsEntry(1L, 1);
    }

    @Test
    void theBalanceIsTrackedAcrossSeveralAssignments()
    {
        Plan plan = AutoFillPlanner.plan(
                List.of(person(1, 100)),
                List.of(problem(10, 1, 40), problem(20, 1, 40), problem(30, 1, 40)),
                TARGET);

        assertThat(pairs(plan)).containsExactly("1:10@40", "1:20@40");   // 100 - 80 = 20 is not enough for the third
    }

    @Test
    void aFreeProblemCostsNothing()
    {
        Plan plan = AutoFillPlanner.plan(
                List.of(person(1, 0)),
                List.of(problem(10, 3, 0)),
                TARGET);

        assertThat(pairs(plan)).containsExactly("1:10@0");
    }

    @Test
    void theFewestWinsGoFirst_soAScarcePoolIsSpreadEvenly()
    {
        // User 1 has no problem, user 2 has two. Only two unsold slots exist (problems 10 and 20, one each).
        Plan plan = AutoFillPlanner.plan(
                List.of(person(2, 500, 30, 40), person(1, 500)),
                List.of(problem(10, 1, 50), problem(20, 1, 50)),
                TARGET);

        // User 1 (0 wins) gets problem 10; then user 1 (1 win) is still behind user 2 (2 wins), so user 1 gets 20 as well.
        assertThat(pairs(plan)).containsExactly("1:10@50", "1:20@50");
        assertThat(plan.winsByUser()).containsEntry(1L, 2).containsEntry(2L, 2);
    }

    @Test
    void equalWinsGoToTheLowerUserId_whateverTheInputOrder()
    {
        Plan plan = AutoFillPlanner.plan(
                List.of(person(7, 500, 10, 20), person(3, 500, 10, 20)),
                List.of(problem(30, 1, 50)),
                TARGET);

        assertThat(pairs(plan)).containsExactly("3:30@50");
    }

    @Test
    void theTargetComesFromTheArgument_notFromAHardcodedThree()
    {
        List<Participant> people = List.of(person(1, 500));
        List<ProblemState> problems = List.of(problem(10, 1, 10), problem(20, 1, 10), problem(30, 1, 10),
                problem(40, 1, 10), problem(50, 1, 10));

        assertThat(AutoFillPlanner.plan(people, problems, 2).assignments()).hasSize(2);
        assertThat(AutoFillPlanner.plan(people, problems, 5).assignments()).hasSize(5);
    }

    @Test
    void theCallersListsAreNotModified()
    {
        Set<Long> held = new HashSet<>(Set.of(10L));
        List<Participant> people = List.of(new Participant(1, 500, held));
        List<ProblemState> problems = List.of(problem(20, 1, 50), problem(10, 1, 50));

        AutoFillPlanner.plan(people, problems, TARGET);

        assertThat(held).containsExactly(10L);
        assertThat(problems).extracting(ProblemState::problemId).containsExactly(20L, 10L);
    }

    @Test
    void theTargetMustBeAtLeastOne()
    {
        assertThatThrownBy(() -> AutoFillPlanner.plan(List.of(), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Random situations, checked against the rules instead of exact outputs: nobody exceeds the target, no problem is
     * given out more often than it has unsold slots, nobody gets a problem twice or one they could not pay for, and
     * the result is maximal (anyone left short truly has no affordable problem with a free slot that they do not hold).
     */
    @Test
    void randomSituations_keepEveryRule()
    {
        Random random = new Random(11);
        for (int run = 0; run < 500; run++)
        {
            int problemCount = 3 + random.nextInt(6);
            List<ProblemState> problems = new ArrayList<>();
            Map<Long, Integer> unsold = new HashMap<>();
            Map<Long, Long> price = new HashMap<>();
            for (long p = 1; p <= problemCount; p++)
            {
                int free = random.nextInt(4);
                long cost = random.nextInt(4) * 25L;
                problems.add(problem(p, free, cost));
                unsold.put(p, free);
                price.put(p, cost);
            }

            int people = 1 + random.nextInt(8);
            List<Participant> participants = new ArrayList<>();
            Map<Long, Set<Long>> held = new HashMap<>();
            Map<Long, Long> balance = new HashMap<>();
            for (long u = 1; u <= people; u++)
            {
                Set<Long> own = new HashSet<>();
                int owned = random.nextInt(4);
                for (int i = 0; i < owned; i++)
                {
                    own.add(1L + random.nextInt(problemCount));
                }
                long coins = random.nextInt(8) * 25L;
                participants.add(new Participant(u, coins, own));
                held.put(u, new HashSet<>(own));
                balance.put(u, coins);
            }

            Plan plan = AutoFillPlanner.plan(participants, problems, TARGET);

            for (Assignment a : plan.assignments())
            {
                assertThat(held.get(a.userId())).as("run %d: never a problem they already hold", run)
                        .doesNotContain(a.problemId());
                assertThat(a.price()).isEqualTo(price.get(a.problemId()));
                held.get(a.userId()).add(a.problemId());
                unsold.merge(a.problemId(), -1, Integer::sum);
                balance.merge(a.userId(), -a.price(), Long::sum);
                assertThat(unsold.get(a.problemId())).as("run %d: not more than the unsold slots", run)
                        .isGreaterThanOrEqualTo(0);
                assertThat(balance.get(a.userId())).as("run %d: never below zero", run).isGreaterThanOrEqualTo(0);
            }
            for (long u = 1; u <= people; u++)
            {
                int wins = plan.winsByUser().get(u);
                assertThat(wins).as("run %d: user %d final wins", run, u).isEqualTo(held.get(u).size());
                assertThat(wins).as("run %d: never above the target", run).isLessThanOrEqualTo(TARGET);
                if (wins < TARGET)
                {
                    for (long p = 1; p <= problemCount; p++)
                    {
                        boolean couldTake = unsold.get(p) > 0 && !held.get(u).contains(p) && balance.get(u) >= price.get(p);
                        assertThat(couldTake).as("run %d: user %d could still take problem %d", run, u, p).isFalse();
                    }
                }
            }
        }
    }

    // ---- helpers ----

    private static Participant person(long userId, long balance, long... held)
    {
        Set<Long> own = new HashSet<>();
        for (long problemId : held)
        {
            own.add(problemId);
        }
        return new Participant(userId, balance, own);
    }

    private static ProblemState problem(long id, int unsold, long price)
    {
        return new ProblemState(id, unsold, price);
    }

    /** "userId:problemId@price" for every assignment, in order, so assertions read like the scenario. */
    private static List<String> pairs(Plan plan)
    {
        return plan.assignments().stream().map(a -> a.userId() + ":" + a.problemId() + "@" + a.price()).toList();
    }
}
