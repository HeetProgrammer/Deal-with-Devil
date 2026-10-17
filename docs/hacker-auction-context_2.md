# Hacker Auction: Project Context

Read this first. It captures every design decision made so far, so anyone joining the project can get up to speed without re-asking.

## 1. What the event is

A competitive tech event where participants use virtual coins to bid on tech problems (debugging, logic, crypto-lite, puzzles). Problems are solved on **HackerRank**. This app handles everything around the contest: login, coins, sealed bidding, results, hints and the leaderboard.

## 2. Key decisions (final unless marked open)

| Topic | Decision |
|---|---|
| Participants | **Individual**, not teams |
| Auction type | **Vickrey-style sealed-bid (blind)** auction |
| Winners | **One problem can go to many winners** (k slots per problem) |
| Steal / open pool / dynamic pricing | **Dropped** |
| Solving | On **HackerRank**. The app only hosts the preview, links and hint passwords |
| Hints | Three hint versions per problem, one per corruption level. **All hints cost the same.** Which version a participant gets depends on their corruption level |
| Hint purchase | **Manual by admin.** No in-app hint purchase |
| Corruption level | 3 levels per participant. Visible to the participant. Can go **up or down** |
| The devil | Exchanges corruption level for coins: take corruption, get coins. Reversing it (level down) costs coins |
| Admin actions | Pre-built buttons with a confirm popup (see section 5) |
| Pop-ups | After each transaction, the participant sees a pop-up (bid won or lost, hint deducted, credits, level change) |
| Mystery assets | **On hold** for now |
| Coins | Whole-number coins preferred; no floating-point money |
| Backend | **Java + Spring Boot** |
| Auth | **Google sign-in, then our own JWT.** React gets a Google ID token, sends it to the backend, the backend verifies it and issues its own **JWT valid 2 to 3 hours** so participants don't have to log in again. A Spring Security filter validates the JWT (sent in the `Authorization: Bearer` header) on every request. Admin role comes from an email allowlist in the config file |
| Frontend | **React JS** |
| Exact numbers | Prices, rewards, devil exchange rates and hint price are **to be decided later** (kept in a config file) |

## 3. How Vickrey settlement works

1. Each participant places one secret bid per problem, editable until the bidding deadline.
2. Admin closes bidding and runs settlement.
3. For a problem with **k slots**, the top k bidders win.
4. **All winners pay the (k+1)th highest bid**, not their own bid.
5. If there are fewer than k+1 bids, winners pay the problem's base price (reserve).
6. Ties go to the earlier submission.
7. Coins are deducted atomically, the ledger is written, and each participant gets a win or lose pop-up.

Why: bidding your true value is the best strategy, so there are no bidding games.

Example (k = 2): bids 90, 70, 50, 30. The top two win and both pay 50.

Open edge case: if a participant wins several problems and the total exceeds their balance. Preferred fix is to cap total bids at their balance when they submit.

## 4. Round flow

Preview → Sealed bidding → Settlement → Solving (HackerRank) → Results → Next round

All phase changes are controlled by the admin.

## 5. Deliverables

### Backend (Spring Boot)
- Auth endpoint: verify the Google ID token (signature, audience, expiry, verified email), find or create the user, issue our own JWT (2 to 3 hours). The frontend sends it as `Authorization: Bearer <token>` on every request. A JWT filter in the Spring Security chain checks it on every request. Admin role via email allowlist in config
- Wallet and ledger: atomic updates, every change logged (admin, reason, amount, balance after)
- Round state machine
- Sealed-bid engine with a server-side deadline
- Vickrey settlement
- Problem bank: title, category, tier, slots, HackerRank link
- Corruption level per participant (3 levels, up or down)
- 3 hint versions per problem with passwords
- Admin actions API (all atomic and logged):
  - Generic hint deduction (participant + problem number)
  - Preset credits (solved Bronze, Silver, Gold)
  - Devil's deal (coins up, level up)
  - Redemption (coins down, level down)
  - Custom amount, undo via reversing ledger entry
- Result events for participant pop-ups (polling or Server-Sent Events, no WebSockets needed)
- Leaderboard
- Config file for tier prices, rewards, hint price, preset amounts, devil rates
- Safety: balance and level-limit checks, audit log, rate limiting

### Frontend (React)

**Participant app**
- Login and dashboard (coins, round, phase, timer, corruption level)
- Round preview (title, hint teaser, category, tier)
- Sealed bid screen
- Transaction pop-ups
- My problems (HackerRank link) and transaction history
- Leaderboard, notifications, rules page

**Admin panel**
- Round and phase controls (start, advance, pause, next round)
- Auction controls (open/close bidding, run settlement, override)
- Problem bank manager
- Participant manager with corruption level editing
- Preset buttons with confirm popups: hint, credits, devil's deal, redemption, custom, undo
- Ledger and log viewer

### Non-software
- HackerRank contests and problems (Bronze, Silver, Gold)
- 3 hint versions per problem, with passwords
- Prices, rewards, hint price, devil exchange rates
- Event logistics: registration, intro session, number of rounds and problems per round

## 6. Safeguards to build in
- Confirm popup before every admin deduction or credit (prevents wrong-person mis-taps)
- Block deductions that would take a balance below zero
- Undo uses a reversing ledger entry, never deletes the original
- Make redemption cost more than the devil's deal pays, or participants can farm coins by looping
- Keep a fallback admin login in case OAuth fails on the day
- Optional: a per-user token version on the server so a JWT can be revoked instantly (for example a cheater or compromised admin)
- Keep the frontend free of pasted third-party scripts (the JWT lives in the browser, so XSS is the main risk); configure CORS to allow the `Authorization` header from the React origin
- Answers and hint passwords are never sent to a participant before they should see them

## 7. Time estimate

Backend: about 12 to 15 working days for one developer, or 1 to 1.5 weeks for two. Add about 30% if the team is new to Spring Boot.

Build order: auth, wallet, bidding, settlement and admin controls first (usable in 5 to 6 days). Then hints, corruption, devil's deal and pop-ups.

## 8. Open questions
- Exact coin prices, rewards, hint price and devil rates
- Tie-break and reserve-price details (current proposal in section 3)
- How the devil appears: each round, timed, or only on request
- Mystery assets (on hold)
- Number of rounds, problems per round, slots per problem

## 9. Origin
Based on the original "Hacker Auction" event document. The following were removed or changed from it: teams, live bidding, steal, open pool, dynamic pricing, in-app solving and in-app hint purchase.
