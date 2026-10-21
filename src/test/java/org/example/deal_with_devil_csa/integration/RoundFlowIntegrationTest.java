package org.example.deal_with_devil_csa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The event day on rails: the same HTTP calls the React apps will make, in the order an organiser would click them,
 * against the real application and PostgreSQL. These replace most of the manual dry-run clicking. What they cannot
 * replace: a real Google sign-in and the real frontend.
 *
 * Uses the same separate test database as CoinSafetyIntegrationTest (see the note there); every test starts empty.
 * Google sign-in is skipped by creating users directly and minting our own JWTs, which is what the app does after
 * it has verified a Google token.
 */
@SpringBootTest(properties = {IntegrationTestSupport.DB_URL_PROPERTY, IntegrationTestSupport.DDL_PROPERTY})
class RoundFlowIntegrationTest extends IntegrationTestSupport
{

    // ================= A whole round, start to results =================

    /**
     * Three participants and four problems (every participant must end with exactly 3). The auto slots button gives
     * 9 slots: 3, 2, 2, 2. Bids (reserve price 50 everywhere), problems A to D:
     * p1 150/140/130/120, p2 100/90/70/65, p3 80/61/55/50.
     * Settlement walks the bids best first with a cap of 3 wins per person: p1 and p2 end with A, B, C (their bids on D
     * are capped out and set no price), p3 gets A and D. B and C are full when p3's bids arrive, so p3's 61 and 55
     * become their prices; A and D never fill up and clear at the reserve price of 50.
     * p3 is left one short (the only unsold slot is on D, which p3 already holds), so auto fill cannot help and the
     * admin hands p3 problem B by hand.
     */
    @Test
    void fullRound_fromStartToResults() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p1 = saveUser(Role.PARTICIPANT, 500);
        User p2 = saveUser(Role.PARTICIPANT, 500);
        User p3 = saveUser(Role.PARTICIPANT, 500);

        // Start round 1. A second "start" while it is running is refused.
        long roundId = numberAt(bodyOf(send(admin, post("/api/admin/rounds"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phase").value("PREVIEW"))), "$.id");
        send(admin, post("/api/admin/rounds"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));

        // Bidding cannot open without problems.
        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));

        // Build the four problems (one slot each for now) and the three hints of the first one.
        long a = createProblem(admin, roundId, "Find the bug", "https://example.com/p1");
        long b = createProblem(admin, roundId, "Second problem", "https://example.com/p2");
        long c = createProblem(admin, roundId, "Third problem", "https://example.com/p3");
        long d = createProblem(admin, roundId, "Fourth problem", "https://example.com/p4");
        for (int level = 1; level <= 3; level++)
        {
            send(admin, put("/api/admin/problems/" + a + "/hints/" + level),
                    "{\"hintText\":\"hint-" + level + "\",\"password\":\"pw-" + level + "\"}")
                    .andExpect(status().isOk());
        }

        // The admin adds the question preview (own endpoint). Participants cannot.
        String questionPreview = "Given an array of numbers, return the index of the first duplicate.";
        send(p1, put("/api/admin/problems/" + a + "/preview"), "{\"preview\":\"hacked\"}")
                .andExpect(status().isForbidden());
        send(admin, put("/api/admin/problems/" + a + "/preview"), "{\"preview\":\"" + questionPreview + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(questionPreview));

        // Participants see the preview text, but never the HackerRank link, hints or passwords.
        String preview = bodyOf(send(p1, get("/api/rounds/current/problems"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Find the bug"))
                .andExpect(jsonPath("$[0].preview").value(questionPreview)));
        assertThat(preview).doesNotContain("example.com").doesNotContain("pw-").doesNotContain("hint-");

        // No bidding in the preview phase.
        send(p1, put("/api/rounds/current/problems/" + a + "/bid"), "{\"amount\":100}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIDDING_CLOSED"));

        // Opening bidding is blocked: 3 participants x 3 problems each = 9 slots are needed, the problems have 4.
        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));

        // The auto slots button. Participants cannot press it; a dry run shows the plan and saves nothing.
        send(p1, post("/api/admin/rounds/current/slots/auto").param("dryRun", "true"), null)
                .andExpect(status().isForbidden());
        send(admin, post("/api/admin/rounds/current/slots/auto").param("dryRun", "true"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.participants").value(3))
                .andExpect(jsonPath("$.problemCount").value(4))
                .andExpect(jsonPath("$.slotsNeeded").value(9))
                .andExpect(jsonPath("$.slotsAssigned").value(9));
        assertThat(slotsOfRound(roundId)).containsExactly(1, 1, 1, 1);

        // The real press: 9 slots over 4 problems, the first problem gets the extra one.
        send(admin, post("/api/admin/rounds/current/slots/auto").param("dryRun", "false"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.slotsAssigned").value(9));
        assertThat(slotsOfRound(roundId)).containsExactly(3, 2, 2, 2);

        // Open bidding. From now on the problem set is frozen, including the slots.
        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("BIDDING"));
        send(admin, delete("/api/admin/problems/" + a), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));
        send(admin, put("/api/admin/problems/" + a + "/preview"), "{\"preview\":\"changed after bidding opened\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));
        send(admin, post("/api/admin/rounds/current/slots/auto").param("dryRun", "false"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));

        // Sealed bids. p1 bids first, so the coverage list (fewest bids first) has p1 last.
        bid(p1, a, 150);
        bid(p1, b, 140);
        bid(p1, c, 130);
        bid(p1, d, 120);
        send(p1, get("/api/admin/rounds/current/bid-coverage"), null).andExpect(status().isForbidden());
        send(admin, get("/api/admin/rounds/current/bid-coverage"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problemCount").value(4))
                .andExpect(jsonPath("$.winsPerRound").value(3))
                .andExpect(jsonPath("$.participants.length()").value(3))
                .andExpect(jsonPath("$.participants[0].bidCount").value(0))
                .andExpect(jsonPath("$.participants[0].missingBids").value(4))
                .andExpect(jsonPath("$.participants[2].userId").value(p1.getId().intValue()))
                .andExpect(jsonPath("$.participants[2].missingBids").value(0));

        bid(p2, a, 100);
        bid(p2, b, 90);
        bid(p2, c, 70);
        bid(p2, d, 65);
        bid(p3, a, 80);
        bid(p3, b, 61);
        bid(p3, c, 55);
        bid(p3, d, 50);
        send(admin, get("/api/admin/rounds/current/bid-coverage"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[0].missingBids").value(0));

        // The allocation view only exists after settlement.
        send(admin, get("/api/admin/rounds/current/allocation"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));

        // Close bidding. Advancing out of settlement is not allowed (settle instead), and bids are now refused.
        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SETTLEMENT"));
        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));
        send(p3, put("/api/rounds/current/problems/" + a + "/bid"), "{\"amount\":120}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIDDING_CLOSED"));

        // Settle. B and C clear at the bids that were turned away at a full problem (61 and 55); A and D at the reserve.
        send(admin, post("/api/admin/rounds/current/settle"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCollected").value(432))
                .andExpect(jsonPath("$.problems[0].bidCount").value(3))
                .andExpect(jsonPath("$.problems[0].winnerCount").value(3))
                .andExpect(jsonPath("$.problems[0].clearingPrice").value(50))
                .andExpect(jsonPath("$.problems[1].winnerCount").value(2))
                .andExpect(jsonPath("$.problems[1].clearingPrice").value(61))
                .andExpect(jsonPath("$.problems[2].winnerCount").value(2))
                .andExpect(jsonPath("$.problems[2].clearingPrice").value(55))
                .andExpect(jsonPath("$.problems[3].winnerCount").value(1))
                .andExpect(jsonPath("$.problems[3].clearingPrice").value(50));
        send(admin, post("/api/admin/rounds/current/settle"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SETTLED"));

        // Coins moved. p1 and p2 each paid 50 + 61 + 55, p3 paid 50 + 50.
        assertThat(balanceOf(p1)).isEqualTo(334);
        assertThat(balanceOf(p2)).isEqualTo(334);
        assertThat(balanceOf(p3)).isEqualTo(400);

        // Results as each participant sees them. Winners get the link, the others do not.
        send(p1, get("/api/me/bids"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].status").value("WON"))
                .andExpect(jsonPath("$[0].pricePaid").value(50))
                .andExpect(jsonPath("$[0].hackerRankLink").value("https://example.com/p1"))
                .andExpect(jsonPath("$[3].status").value("LOST"));   // capped out on problem D despite bidding 120
        String p2Bids = bodyOf(send(p2, get("/api/me/bids"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].pricePaid").value(61))
                .andExpect(jsonPath("$[3].status").value("LOST")));
        assertThat(p2Bids).doesNotContain("https://example.com/p4");
        send(p3, get("/api/me/bids"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].status").value("LOST"))
                .andExpect(jsonPath("$[3].status").value("WON"))
                .andExpect(jsonPath("$[3].pricePaid").value(50));

        // Pop-ups: everybody got their own events.
        assertThat(bodyOf(send(p1, get("/api/me/events"), null).andExpect(status().isOk()))).contains("BID_WON");
        assertThat(bodyOf(send(p2, get("/api/me/events"), null).andExpect(status().isOk()))).contains("BID_LOST");

        // Admin views of the result, and the winner lists.
        send(admin, get("/api/admin/problems/" + a + "/winners"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        send(admin, get("/api/admin/problems/" + d + "/winners"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(p3.getId().intValue()))
                .andExpect(jsonPath("$[0].pricePaid").value(50));
        send(admin, get("/api/admin/ledger?userId=" + p1.getId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("BID_SETTLEMENT"))
                .andExpect(jsonPath("$[0].amount").value(-55))
                .andExpect(jsonPath("$[0].balanceAfter").value(334));

        // The allocation view flags p3 (two problems instead of three) and shows the one unsold slot, on problem D.
        send(p1, get("/api/admin/rounds/current/allocation"), null).andExpect(status().isForbidden());
        send(admin, get("/api/admin/rounds/current/allocation"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winsPerRound").value(3))
                .andExpect(jsonPath("$.participantCount").value(3))
                .andExpect(jsonPath("$.atTargetCount").value(2))
                .andExpect(jsonPath("$.participants[0].userId").value(p3.getId().intValue()))
                .andExpect(jsonPath("$.participants[0].wins").value(2))
                .andExpect(jsonPath("$.participants[0].missing").value(1))
                .andExpect(jsonPath("$.participants[0].atTarget").value(false))
                .andExpect(jsonPath("$.problems[0].sold").value(3))
                .andExpect(jsonPath("$.problems[0].unsold").value(0))
                .andExpect(jsonPath("$.problems[3].unsold").value(1));

        // Auto fill cannot help p3: the only unsold slot is on a problem p3 already holds. Nothing is given out.
        send(admin, post("/api/admin/rounds/current/auto-fill").param("dryRun", "true"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.assignments.length()").value(0))
                .andExpect(jsonPath("$.stillShort.length()").value(1))
                .andExpect(jsonPath("$.stillShort[0].userId").value(p3.getId().intValue()))
                .andExpect(jsonPath("$.stillShort[0].missing").value(1));

        // Manual assignment: problem B (full, so it goes over capacity on purpose) at its reserve price of 50.
        send(admin, post("/api/admin/rounds/current/assignments"),
                "{\"userId\":" + p3.getId() + ",\"problemId\":" + b + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePaid").value(50))
                .andExpect(jsonPath("$.balanceAfter").value(350))
                .andExpect(jsonPath("$.winsNow").value(3))
                .andExpect(jsonPath("$.overCapacity").value(true));
        assertThat(balanceOf(p3)).isEqualTo(350);
        send(admin, post("/api/admin/rounds/current/assignments"),
                "{\"userId\":" + p3.getId() + ",\"problemId\":" + b + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        send(admin, get("/api/admin/rounds/current/allocation"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atTargetCount").value(3));
        send(p3, get("/api/me/bids"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].status").value("WON"))
                .andExpect(jsonPath("$[1].pricePaid").value(50))
                .andExpect(jsonPath("$[1].hackerRankLink").value("https://example.com/p2"));

        // Leaderboard: p3 leads with 350, p1 and p2 tie on 334 (rank 2).
        send(p1, get("/api/leaderboard"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].coins").value(350))
                .andExpect(jsonPath("$[1].rank").value(2))
                .andExpect(jsonPath("$[2].rank").value(2));

        // Finish the round and start the next one.
        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("RESULTS"));
        send(admin, post("/api/admin/rounds"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(2));

        // Every state-changing admin call above was audited.
        send(admin, get("/api/admin/audit"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actorEmail").value(admin.getEmail()));
    }

    // ================= The bidding rules, one by one =================

    @Test
    void biddingRules_reserveBalancePauseAndEditing() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p = saveUser(Role.PARTICIPANT, 100);
        Round round = saveRound(RoundPhase.BIDDING);
        Problem a = saveProblem(round, "A");
        Problem b = saveProblem(round, "B");

        // Below the reserve price of 50.
        send(p, put("/api/rounds/current/problems/" + a.getId() + "/bid"), "{\"amount\":40}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.amount").exists());

        // A valid bid, then a second one that would take the total above the balance (60 + 60 > 100).
        bid(p, a.getId(), 60);
        send(p, put("/api/rounds/current/problems/" + b.getId() + "/bid"), "{\"amount\":60}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BID_EXCEEDS_BALANCE"));

        // Editing replaces the old amount instead of adding to it: lower the first bid, and 50 + 50 fits in 100.
        bid(p, a.getId(), 50);
        bid(p, b.getId(), 50);
        send(p, get("/api/me/bids"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // A bid on a problem that does not exist in this round.
        send(p, put("/api/rounds/current/problems/999999/bid"), "{\"amount\":60}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROBLEM_NOT_FOUND"));

        // Paused rounds refuse bids; resuming brings bidding back.
        send(admin, post("/api/admin/rounds/current/pause"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));
        send(p, put("/api/rounds/current/problems/" + a.getId() + "/bid"), "{\"amount\":50}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIDDING_CLOSED"));
        send(admin, post("/api/admin/rounds/current/resume"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));
        bid(p, a.getId(), 50);
    }

    // ================= Devil's deal, redemption and undo =================

    @Test
    void devilDealRedemptionAndUndo_keepCoinsAndLevelInStep() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p = saveUser(Role.PARTICIPANT, 1_000);

        String config = bodyOf(send(p, get("/api/config"), null).andExpect(status().isOk()));
        long payout = numberAt(config, "$.devil.dealPayout");
        long cost = numberAt(config, "$.devil.redemptionCost");
        assertThat(cost).isGreaterThan(payout);   // otherwise looping would farm coins
        assertThat(numberAt(config, "$.winsPerRound")).isEqualTo(3);

        // Two deals take the participant from level 1 to 3; a third is refused.
        devilDeal(admin, p).andExpect(jsonPath("$.corruptionLevel").value(2));
        devilDeal(admin, p).andExpect(jsonPath("$.corruptionLevel").value(3));
        devilDeal(admin, p)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRUPTION_LIMIT_REACHED"));
        assertThat(balanceOf(p)).isEqualTo(1_000 + 2 * payout);

        // Redemption: one level down, and it costs coins.
        long redemptionEntry = numberAt(bodyOf(redeem(admin, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corruptionLevel").value(2))), "$.entry.id");
        assertThat(balanceOf(p)).isEqualTo(1_000 + 2 * payout - cost);

        // Undoing the redemption gives the coins back and puts the level back to 3. Only once.
        send(admin, post("/api/admin/wallet/undo/" + redemptionEntry), "{\"reason\":\"clicked the wrong button\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REVERSAL"));
        send(p, get("/api/me"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corruptionLevel").value(3))
                .andExpect(jsonPath("$.coins").value(1_000 + 2 * payout));
        send(admin, post("/api/admin/wallet/undo/" + redemptionEntry), "{\"reason\":\"again\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // Down to level 1 and no further.
        redeem(admin, p).andExpect(status().isOk()).andExpect(jsonPath("$.corruptionLevel").value(2));
        redeem(admin, p).andExpect(status().isOk()).andExpect(jsonPath("$.corruptionLevel").value(1));
        redeem(admin, p)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRUPTION_LIMIT_REACHED"));

        // The participant was told about all of it.
        assertThat(bodyOf(send(p, get("/api/me/events"), null).andExpect(status().isOk())))
                .contains("DEVIL_DEAL").contains("REDEMPTION").contains("REVERSAL");
    }

    // ================= Hints follow the corruption level =================

    @Test
    void hintLookup_followsCorruptionLevel_andHintPurchaseCostsCoins() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p = saveUser(Role.PARTICIPANT, 100);
        User broke = saveUser(Role.PARTICIPANT, 0);
        Problem problem = saveProblem(saveRound(RoundPhase.SOLVING), "Hinted");

        // Only levels 1 and 2 have a hint written.
        for (int level = 1; level <= 2; level++)
        {
            send(admin, put("/api/admin/problems/" + problem.getId() + "/hints/" + level),
                    "{\"hintText\":\"hint-" + level + "\",\"password\":\"pw-" + level + "\"}")
                    .andExpect(status().isOk());
        }
        send(admin, put("/api/admin/problems/" + problem.getId() + "/hints/4"),
                "{\"hintText\":\"x\",\"password\":\"y\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Level 1 gets the level 1 hint.
        send(admin, get("/api/admin/users/" + p.getId() + "/hints/" + problem.getId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hintText").value("hint-1"))
                .andExpect(jsonPath("$.password").value("pw-1"));

        // Raise the level by hand: the hint changes with it.
        send(admin, put("/api/admin/users/" + p.getId() + "/corruption-level"), "{\"level\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corruptionLevel").value(2));
        send(admin, get("/api/admin/users/" + p.getId() + "/hints/" + problem.getId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hintText").value("hint-2"));

        // Level 3 has no hint written yet.
        send(admin, put("/api/admin/users/" + p.getId() + "/corruption-level"), "{\"level\":3}").andExpect(status().isOk());
        send(admin, get("/api/admin/users/" + p.getId() + "/hints/" + problem.getId()), null)
                .andExpect(status().isNotFound());
        send(admin, put("/api/admin/users/" + p.getId() + "/corruption-level"), "{\"level\":4}")
                .andExpect(status().isBadRequest());

        // Buying the hint: costs the configured price, and is refused without enough coins.
        long hintPrice = numberAt(bodyOf(send(p, get("/api/config"), null).andExpect(status().isOk())), "$.hintPrice");
        send(admin, post("/api/admin/wallet/hint-deduction"),
                "{\"userId\":" + p.getId() + ",\"problemNumber\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("HINT_DEDUCTION"))
                .andExpect(jsonPath("$.amount").value(-hintPrice));
        assertThat(balanceOf(p)).isEqualTo(100 - hintPrice);

        send(admin, post("/api/admin/wallet/hint-deduction"),
                "{\"userId\":" + broke.getId() + ",\"problemNumber\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        assertThat(balanceOf(broke)).isEqualTo(0);
    }

    // ================= The mandatory profile form =================

    @Test
    void profileGate_blocksEverythingUntilTheFormIsSaved() throws Exception
    {
        User taken = saveUser(Role.PARTICIPANT, 0);
        User newcomer = saveIncompleteUser();

        // Allowed before the form: who am I, and the game config.
        send(newcomer, get("/api/me"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileComplete").value(false));
        send(newcomer, get("/api/config"), null).andExpect(status().isOk());

        // Everything else is blocked.
        send(newcomer, get("/api/rounds/current"), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"));
        send(newcomer, get("/api/leaderboard"), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"));

        // The form validates, and a HackerRank ID already taken (any letter case) is refused.
        send(newcomer, put("/api/me/profile"), "{\"name\":\"\",\"hackerRankId\":\"ok_id\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.name").exists());
        send(newcomer, put("/api/me/profile"),
                "{\"name\":\"New Person\",\"hackerRankId\":\"" + taken.getHackerRankId().toUpperCase() + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details.hackerRankId").exists());

        // A valid form unlocks the app (the 404 now only means that no round has started).
        send(newcomer, put("/api/me/profile"), "{\"name\":\"New Person\",\"hackerRankId\":\"new_person_hr\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileComplete").value(true));
        send(newcomer, get("/api/rounds/current"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUND_NOT_FOUND"));
    }

    // ================= Input validation on the admin forms =================

    @Test
    void problemForm_rejectsBadInput_withPerFieldDetails() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);

        send(admin, post("/api/admin/problems"),
                "{\"title\":\"\",\"category\":\"Logic\",\"tier\":\"BRONZE\",\"slots\":0,\"hackerRankLink\":\"not-a-link\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.title").exists())
                .andExpect(jsonPath("$.details.slots").exists())
                .andExpect(jsonPath("$.details.hackerRankLink").exists());

        send(admin, post("/api/admin/problems"), "this is not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ================= Finding a participant's id =================

    @Test
    void participantSearch_findsByNameOrHackerRankId_ignoringLetterCase() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User asha = saveUser(Role.PARTICIPANT, 100, "Asha Rao");
        User ravi = saveUser(Role.PARTICIPANT, 100, "Ravi Kumar");
        asha.setHackerRankId("ace_coder");
        userRepository.save(asha);
        ravi.setHackerRankId("rk_2026");
        userRepository.save(ravi);

        // Part of a name, in any letter case.
        send(admin, get("/api/admin/users").param("q", "ASHA"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(asha.getId().intValue()))
                .andExpect(jsonPath("$[0].hackerRankId").value("ace_coder"));

        // Part of a HackerRank ID, in any letter case, with spaces around it.
        send(admin, get("/api/admin/users").param("q", "  RK_20 "), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ravi.getId().intValue()));

        // No match gives an empty list; a blank search or no search gives every participant (never the admin).
        send(admin, get("/api/admin/users").param("q", "zzz"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        send(admin, get("/api/admin/users").param("q", "   "), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        send(admin, get("/api/admin/users"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Admin only.
        send(asha, get("/api/admin/users").param("q", "ravi"), null)
                .andExpect(status().isForbidden());
    }

    // ================= Helpers =================

    private ResultActions devilDeal(User admin, User participant) throws Exception
    {
        return send(admin, post("/api/admin/wallet/devil-deal"), "{\"userId\":" + participant.getId() + "}");
    }

    private ResultActions redeem(User admin, User participant) throws Exception
    {
        return send(admin, post("/api/admin/wallet/redemption"), "{\"userId\":" + participant.getId() + "}");
    }

    private void bid(User participant, long problemId, long amount) throws Exception
    {
        send(participant, put("/api/rounds/current/problems/" + problemId + "/bid"), "{\"amount\":" + amount + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /** Creates a Bronze problem in the round through the admin API (one slot, reserve price 50) and returns its id. */
    private long createProblem(User admin, long roundId, String title, String link) throws Exception
    {
        String json = "{\"title\":\"" + title + "\",\"category\":\"Debugging\",\"tier\":\"BRONZE\",\"slots\":1,"
                + "\"hackerRankLink\":\"" + link + "\",\"basePrice\":50,\"teaser\":\"A tiny off-by-one\","
                + "\"roundId\":" + roundId + "}";
        return numberAt(bodyOf(send(admin, post("/api/admin/problems"), json).andExpect(status().isCreated())), "$.id");
    }

    /** The slots of the round's problems, in id order. */
    private List<Integer> slotsOfRound(long roundId)
    {
        return problemRepository.findByRoundIdOrderByIdAsc(roundId).stream().map(Problem::getSlots).toList();
    }

}
