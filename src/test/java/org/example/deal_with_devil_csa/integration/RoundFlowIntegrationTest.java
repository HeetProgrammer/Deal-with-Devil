package org.example.deal_with_devil_csa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

        // Build the problem and its three hints. One slot, reserve price 50.
        String problemJson = "{\"title\":\"Find the bug\",\"category\":\"Debugging\",\"tier\":\"BRONZE\",\"slots\":1,"
                + "\"hackerRankLink\":\"https://example.com/p1\",\"basePrice\":50,\"teaser\":\"A tiny off-by-one\","
                + "\"roundId\":" + roundId + "}";
        long problemId = numberAt(bodyOf(send(admin, post("/api/admin/problems"), problemJson)
                .andExpect(status().isCreated())), "$.id");
        for (int level = 1; level <= 3; level++)
        {
            send(admin, put("/api/admin/problems/" + problemId + "/hints/" + level),
                    "{\"hintText\":\"hint-" + level + "\",\"password\":\"pw-" + level + "\"}")
                    .andExpect(status().isOk());
        }

        // Participants see the preview, but never the HackerRank link, hints or passwords.
        String preview = bodyOf(send(p1, get("/api/rounds/current/problems"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Find the bug")));
        assertThat(preview).doesNotContain("example.com").doesNotContain("pw-").doesNotContain("hint-");

        // No bidding in the preview phase.
        send(p1, put("/api/rounds/current/problems/" + problemId + "/bid"), "{\"amount\":100}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIDDING_CLOSED"));

        // Open bidding. From now on the problem is frozen.
        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("BIDDING"));
        send(admin, delete("/api/admin/problems/" + problemId), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));

        // Three sealed bids. Top bidder (p1, 150) wins and pays the second highest bid (100).
        bid(p1, problemId, 150);
        bid(p2, problemId, 100);
        bid(p3, problemId, 80);

        // Close bidding. Advancing out of settlement is not allowed (settle instead), and bids are now refused.
        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SETTLEMENT"));
        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));
        send(p3, put("/api/rounds/current/problems/" + problemId + "/bid"), "{\"amount\":120}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIDDING_CLOSED"));

        // Settle.
        send(admin, post("/api/admin/rounds/current/settle"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCollected").value(100))
                .andExpect(jsonPath("$.problems[0].bidCount").value(3))
                .andExpect(jsonPath("$.problems[0].winnerCount").value(1))
                .andExpect(jsonPath("$.problems[0].clearingPrice").value(100));
        send(admin, post("/api/admin/rounds/current/settle"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SETTLED"));

        // Coins moved: only the winner paid, and only the clearing price.
        assertThat(balanceOf(p1)).isEqualTo(400);
        assertThat(balanceOf(p2)).isEqualTo(500);
        assertThat(balanceOf(p3)).isEqualTo(500);

        // Results as each participant sees them. The winner gets the link, the others do not.
        send(p1, get("/api/me/bids"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("WON"))
                .andExpect(jsonPath("$[0].pricePaid").value(100))
                .andExpect(jsonPath("$[0].hackerRankLink").value("https://example.com/p1"));
        String loserBids = bodyOf(send(p2, get("/api/me/bids"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("LOST")));
        assertThat(loserBids).doesNotContain("example.com");

        // Pop-ups: the winner and the loser each got their own event.
        assertThat(bodyOf(send(p1, get("/api/me/events"), null).andExpect(status().isOk()))).contains("BID_WON");
        assertThat(bodyOf(send(p2, get("/api/me/events"), null).andExpect(status().isOk()))).contains("BID_LOST");

        // Admin views of the result, and the winner list.
        send(admin, get("/api/admin/problems/" + problemId + "/winners"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(p1.getId().intValue()))
                .andExpect(jsonPath("$[0].pricePaid").value(100));
        send(admin, get("/api/admin/ledger?userId=" + p1.getId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("BID_SETTLEMENT"))
                .andExpect(jsonPath("$[0].amount").value(-100))
                .andExpect(jsonPath("$[0].balanceAfter").value(400));

        // Leaderboard: p2 and p3 tie on 500 (rank 1), p1 follows with 400 (rank 3).
        send(p1, get("/api/leaderboard"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[1].rank").value(1))
                .andExpect(jsonPath("$[2].rank").value(3))
                .andExpect(jsonPath("$[2].coins").value(400));

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

}
