package org.example.deal_with_devil_csa.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settlement rule for a whole round (Phase 9), as a pure function with no database or Spring, so it can be
 * tested exhaustively. It replaces the per-problem VickreyCalculator in settlement; VickreyCalculator stays as the
 * reference the tests compare against.
 *
 * Take every eligible bid of the round (amount at or above the problem's reserve price) and rank them: higher amount
 * first; equal amounts go to the earlier submission; if even the time is equal, the lower bid id (so the result is
 * always deterministic). Walk the list and accept a bid only if
 * <ul>
 *   <li>the person has fewer than maxWinsPerPerson wins so far, and</li>
 *   <li>the problem still has a free slot.</li>
 * </ul>
 * A bid from a person who is already at the cap is skipped on its own: it never takes a slot and never sets a price.
 * The price of a problem is the amount of the first bid that was rejected because the problem was full, else the
 * reserve price. Every winner of a problem pays that same price.
 *
 * When nobody would win more than maxWinsPerPerson problems this gives exactly the same result as running
 * VickreyCalculator on each problem separately.
 */
public final class AllocationCalculator
{
    private AllocationCalculator()
    {
    }

    /** What the rule needs to know about one bid. */
    public record BidEntry(long bidId, long userId, long problemId, long amount, Instant submittedAt)
    {
    }

    /** What the rule needs to know about one problem of the round. */
    public record ProblemSpec(long problemId, int slots, long reservePrice)
    {
    }

    /**
     * winners are in the order they were accepted (best bid first). priceByProblem has an entry for every problem
     * that was passed in, including problems without winners (their price is the reserve price).
     */
    public record Allocation(List<BidEntry> winners, Map<Long, Long> priceByProblem)
    {
    }

    private static final Comparator<BidEntry> RANKING = Comparator
            .comparingLong(BidEntry::amount).reversed()
            .thenComparing(BidEntry::submittedAt)
            .thenComparingLong(BidEntry::bidId);

    public static Allocation resolve(List<ProblemSpec> problems, List<BidEntry> bids, int maxWinsPerPerson)
    {
        if (maxWinsPerPerson < 1)
        {
            throw new IllegalArgumentException("A person must be allowed at least one win.");
        }

        Map<Long, ProblemSpec> specs = new LinkedHashMap<>();
        for (ProblemSpec spec : problems)
        {
            if (spec.slots() < 0)
            {
                throw new IllegalArgumentException("A problem cannot have a negative number of slots.");
            }
            specs.put(spec.problemId(), spec);
        }

        // Only eligible bids take part: on a problem of this round and at or above its reserve price.
        List<BidEntry> ranked = new ArrayList<>();
        for (BidEntry bid : bids)
        {
            ProblemSpec spec = specs.get(bid.problemId());
            if (spec != null && bid.amount() >= spec.reservePrice())
            {
                ranked.add(bid);
            }
        }
        ranked.sort(RANKING);

        Map<Long, Integer> winsByPerson = new HashMap<>();
        Map<Long, Integer> takenSlots = new HashMap<>();
        Map<Long, Long> priceSetByFullProblem = new HashMap<>();
        List<BidEntry> winners = new ArrayList<>();

        for (BidEntry bid : ranked)
        {
            if (winsByPerson.getOrDefault(bid.userId(), 0) >= maxWinsPerPerson)
            {
                continue;   // capped: this bid is out, and it must not set anybody's price
            }

            ProblemSpec spec = specs.get(bid.problemId());
            if (takenSlots.getOrDefault(bid.problemId(), 0) >= spec.slots())
            {
                // The problem is full: the first (highest) bid turned away for that reason sets the price.
                priceSetByFullProblem.putIfAbsent(bid.problemId(), bid.amount());
                continue;
            }

            winners.add(bid);
            winsByPerson.merge(bid.userId(), 1, Integer::sum);
            takenSlots.merge(bid.problemId(), 1, Integer::sum);
        }

        Map<Long, Long> prices = new LinkedHashMap<>();
        for (ProblemSpec spec : specs.values())
        {
            long price = priceSetByFullProblem.getOrDefault(spec.problemId(), spec.reservePrice());
            prices.put(spec.problemId(), Math.max(price, spec.reservePrice()));
        }
        return new Allocation(List.copyOf(winners), Collections.unmodifiableMap(prices));
    }
}
