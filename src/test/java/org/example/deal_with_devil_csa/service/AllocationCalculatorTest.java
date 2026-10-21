package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.example.deal_with_devil_csa.service.AllocationCalculator.Allocation;
import org.example.deal_with_devil_csa.service.AllocationCalculator.BidEntry;
import org.example.deal_with_devil_csa.service.AllocationCalculator.ProblemSpec;
import org.junit.jupiter.api.Test;

/** The whole-round allocation is a pure function, so these tests need no database, no Spring and no mocks. */
class AllocationCalculatorTest
{
    private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");
    private static final int CAP = 3;

    // ---- the cap of three wins per person ----

    @Test
    void aPersonNeverWinsMoreThanThree_andTheirHighestBidsAreTheOnesThatWin()
    {
        // One slot each, reserve 20. User 1 bids 100, 90, 80, 70, 60 on problems 1 to 5; user 2 bids 50 on all five.
        List<ProblemSpec> problems = List.of(problem(1, 1, 20), problem(2, 1, 20), problem(3, 1, 20),
                problem(4, 1, 20), problem(5, 1, 20));
        long[] user1 = {100, 90, 80, 70, 60};
        List<BidEntry> bids = new ArrayList<>();
        for (int i = 0; i < 5; i++)
        {
            bids.add(bid(i + 1, 1, i + 1, user1[i], i));
            bids.add(bid(i + 11, 2, i + 1, 50, 10 + i));
        }

        Allocation allocation = AllocationCalculator.resolve(problems, bids, CAP);

        assertThat(winnerPairs(allocation)).containsExactlyInAnyOrder("1:1", "1:2", "1:3", "2:4", "2:5");
        // Problems 1 to 3 were full when user 2's bid of 50 arrived, so 50 sets their price. Problems 4 and 5 were
        // not full (user 1's bids there were turned away because user 1 is capped), so they cost the reserve.
        assertThat(allocation.priceByProblem()).containsEntry(1L, 50L).containsEntry(2L, 50L).containsEntry(3L, 50L)
                .containsEntry(4L, 20L).containsEntry(5L, 20L);
    }

    @Test
    void aCappedBidThatArrivesAtAFullProblemDoesNotSetItsPrice()
    {
        // User 1 takes problems 1, 2, 3 (cap reached). User 3 wins problem 5. User 1's later bid of 70 on problem 5
        // is turned away because of the cap, so the price of problem 5 is user 2's 60, not 70.
        List<ProblemSpec> problems = List.of(problem(1, 1, 20), problem(2, 1, 20), problem(3, 1, 20), problem(5, 1, 20));
        List<BidEntry> bids = List.of(
                bid(1, 1, 1, 100, 0),
                bid(2, 1, 2, 90, 1),
                bid(3, 1, 3, 80, 2),
                bid(4, 1, 5, 70, 3),
                bid(5, 3, 5, 85, 4),
                bid(6, 2, 5, 60, 5));

        Allocation allocation = AllocationCalculator.resolve(problems, bids, CAP);

        assertThat(winnerPairs(allocation)).containsExactlyInAnyOrder("1:1", "1:2", "1:3", "3:5");
        assertThat(allocation.priceByProblem()).containsEntry(5L, 60L).containsEntry(1L, 20L);
    }

    @Test
    void aPersonWithFewerThanThreeBidsGetsFewerThanThreeWins()
    {
        List<ProblemSpec> problems = List.of(problem(1, 1, 20), problem(2, 1, 20), problem(3, 1, 20));
        List<BidEntry> bids = List.of(bid(1, 1, 1, 100, 0), bid(2, 1, 2, 90, 1));

        Allocation allocation = AllocationCalculator.resolve(problems, bids, CAP);

        assertThat(winnerPairs(allocation)).containsExactlyInAnyOrder("1:1", "1:2");
        assertThat(allocation.priceByProblem()).containsEntry(1L, 20L).containsEntry(2L, 20L).containsEntry(3L, 20L);
    }

    @Test
    void theCapComesFromTheArgument_notFromAHardcodedThree()
    {
        List<ProblemSpec> problems = List.of(problem(1, 1, 20), problem(2, 1, 20), problem(3, 1, 20));
        List<BidEntry> bids = List.of(bid(1, 1, 1, 100, 0), bid(2, 1, 2, 90, 1), bid(3, 1, 3, 80, 2));

        assertThat(AllocationCalculator.resolve(problems, bids, 2).winners()).hasSize(2);
        assertThat(AllocationCalculator.resolve(problems, bids, 5).winners()).hasSize(3);
    }

    // ---- ranking and ties ----

    @Test
    void equalBidsGoToTheEarlierSubmission_andTheLoserSetsThePrice()
    {
        List<ProblemSpec> problems = List.of(problem(1, 1, 20));
        List<BidEntry> bids = List.of(bid(1, 1, 1, 80, 10), bid(2, 2, 1, 80, 2));

        Allocation allocation = AllocationCalculator.resolve(problems, bids, CAP);

        assertThat(winnerPairs(allocation)).containsExactly("2:1");
        assertThat(allocation.priceByProblem()).containsEntry(1L, 80L);
    }

    @Test
    void ifEvenTheTimeIsEqualTheLowerBidIdWins()
    {
        List<ProblemSpec> problems = List.of(problem(1, 1, 20));
        List<BidEntry> bids = List.of(bid(7, 1, 1, 80, 3), bid(4, 2, 1, 80, 3));

        assertThat(winnerPairs(AllocationCalculator.resolve(problems, bids, CAP))).containsExactly("2:1");
    }

    @Test
    void winnersAreReturnedInRankingOrderWhateverTheInputOrder()
    {
        List<ProblemSpec> problems = List.of(problem(1, 2, 20), problem(2, 2, 20));
        List<BidEntry> bids = List.of(bid(1, 1, 1, 30, 0), bid(2, 2, 2, 90, 1), bid(3, 3, 1, 60, 2));

        Allocation allocation = AllocationCalculator.resolve(problems, bids, CAP);

        assertThat(allocation.winners()).extracting(BidEntry::bidId).containsExactly(2L, 3L, 1L);
    }

    // ---- reserve price ----

    @Test
    void bidsBelowTheReserveAreIgnored_andAProblemWithoutEligibleBidsCostsTheReserve()
    {
        List<ProblemSpec> problems = List.of(problem(1, 1, 50), problem(2, 1, 50));
        List<BidEntry> bids = List.of(bid(1, 1, 1, 100, 0), bid(2, 2, 1, 30, 1), bid(3, 3, 2, 49, 2));

        Allocation allocation = AllocationCalculator.resolve(problems, bids, CAP);

        assertThat(winnerPairs(allocation)).containsExactly("1:1");
        assertThat(allocation.priceByProblem()).containsEntry(1L, 50L).containsEntry(2L, 50L);   // 30 never sets a price
    }

    @Test
    void aBidOnAProblemOutsideTheRoundIsIgnored()
    {
        List<ProblemSpec> problems = List.of(problem(1, 1, 20));
        List<BidEntry> bids = List.of(bid(1, 1, 99, 500, 0), bid(2, 2, 1, 40, 1));

        Allocation allocation = AllocationCalculator.resolve(problems, bids, CAP);

        assertThat(winnerPairs(allocation)).containsExactly("2:1");
    }

    // ---- equals the old rule when the cap does not bite ----

    @Test
    void withNoEffectiveCapItEqualsTheOldPerProblemVickreyRule()
    {
        Random random = new Random(7);
        for (int run = 0; run < 200; run++)
        {
            List<ProblemSpec> problems = new ArrayList<>();
            for (long p = 1; p <= 4; p++)
            {
                problems.add(problem(p, 1 + random.nextInt(3), 10 + random.nextInt(20)));
            }
            List<BidEntry> bids = new ArrayList<>();
            long nextBidId = 1;
            for (long user = 1; user <= 8; user++)
            {
                for (ProblemSpec spec : problems)
                {
                    if (random.nextInt(4) != 0)
                    {
                        bids.add(new BidEntry(nextBidId++, user, spec.problemId(), 5 + random.nextInt(100),
                                T0.plusSeconds(random.nextInt(20))));
                    }
                }
            }

            Allocation allocation = AllocationCalculator.resolve(problems, bids, 100);   // nobody can reach this cap

            for (ProblemSpec spec : problems)
            {
                List<VickreyCalculator.BidEntry> eligible = bids.stream()
                        .filter(b -> b.problemId() == spec.problemId() && b.amount() >= spec.reservePrice())
                        .map(b -> new VickreyCalculator.BidEntry(b.bidId(), b.userId(), b.amount(), b.submittedAt()))
                        .toList();
                VickreyCalculator.Outcome expected = VickreyCalculator.resolve(eligible, spec.slots(), spec.reservePrice());

                assertThat(allocation.priceByProblem().get(spec.problemId())).isEqualTo(expected.price());
                assertThat(winnerBidIds(allocation, spec.problemId()))
                        .containsExactlyInAnyOrderElementsOf(
                                expected.winners().stream().map(VickreyCalculator.BidEntry::bidId).toList());
            }
        }
    }

    // ---- misc ----

    @Test
    void theCallersListsAreNotModified()
    {
        List<ProblemSpec> problems = List.of(problem(1, 1, 20));
        List<BidEntry> bids = List.of(bid(1, 1, 1, 10 + 40, 0), bid(2, 2, 1, 90, 1));

        // Would throw UnsupportedOperationException if the calculator sorted the caller's list in place.
        AllocationCalculator.resolve(problems, bids, CAP);

        assertThat(bids).extracting(BidEntry::bidId).containsExactly(1L, 2L);
    }

    @Test
    void theCapMustBeAtLeastOne()
    {
        assertThatThrownBy(() -> AllocationCalculator.resolve(List.of(problem(1, 1, 20)), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers ----

    private static ProblemSpec problem(long id, int slots, long reserve)
    {
        return new ProblemSpec(id, slots, reserve);
    }

    private static BidEntry bid(long bidId, long userId, long problemId, long amount, long secondsAfterStart)
    {
        return new BidEntry(bidId, userId, problemId, amount, T0.plusSeconds(secondsAfterStart));
    }

    /** "userId:problemId" for every winner, so assertions read like the scenario. */
    private static List<String> winnerPairs(Allocation allocation)
    {
        return allocation.winners().stream().map(w -> w.userId() + ":" + w.problemId()).toList();
    }

    private static List<Long> winnerBidIds(Allocation allocation, long problemId)
    {
        return allocation.winners().stream().filter(w -> w.problemId() == problemId).map(BidEntry::bidId).toList();
    }
}
