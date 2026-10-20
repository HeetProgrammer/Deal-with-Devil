# Deal with Devil: Backend Roadmap

Spring Boot 4.1.1 (Java 21), PostgreSQL in Docker (port 5433), React frontend.
Event: individual participants bid coins in a sealed Vickrey auction on HackerRank problems. Corruption levels change which hint version a participant gets, and the devil trades corruption for coins.

Tick the boxes as work is finished. Target for the whole backend: about 12 to 15 working days.

> Status 2026-10-20: the whole backend source was read against these docs and they match it. The full test suite (125 tests) passes, including `DealWithDevilCsaApplicationTests.contextLoads` against the Docker PostgreSQL 16, and the app starts normally. Still never exercised: a real Google sign-in, a dry-run with test accounts, and database behaviour in tests (row locks, unique constraints). The endpoint list and all request/response shapes are in `frontend-api-contract.md`.
>
> Update 2026-10-20 (later): ownership and role checks are now on every service method (see Phase 8), and `AccessGuardTest` (12 unit tests) was added. `CoinSafetyIntegrationTest` passes against the real PostgreSQL. A full `mvnw test` run after these changes has not been confirmed yet, so treat the 125-test figure above as the earlier count.

---

## Phase 0: Setup (done)
- [x] Spring Boot project created (Web, Security, JPA, Validation, PostgreSQL, Lombok)
- [x] Maven and JDK 21 working in IntelliJ
- [x] PostgreSQL running in Docker on port 5433
- [x] `application.properties` with datasource and JPA settings
- [x] Time zone fix in `main` (Asia/Kolkata). Tests skip `main`, so `DealWithDevilCsaApplicationTests` sets the same zone in a static block
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
- [x] Admin panel shows each participant's HackerRank ID (backend side: `GET /api/admin/users` returns it)
- [x] JWT filter in the Spring Security chain (`Authorization: Bearer`)
- [x] `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403) that return the same `ErrorResponse` JSON, because errors in the filter chain never reach `GlobalExceptionHandler`
- [x] Roles: PARTICIPANT and ADMIN (admin emails from an allowlist in config)
- [x] Fallback admin login for the event day (`POST /api/auth/admin-fallback`: allowlisted email plus a shared passcode from `ADMIN_FALLBACK_PASSCODE`; off while the passcode is blank; 5 failures per email in 5 minutes locks it out)
- [x] CORS for the React origin

## Phase 3: Wallet and ledger (about 2 days)
- [x] Integer coin balances; every change writes a `LedgerEntry` (admin, reason, amount, balance after) (`WalletService.applyTo` is the single chokepoint; reason required, zero amounts rejected)
- [x] Atomic credit and deduct with `@Transactional` and a balance check (`WalletService.credit` / `deduct`; wallet row is locked with `findByUserIdForUpdate`, balance below zero gives `INSUFFICIENT_BALANCE`)
- [x] Admin actions: preset credits (solved Bronze, Silver, Gold), generic hint deduction (participant and problem number), custom amount (`AdminWalletService`, `/api/admin/wallet/*`; each also writes the participant's pop-up event)
- [x] Undo by adding a reversing ledger entry (`WalletService.reverse`: an entry can be undone once, a reversal cannot be undone, `reversalOf` is unique in the database; admin endpoint `POST /api/admin/wallet/undo/{entryId}`, which also restores the corruption level when a devil entry is undone)
- [x] `GET /api/me` (coins, corruption level) and `GET /api/me/history`
- [x] Tests for the wallet logic (`WalletServiceTest`: Mockito unit tests, passing)

## Phase 4: Problems and round flow (about 2 days)
- [x] `Problem` entity: title, category, tier, slots, HackerRank link, base price
- [x] Hint versions: 3 per problem, one per corruption level, with passwords (admin only)
- [x] `Round` entity and phase state machine: Preview, Bidding, Settlement, Solving, Results (`RoundService`, `RoundServiceTest`)
- [x] Admin endpoints: start round, advance phase, pause, next round (plus resume, bidding deadline and settle)
- [x] Participant endpoints: current round and phase, problem preview (title, category, tier, teaser)

## Phase 5: Sealed bids and Vickrey settlement (about 2 to 3 days)
- [x] `Bid` entity: one bid per participant per problem, editable until the deadline
- [x] Server-side bidding deadline; reject late bids
- [x] Reject a bid above the participant's balance, and cap total bids at balance (also rejects a bid below the problem's reserve price)
- [x] Settlement: top k bidders win, all pay the (k+1)th highest bid, reserve price if too few bids, earlier bid wins ties (`VickreyCalculator`, `SettlementService`)
- [x] Settlement deducts coins and writes ledger entries in one transaction (a winner who can no longer pay is marked UNAFFORDABLE; the slot is not reassigned)
- [x] Results endpoint: won or lost, price paid (`GET /api/me/bids`)
- [x] Thorough tests for settlement (ties, too few bids, insufficient balance): `VickreyCalculatorTest`, `SettlementServiceTest`, `BidServiceTest` (passing)

## Phase 6: Corruption and the devil (about 1 to 2 days)
- [x] Corruption level on the user (3 levels, up or down)
- [x] Devil's deal: coins up, level up (atomic, logged)
- [x] Redemption: coins down, level down (cost higher than the deal pays; the app refuses to start otherwise)
- [x] Block deals beyond the level limits
- [x] Hint lookup by problem and the participant's current level (admin sees which version and password to give)

## Phase 7: Feedback and leaderboard (about 1 to 2 days)
- [x] Event feed for pop-ups (bid won or lost, hint deducted, credits, level change), delivered by polling (`GET /api/me/events?after=`)
- [x] Leaderboard endpoint (by coins; equal coins share a rank)
- [x] Notifications for phase changes (broadcast events)

## Phase 8: Hardening and launch (about 2 to 3 days)
- [x] Audit log for every admin action (`AdminAuditFilter` records who called which state-changing `/api/admin/**` endpoint and the HTTP status; no request bodies; read it at `GET /api/admin/audit`; covered by `AdminAuditFilterTest` and `AuditServiceTest`, passing)
- [x] Ownership and role checks on every service method, in addition to the URL rule in `SecurityConfig` (`@EnableMethodSecurity` plus `@PreAuthorize`, using `AccessGuard` registered as `@guard`: `isSelf(id)`, `isAdmin()`, `isSelfOrAdmin(id)`). Admin-only: round, problem, settlement, wallet, ledger, audit, user-manager and winners methods, and `WalletService.credit/deduct/reverse`. Admin wallet buttons also require the `adminId` to be the caller (`hasRole('ADMIN') and @guard.isSelf(#adminId)`). Own-data-only: `UserService`, `WalletService.history`, `BidService.myBids` and `AuthService.refresh` (the user id must be the signed-in user's). Bidding needs the PARTICIPANT role and the caller's own id. Any signed-in user: current round, problem preview and leaderboard. Deliberately open: login methods, `AuditService.record` (the filter also logs refused participant calls) and the internal `EventService` writers (only called from admin-only services). A failed check returns `FORBIDDEN` (403) through `GlobalExceptionHandler`. `AccessGuardTest` covers the guard itself, and `CoinSafetyIntegrationTest` checks the annotations on the real beans
- [x] Input validation on every request body (Bean Validation with `@Valid` on every body; the one exception is the bidding deadline, a single optional date that `RoundService` checks must be in the future. Failures return `VALIDATION_FAILED` with per-field `details`. No automated test exercises these rules end to end yet)
- [ ] Optional rate limiting (only the backup admin sign-in is throttled today: 5 failures per email in 5 minutes, kept in memory, answered with `TOO_MANY_REQUESTS`. Nothing limits the other endpoints)
- [x] Token version for instant JWT revocation (the JWT filter rejects a stale `tokenVersion`; `POST /api/admin/users/{userId}/revoke-sessions` bumps it with one atomic update, `AdminUserService.revokeSessions`; the user can sign in again and gets a token with the new version. Covered by `CoinSafetyIntegrationTest`)
- [x] Narrow integration tests for the coin rules and access checks: `CoinSafetyIntegrationTest` (simultaneous bids cannot exceed the wallet, simultaneous undo clicks reverse an entry only once, `@PreAuthorize` enforced on the real services, 401/403 and revoked sessions over HTTP). Needs its own database once: `CREATE DATABASE deal_devil_test;` on the Docker Postgres (or set `TEST_DB_URL`); it never touches `deal_devil`. Passing (2026-10-20). Broader integration tests of the main flows are deliberately skipped for this one-off event; a dry-run with test accounts covers them
- [ ] (Skipped on purpose) Broader integration tests for the main flows (before the narrow tests above, only `contextLoads` touched the real database; every service test mocks the repositories, so row locking, the unique `reversalOf` constraint and the repository queries have never run in a test beyond startup)
- [ ] Move secrets to environment variables (Google client ID, JWT secret, admin emails and the fallback passcode are read from environment variables, and so are `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`; the database defaults are still `postgres`/`postgres`, so set real values on the server)
- [ ] Switch `spring.jpa.hibernate.ddl-auto` from `update` to `validate` (or add migrations) before the event, so the server never changes the schema by itself
- [ ] Optional: silence the generated dev password Spring prints at startup (no `UserDetailsService` is defined, so Boot creates one; nothing uses it)
- [ ] Optional: manual override of a settlement result (listed in the project context; there is no endpoint for it, only undo of coin entries and the corruption level editor)
- [ ] Deployment plan (server, database, HTTPS) and a dry-run with test accounts

## Frontend (React), in parallel
- [ ] Google sign-in screen, token stored and sent as a Bearer header
- [ ] Participant app: dashboard, round preview, sealed bid screen, results pop-ups, my problems, leaderboard, rules
- [ ] Admin panel: round controls, problem manager, participant manager, preset buttons with confirm popups, ledger viewer

---

## Open decisions
- [ ] Coin prices, tier rewards, hint price, preset credit amounts (placeholders in `application.properties`: base price 50/100/200, solve reward 100/200/400, hint 30)
- [ ] Devil rates (coins per level; redemption must cost more than the deal pays; placeholders: the deal pays 100, redemption costs 150)
- [ ] Reserve price rule and tie-break details (implemented as: reserve = the problem's base price, bids below it are rejected, the earlier bid wins a tie; confirm with the organisers)
- [ ] Slots per problem, number of rounds, problems per round
- [ ] How the devil appears (every round, timed, or on request)
- [x] How long the event runs: handled either way. The JWT lasts 3 hours (`app.auth.jwt-expiry-hours`) and `POST /api/auth/refresh` gives a fresh one, so a long event needs no new Google sign-in
- [ ] Mystery assets (on hold)
- [x] HackerRank ID: participants can edit it themselves (decided)
- [ ] Do admins need a HackerRank ID too? (for now the profile gate only applies to participants)
- [x] Real admin emails (15 tech team members), the real Google OAuth client ID and a real JWT secret: set as the environment variables `ADMIN_EMAILS`, `GOOGLE_CLIENT_ID` and `JWT_SECRET` (kept out of the repo)
- [ ] Google Cloud: set the OAuth consent screen (Audience) to In production so non-test users can sign in; add the real React origin to Authorized JavaScript origins once deployed
- [ ] Phase 2: test login end to end with a real Google token (OAuth client and environment variables are now set up, but no real login has been tried yet)

## Suggested order
Phases 1 to 3 give a working login and wallet, then phases 4 and 5 make the core auction usable. Phases 6 and 7 add the extras. Phase 8 comes before the event, not after.
