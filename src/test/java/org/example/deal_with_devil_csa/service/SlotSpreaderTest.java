package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** The auto slots spread is a pure function, so these tests need no database and no Spring. */
class SlotSpreaderTest
{
    @Test
    void thirtyTwoParticipantsAndTenProblems_isSixWithTenAndFourWithNine()
    {
        int[] slots = SlotSpreader.spread(32, 10, 3);

        assertThat(slots).containsExactly(10, 10, 10, 10, 10, 10, 9, 9, 9, 9);
        assertThat(Arrays.stream(slots).sum()).isEqualTo(96);
    }

    @Test
    void fiftyParticipantsAndSevenProblems_isThreeWith22AndFourWith21()
    {
        int[] slots = SlotSpreader.spread(50, 7, 3);

        assertThat(slots).containsExactly(22, 22, 22, 21, 21, 21, 21);
        assertThat(Arrays.stream(slots).sum()).isEqualTo(150);
    }

    @Test
    void anEvenSplitGivesEveryProblemTheSameNumber()
    {
        assertThat(SlotSpreader.spread(10, 3, 3)).containsExactly(10, 10, 10);   // exactly N each, still allowed
        assertThat(SlotSpreader.spread(20, 6, 3)).containsExactly(10, 10, 10, 10, 10, 10);
    }

    @Test
    void theExtraSlotsGoToTheFirstProblems()
    {
        assertThat(SlotSpreader.spread(4, 5, 3)).containsExactly(3, 3, 2, 2, 2);   // 12 slots over 5 problems
    }

    @Test
    void withAtLeastThreeProblemsTheTotalIsAlwaysExactlyThreeTimesTheParticipants_andNoProblemExceedsN()
    {
        for (int participants = 0; participants <= 80; participants++)
        {
            for (int problems = 3; problems <= 25; problems++)
            {
                int[] slots = SlotSpreader.spread(participants, problems, 3);

                assertThat(Arrays.stream(slots).sum()).as("N=%d P=%d", participants, problems)
                        .isEqualTo(3 * participants);
                assertThat(Arrays.stream(slots).max().orElse(0)).as("N=%d P=%d", participants, problems)
                        .isLessThanOrEqualTo(participants);
                assertThat(Arrays.stream(slots).max().orElse(0) - Arrays.stream(slots).min().orElse(0))
                        .as("evenly spread, N=%d P=%d", participants, problems).isLessThanOrEqualTo(1);
            }
        }
    }

    @Test
    void fewerProblemsThanWinsHitsTheClamp_soNoProblemGetsMoreSlotsThanParticipants()
    {
        // 10 participants, 2 problems: 30 slots would be 15 each, but nobody can win the same problem twice.
        int[] slots = SlotSpreader.spread(10, 2, 3);

        assertThat(slots).containsExactly(10, 10);
    }

    @Test
    void moreProblemsThanSlotsLeavesSomeProblemsWithNone()
    {
        // 1 participant, 10 problems: only 3 slots exist in total.
        int[] slots = SlotSpreader.spread(1, 10, 3);

        assertThat(slots).containsExactly(1, 1, 1, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void invalidArgumentsAreRejected()
    {
        assertThatThrownBy(() -> SlotSpreader.spread(-1, 5, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotSpreader.spread(5, 0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotSpreader.spread(5, 5, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
