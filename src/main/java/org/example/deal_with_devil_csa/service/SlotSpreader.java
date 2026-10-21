package org.example.deal_with_devil_csa.service;

/**
 * The "auto slots" rule as a pure function: how many slots each problem of a round gets.
 *
 * Total = winsPerPerson x participants, spread as evenly as possible over the problems. When the total does not
 * divide evenly, the remainder gives one extra slot to the first problems (in the order given, which is id order),
 * so the result depends only on the counts. No problem gets more slots than there are participants (a person
 * cannot win the same problem twice). With at least winsPerPerson problems that limit never removes anything:
 * total / problems is at most the number of participants, so the slots always add up to exactly the total. The clamp
 * only matters as a safety net when there are fewer problems than winsPerPerson.
 *
 * Example: 32 participants, 10 problems, 3 wins each = 96 slots = six problems with 10 and four with 9.
 */
public final class SlotSpreader
{
    private SlotSpreader()
    {
    }

    /** One entry per problem, in the order of the problems. */
    public static int[] spread(int participants, int problems, int winsPerPerson)
    {
        if (participants < 0 || problems < 1 || winsPerPerson < 1)
        {
            throw new IllegalArgumentException("Needs participants >= 0, problems >= 1 and winsPerPerson >= 1.");
        }

        long total = (long) participants * winsPerPerson;
        long base = total / problems;
        long extra = total % problems;

        int[] slots = new int[problems];
        for (int i = 0; i < problems; i++)
        {
            long wanted = base + (i < extra ? 1 : 0);
            slots[i] = (int) Math.min(wanted, participants);
        }
        return slots;
    }
}
