# Deal with Devil: Backend Roadmap

Spring Boot 4.1.1 (Java 21), PostgreSQL in Docker (port 5433), React frontend.
Event: individual participants bid coins in a sealed Vickrey auction on HackerRank problems. Corruption levels change which hint version a participant gets, and the devil trades corruption for coins.

Tick the boxes as work is finished. Target for the whole backend: about 12 to 15 working days.

---

## Phase 0: Setup (done)
- [x] Spring Boot project created (Web, Security, JPA, Validation, PostgreSQL, Lombok)
- [x] Maven and JDK 21 working in IntelliJ
- [x] PostgreSQL running in Docker on port 5433
- [x] `application.properties` with datasource and JPA settings
- [x] Time zone fix in `main` (Asia/Kolkata)
- [x] `.gitignore` updated, repo on GitHub

## Phase 1: Foundation (about 1 day)
- [x] Package structure: `config`, `domain`, `repository`, `web` (`controller`, `dto`, `handler`), `exception` created; `service` and `security` get created with their first class in Phases 2 and 3
- [x] Global exception handler and a standard error response (`ErrorResponse` in `web/dto`, `GlobalExceptionHandler` in `web/handler`; compiles)
- [x] Entities: `User` (includes `googleId`, `hackerRankId`), `Wallet`, `LedgerEntry` (coins are `long`; wallet has `@Version`; ledger is append-only; plus `Role` and `LedgerType` enums and the three repositories)
- [x] Config class for prices, rewards, preset amounts, devil rates (`GameProperties` bound from `game.*` in `application.properties`; values are placeholders until decided)
- [x] Health check endpoint (`/api/health`), plus a temporary `SecurityConfig` that permits it (replaced in Phase 2)

## Phase 2: Auth (about 1 day)
- [x] Add dependencies: `google-api-client` and a JWT library
- [x] `POST /api/auth/google`: verify the Google ID token (signature, audience, expiry, verified email)
- [x] `POST /api/auth/google` also finds or creates the user (email + Google id) and issues our own JWT (2 to 3 hours). The response includes `profileComplete`
- [x] Mandatory first-login profile form: `PUT /api/me/profile` takes name and HackerRank ID (trimmed, not blank, HackerRank ID unique, duplicate gives `CONFLICT` with the field in `details`). The same endpoint is used later if a participant needs to correct their HackerRank ID. Until `profileComplete` is true, every endpoint except `/api/me` and `/api/me/profile` returns `PROFILE_INCOMPLETE` (403); the frontend shows the form and lets them proceed once it is saved
- [x] Create the participant's wallet when the user is created
- [ ] Admin panel shows each participant's HackerRank ID
- [x] JWT filter in the Spring Security chain (`Authorization: Bearer`)
- [x] `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403) that return the same `ErrorResponse` JSON, because errors in the filter chain never reach `GlobalExceptionHandler`
- [x] Roles: PARTICIPANT and ADMIN (admin emails from an allowlist in config)
- [ ] Fallback admin login for the event day
- [x] CORS for the React origin

## Phase 3: Wallet and ledger (about 2 days)
- [x] Integer coin balances; every change writes a `LedgerEntry` (admin, reason, amount, balance after) (`WalletService.applyTo` is the single chokepoint; reason required, zero amounts rejected; not yet compiled or tested)
- [x] Atomic credit and deduct with `@Transactional` and a balance check (`WalletService.credit` / `deduct`; wallet row is locked with `findByUserIdForUpdate`, balance below zero gives `INSUFFICIENT_BALANCE`)
- [ ] Admin actions: preset credits (solved Bronze, Silver, Gold), generic hint deduction (participant and problem number), custom amount
- [x] Undo by adding a reversing ledger entry (`WalletService.reverse`: an entry can be undone once, a reversal cannot be undone, `reversalOf` is unique in the database; the admin endpoint for it is still to do)
- [ ] `GET /api/me` (coins, corruption level) and `GET /api/me/history` (`GET /api/me` already exists from Phase 2; `/api/me/history` still to do)
- [ ] Tests for the wallet logic

## Phase 4: Problems and round flow (about 2 days)
- [ ] `Problem` entity: title, category, tier, slots, HackerRank link, base price
- [ ] Hint versions: 3 per problem, one per corruption level, with passwords (admin only)
- [ ] `Round` entity and phase state machine: Preview, Bidding, Settlement, Solving, Results
- [ ] Admin endpoints: start round, advance phase, pause, next round
- [ ] Participant endpoints: current round and phase, problem preview (title, category, tier, teaser)

## Phase 5: Sealed bids and Vickrey settlement (about 2 to 3 days)
- [ ] `Bid` entity: one bid per participant per problem, editable until the deadline
- [ ] Server-side bidding deadline; reject late bids
- [ ] Reject a bid above the participant's balance, and cap total bids at balance
- [ ] Settlement: top k bidders win, all pay the (k+1)th highest bid, reserve price if too few bids, earlier bid wins ties
- [ ] Settlement deducts coins and writes ledger entries in one transaction
- [ ] Results endpoint: won or lost, price paid
- [ ] Thorough tests for settlement (ties, too few bids, insufficient balance)

## Phase 6: Corruption and the devil (about 1 to 2 days)
- [ ] Corruption level on the user (3 levels, up or down)
- [ ] Devil's deal: coins up, level up (atomic, logged)
- [ ] Redemption: coins down, level down (cost higher than the deal pays)
- [ ] Block deals beyond the level limits
- [ ] Hint lookup by problem and the participant's current level (admin sees which version and password to give)

## Phase 7: Feedback and leaderboard (about 1 to 2 days)
- [ ] Event feed for pop-ups (bid won or lost, hint deducted, credits, level change), delivered by polling or Server-Sent Events
- [ ] Leaderboard endpoint (by coins, or by your scoring rule)
- [ ] Notifications for phase changes

## Phase 8: Hardening and launch (about 2 to 3 days)
- [ ] Audit log for every admin action
- [ ] Input validation on every request body
- [ ] Optional rate limiting
- [ ] Optional token version for instant JWT revocation
- [ ] Integration tests for the main flows
- [ ] Move secrets to environment variables (Google client ID, JWT secret and admin emails done locally; database credentials still use the defaults)
- [ ] Deployment plan (server, database, HTTPS) and a dry-run with test accounts

## Frontend (React), in parallel
- [ ] Google sign-in screen, token stored and sent as a Bearer header
- [ ] Participant app: dashboard, round preview, sealed bid screen, results pop-ups, my problems, leaderboard, rules
- [ ] Admin panel: round controls, problem manager, participant manager, preset buttons with confirm popups, ledger viewer

---

## Open decisions
- [ ] Coin prices, tier rewards, hint price, preset credit amounts
- [ ] Devil rates (coins per level; redemption must cost more than the deal pays)
- [ ] Reserve price rule and tie-break details
- [ ] Slots per problem, number of rounds, problems per round
- [ ] How the devil appears (every round, timed, or on request)
- [ ] How long the event runs (decides whether the JWT needs a refresh)
- [ ] Mystery assets (on hold)
- [x] HackerRank ID: participants can edit it themselves (decided)
- [ ] Do admins need a HackerRank ID too? (for now the profile gate only applies to participants)
- [x] Real admin emails (15 tech team members), the real Google OAuth client ID and a real JWT secret: set as the environment variables `ADMIN_EMAILS`, `GOOGLE_CLIENT_ID` and `JWT_SECRET` (kept out of the repo)
- [ ] Google Cloud: set the OAuth consent screen (Audience) to In production so non-test users can sign in; add the real React origin to Authorized JavaScript origins once deployed
- [ ] Phase 2: test login end to end with a real Google token (OAuth client and environment variables are now set up, but no real login has been tried yet)

## Suggested order
Phases 1 to 3 give a working login and wallet, then phases 4 and 5 make the core auction usable. Phases 6 and 7 add the extras. Phase 8 comes before the event, not after.
