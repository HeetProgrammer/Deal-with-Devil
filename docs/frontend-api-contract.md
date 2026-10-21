# Deal with Devil: Frontend API Contract

Everything the React developer needs to build both apps (participant app and admin panel) without the backend running.
Build against this file; every endpoint, shape and error below is final.

---

## 1. Basics

| Thing | Value |
|---|---|
| Base URL | `VITE_API_URL`, dev default `http://localhost:8080`. All routes start with `/api` |
| Format | JSON in and out. Send `Content-Type: application/json` on POST/PUT bodies |
| Auth | `Authorization: Bearer <JWT>` on every request except `GET /api/health`, `POST /api/auth/google`, `POST /api/auth/admin-fallback` |
| CORS | dev origins `http://localhost:5173` (Vite) and `http://localhost:3000` (CRA) |
| Coins | whole numbers, never fractions |
| IDs | numbers |
| Timestamps | ISO-8601 UTC strings, e.g. `"2026-11-02T10:30:00Z"` |
| Enums | uppercase strings (section 2) |
| Absent values | `null` |
| Dev sign-in | Google Identity Services in the browser gives an ID token; the Google client ID is provided by the backend dev (`VITE_GOOGLE_CLIENT_ID`). Admin panel access needs an allowlisted Google email |

### Roles and the two apps
Login returns `role`. `PARTICIPANT` → participant app. `ADMIN` → admin panel. The server also rejects every
`/api/admin/**` call from a non-admin with `403 FORBIDDEN`. Every service method also checks role and ownership on its own: a participant can only ever read or change their own data, so a `403 FORBIDDEN` can also come from a non-admin route if something is requested for another user. The UI does not need to handle this separately.

### Session
- JWT lifetime is about 3 hours (`expiresAt`).
- Call `POST /api/auth/refresh` when less than 15 minutes remain. It returns a fresh `AuthResponse`.
- On `TOKEN_EXPIRED` or `TOKEN_INVALID` (401): clear the token and show the login screen.
- Keep the token in memory or `sessionStorage`. Never add third-party scripts to the page.

### Profile gate (participants only)
Until `profileComplete` is `true`, every endpoint except `GET /api/me`, `PUT /api/me/profile`, `GET /api/config` and
`/api/auth/*` returns `403 PROFILE_INCOMPLETE`. Admins are not gated.

Login flow:
1. `POST /api/auth/google` → read `profileComplete` and `role`.
2. Participant with `profileComplete: false` → profile form → `PUT /api/me/profile` → dashboard.
3. Otherwise go straight to the dashboard of that role.

### Polling (no WebSockets)
| What | Endpoint | Interval |
|---|---|---|
| Round, phase, pause, countdown | `GET /api/rounds/current` | 5 s |
| Pop-ups and notifications | `GET /api/me/events?after=<lastId>` | 5 s |
| Coins and corruption level | `GET /api/me` | after every event and every 15 s |
| Leaderboard | `GET /api/leaderboard` | 15 s while the screen is open |

For countdowns use `serverTime` from `GET /api/rounds/current` to compute the offset between server and browser
clock, then count down to `biddingDeadline` using that offset.

---

## 2. Enums

```ts
type Role       = 'PARTICIPANT' | 'ADMIN';
type Tier       = 'BRONZE' | 'SILVER' | 'GOLD';
type RoundPhase = 'PREVIEW' | 'BIDDING' | 'SETTLEMENT' | 'SOLVING' | 'RESULTS';  // fixed order, forward only
type BidStatus  = 'PENDING' | 'WON' | 'LOST' | 'UNAFFORDABLE';
type LedgerType = 'PRESET_CREDIT' | 'HINT_DEDUCTION' | 'BID_SETTLEMENT' | 'DEVIL_DEAL'
                | 'REDEMPTION' | 'CUSTOM' | 'REVERSAL';
type EventType  = 'BID_WON' | 'BID_LOST' | 'BID_UNAFFORDABLE' | 'PRESET_CREDIT' | 'HINT_DEDUCTION'
                | 'DEVIL_DEAL' | 'REDEMPTION' | 'CUSTOM_ADJUSTMENT' | 'REVERSAL'
                | 'ROUND_STARTED' | 'PHASE_CHANGED';
```

- Corruption level: integer 1 to 3. Everyone starts at 1.
- `UNAFFORDABLE`: the participant would have won, but their balance no longer covered the price at settlement.

---

## 3. Errors

Every error has this shape. Switch on `code`, show `message`.

```ts
interface ErrorResponse {
  timestamp: string;
  status: number;
  code: ErrorCode;
  message: string;                    // safe to show to the user
  path: string;
  details?: Record<string, string>;   // field name -> problem; omitted when empty
}
```

| `code` | HTTP | UI behaviour |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Show `details` next to the matching fields |
| `MALFORMED_REQUEST` | 400 | Generic "something went wrong" |
| `INVALID_PARAMETER` | 400 | Generic |
| `INVALID_AMOUNT` | 400 | Show message |
| `UNAUTHORIZED` | 401 | Go to login |
| `INVALID_GOOGLE_TOKEN` | 401 | "Google sign-in failed, try again" |
| `INVALID_CREDENTIALS` | 401 | Wrong fallback admin credentials |
| `TOKEN_EXPIRED` | 401 | Go to login |
| `TOKEN_INVALID` | 401 | Go to login |
| `FORBIDDEN` | 403 | "You are not allowed to do that" |
| `EMAIL_NOT_VERIFIED` | 403 | "Verify your Google email first" |
| `PROFILE_INCOMPLETE` | 403 | Go to the profile form |
| `NOT_FOUND`, `USER_NOT_FOUND`, `PROBLEM_NOT_FOUND` | 404 | Show message |
| `ROUND_NOT_FOUND` | 404 | Normal before round 1: show a "Event has not started yet" screen, not an error |
| `METHOD_NOT_ALLOWED` | 405 | Generic |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Generic |
| `CONFLICT` | 409 | Show message; also used for "HackerRank ID taken" (`details.hackerRankId`) and "already undone" |
| `CONCURRENT_UPDATE` | 409 | Refetch and let the user retry |
| `INSUFFICIENT_BALANCE` | 409 | Show message |
| `BID_EXCEEDS_BALANCE` | 409 | Show `message` ("Your 3 highest bids would add up to X coins, but you only have Y") |
| `INVALID_PHASE` | 409 | Show message; refetch the round |
| `BIDDING_CLOSED` | 409 | Lock the bid screen, refetch the round |
| `ALREADY_SETTLED` | 409 | Show message |
| `CORRUPTION_LIMIT_REACHED` | 409 | Show message |
| `TOO_MANY_REQUESTS` | 429 | "Slow down" (today only returned after repeated failed fallback admin sign-ins) |
| `SERVICE_UNAVAILABLE` | 503 | "Try again shortly" |
| `INTERNAL_ERROR` | 500 | Generic |

---

## 4. TypeScript types

```ts
// ---------- Auth ----------
interface GoogleLoginRequest { idToken: string }
interface AdminFallbackRequest { email: string; passcode: string }
interface AuthResponse {
  token: string;
  expiresAt: string;
  profileComplete: boolean;
  role: Role;
}

// ---------- Config ----------
interface TierAmounts { bronze: number; silver: number; gold: number }
interface GameConfigResponse {
  basePrice: TierAmounts;            // default reserve price per tier
  solveReward: TierAmounts;          // credit for "solved Bronze/Silver/Gold"
  hintPrice: number;                 // same for every problem
  devil: { dealPayout: number; redemptionCost: number };  // redemptionCost > dealPayout
  minCorruptionLevel: number;        // 1
  maxCorruptionLevel: number;        // 3
}

// ---------- Me ----------
interface MeResponse {
  id: number;
  email: string;
  name: string | null;               // null until the profile form is saved
  hackerRankId: string | null;
  role: Role;
  corruptionLevel: number;           // 1..3
  coins: number;
  profileComplete: boolean;
}
interface ProfileRequest {
  name: string;                      // required, max 100
  hackerRankId: string;              // required, max 50, no spaces, unique (case-insensitive)
}

// ---------- Ledger ----------
interface LedgerEntryResponse {
  id: number;
  type: LedgerType;
  amount: number;                    // signed: positive credit, negative deduction
  balanceAfter: number;
  reason: string;
  reversalOf: number | null;
  createdAt: string;
}
interface AdminLedgerEntryResponse extends LedgerEntryResponse {
  userId: number;
  userName: string | null;
  actorName: string | null;          // admin who did it (their email if they have no name); null for system actions (settlement)
  undone: boolean;                   // true if a reversal already exists for this entry
}

// ---------- Events (pop-ups and notifications) ----------
interface EventResponse {
  id: number;                        // increasing; use as the `after` cursor
  type: EventType;
  broadcast: boolean;                // true = for everyone (notification bell only); false = personal (show a pop-up)
  message: string;                   // ready to display
  amount: number | null;             // signed coin change, when relevant
  balanceAfter: number | null;
  corruptionLevel: number | null;    // new level, for DEVIL_DEAL / REDEMPTION
  problemId: number | null;          // for BID_* events
  createdAt: string;
}

// ---------- Rounds ----------
interface RoundResponse {
  id: number;
  number: number;
  phase: RoundPhase;
  paused: boolean;
  phaseChangedAt: string;
  biddingDeadline: string | null;    // null = no deadline set
  serverTime: string;                // for clock-skew correction
}

// ---------- Problems ----------
interface ProblemPreviewResponse {   // participant view: no link, hints or passwords
  id: number;
  title: string;
  category: string;
  tier: Tier;
  teaser: string | null;
  preview: string | null;            // question preview text written by the admin; null until added. Show it on the problem card so people can bid on it
  slots: number;                     // number of winners this problem has
  basePrice: number;                 // reserve price
}
interface HintResponse {             // admin only
  corruptionLevel: number;           // 1..3
  hintText: string;
  password: string;
}
interface AdminProblemResponse {
  id: number;
  title: string;
  category: string;
  tier: Tier;
  slots: number;
  hackerRankLink: string;
  basePrice: number;
  teaser: string | null;
  preview: string | null;            // question preview shown to participants; null until added
  roundId: number | null;            // null = bank only
  hints: HintResponse[];             // up to 3, ordered by corruptionLevel
}
interface ProblemRequest {
  title: string;                     // required, max 150
  category: string;                  // required, max 60
  tier: Tier;                        // required
  slots: number;                     // required, 1..100
  hackerRankLink: string;            // required, max 500, must match ^https?://\S+$
  basePrice?: number | null;         // optional, >= 0; omitted = tier default from /api/config
  teaser?: string | null;            // optional, max 500
  roundId?: number | null;           // optional; omitted = bank only
}
interface ProblemPreviewRequest {
  preview: string | null;            // plain text, max 4000; null or blank removes the preview
}
interface HintRequest {
  hintText: string;                  // required, max 2000
  password: string;                  // required, max 100
}
interface WinnerResponse {
  userId: number;
  name: string | null;
  hackerRankId: string | null;
  pricePaid: number;
}

// ---------- Bidding ----------
interface BidRequest { amount: number }          // whole coins, >= 1
interface BidResponse {
  problemId: number;
  problemTitle: string;
  amount: number;
  status: BidStatus;
  pricePaid: number | null;          // what a winner paid (the clearing price, not their bid)
  hackerRankLink: string | null;     // only when status is WON
  submittedAt: string;
}
interface BiddingDeadlineRequest { deadline: string | null }   // ISO instant, or null to clear
interface SettlementSummaryResponse {
  roundId: number;
  roundNumber: number;
  totalCollected: number;
  problems: {
    problemId: number;
    title: string;
    bidCount: number;
    winnerCount: number;
    unaffordableCount: number;
    clearingPrice: number;
  }[];
}

// ---------- Leaderboard ----------
interface LeaderboardEntry {
  rank: number;                      // equal coins share a rank
  userId: number;
  name: string | null;
  hackerRankId: string | null;
  coins: number;
}

// ---------- Admin wallet buttons ----------
interface PresetCreditRequest  { userId: number; tier: Tier }
interface HintDeductionRequest { userId: number; problemNumber: number }          // problemNumber >= 1, label only
interface CustomAmountRequest  { userId: number; amount: number; reason: string } // reason max 255; amount != 0
interface UndoRequest          { reason: string }                                 // max 200
interface DevilActionRequest   { userId: number }
interface DevilActionResponse  { entry: LedgerEntryResponse; corruptionLevel: number }
interface CorruptionLevelRequest { level: number }                                // 1..3
```

---

## 5. Public endpoints

### `GET /api/health`
`200` `{ "status": "UP", "time": "<ISO instant>" }`

### `POST /api/auth/google`
Body `GoogleLoginRequest`. `200` `AuthResponse`.
Errors: `INVALID_GOOGLE_TOKEN`, `EMAIL_NOT_VERIFIED`, `VALIDATION_FAILED`.

### `POST /api/auth/admin-fallback`
Backup admin sign-in for when Google sign-in is unavailable. Body `AdminFallbackRequest`. `200` `AuthResponse` with `role: "ADMIN"`.
Error: `INVALID_CREDENTIALS`.
Show it as a small "Admin fallback sign-in" link on the login screen.

---

## 6. Endpoints for any signed-in user

### `POST /api/auth/refresh`
No body. `200` `AuthResponse` (new token, same user). Call it before expiry (section 1).

### `GET /api/config`
`200` `GameConfigResponse`. Fetch once after login. Use it for prices in confirm popups and the rules page.

### `GET /api/me`
`200` `MeResponse`. Dashboard data.

### `PUT /api/me/profile`
Body `ProfileRequest`. `200` `MeResponse`.
Errors: `VALIDATION_FAILED` (per-field `details`), `CONFLICT` with `details.hackerRankId` when taken.
Also used later to correct the HackerRank ID.

### `GET /api/me/history`
`200` `LedgerEntryResponse[]`, newest first. Transaction history screen.

### `GET /api/me/events?after={id}&limit={n}`
`200` `EventResponse[]`, oldest first. `limit` defaults to 50.
- First call with no `after`: returns the most recent 20. Use it to fill the notification list and set `lastId`. **Do not pop up for these.**
- Later calls with `after=lastId`: only newer events. For each one, update `lastId`.
  - `broadcast: false` → show a transaction pop-up (`message`, plus `amount` and `balanceAfter` when present), then refetch `/api/me`.
  - `broadcast: true` → add to the notification bell only (`ROUND_STARTED`, `PHASE_CHANGED`).
- Pop-up styling by `type`: `BID_WON`, `PRESET_CREDIT`, `DEVIL_DEAL` positive; `BID_LOST`, `BID_UNAFFORDABLE`, `HINT_DEDUCTION`, `REDEMPTION` negative or neutral; `REVERSAL` and `CUSTOM_ADJUSTMENT` by the sign of `amount`.
- `DEVIL_DEAL` and `REDEMPTION` carry the new `corruptionLevel`.

### `GET /api/rounds/current`
`200` `RoundResponse`. `404 ROUND_NOT_FOUND` before round 1.

### `GET /api/rounds/current/problems`
`200` `ProblemPreviewResponse[]` ordered by `id`; empty array if none. `404 ROUND_NOT_FOUND` before round 1.

### `PUT /api/rounds/current/problems/{problemId}/bid`
Place or change a sealed bid. Body `BidRequest`. `200` `BidResponse` (status `PENDING`).
- One bid per participant per problem; calling again replaces the amount.
- Participants only: an admin calling this gets `403 FORBIDDEN`.
- Only valid in phase `BIDDING`, not paused, before `biddingDeadline`.
- Errors: `BIDDING_CLOSED`, `BID_EXCEEDS_BALANCE` (the participant's 3 highest bids in the round, with this one included, would add up to more than their coins; only the top 3 count because nobody can win more than 3 problems, so lower bids may exceed the balance), `PROBLEM_NOT_FOUND` (also when the problem is not in the current round), `VALIDATION_FAILED` (amount < 1, or below the problem's `basePrice`; `details.amount` says which).
- A bid must be at least the problem's reserve price (`basePrice` in the preview). Disable or warn in the bid input below that amount.
- Bids are secret; a participant only ever sees their own.

### `GET /api/me/bids`
`200` `BidResponse[]`: the participant's bids in the current round (only problems they bid on).
After settlement: `status` is `WON`, `LOST` or `UNAFFORDABLE`, and winners get `pricePaid` and `hackerRankLink`.
"My problems" = bids with `status === 'WON'`.

### `GET /api/leaderboard`
`200` `LeaderboardEntry[]`, sorted by `coins` descending, then by name (participants with a completed profile only). Highlight the row where `userId` equals the signed-in user.

---

## 7. Admin endpoints (`ADMIN` only)

### Round and auction control

| Call | Body | Success | Rules |
|---|---|---|---|
| `POST /api/admin/rounds` | none | `201` `RoundResponse` | Starts round 1, or the next round. `INVALID_PHASE` unless there is no round yet or the latest is in `RESULTS` |
| `POST /api/admin/rounds/current/advance` | none | `200` `RoundResponse` | Next phase. `INVALID_PHASE` if paused, finished, leaving `PREVIEW` with no problems, leaving `PREVIEW` with fewer than 3 problems or fewer total slots than 3 x participants (press auto slots again first), or in `SETTLEMENT` (use settle instead) |
| `POST /api/admin/rounds/current/pause` | none | `200` `RoundResponse` | Idempotent. Paused rounds reject bids |
| `POST /api/admin/rounds/current/resume` | none | `200` `RoundResponse` | Idempotent |
| `PUT /api/admin/rounds/current/bidding-deadline` | `BiddingDeadlineRequest` | `200` `RoundResponse` | Allowed in `PREVIEW` or `BIDDING`. `null` clears it. A deadline must be in the future (`VALIDATION_FAILED`, `details.deadline`); to stop bidding now, advance the phase instead. Bids at or after the deadline are rejected |
| `POST /api/admin/rounds/current/settle` | none | `200` `SettlementSummaryResponse` | Only in `SETTLEMENT`. Runs one allocation for the whole round with a cap of 3 wins per person, charges winners, moves the round to `SOLVING`. `ALREADY_SETTLED` if repeated, `INVALID_PHASE` if the round is not in `SETTLEMENT` or is paused. Coins, bid statuses and the phase change in one transaction. Anyone left below 3 problems is topped up afterwards with auto fill (section "Exactly 3 problems per person") |
| `GET /api/admin/rounds/current/settlement` | none | `200` `SettlementSummaryResponse` | Re-read the summary after settlement. `INVALID_PHASE` before settlement |

Phase buttons by `phase`:
- `PREVIEW` → button "Open bidding" (advance). Deadline picker enabled.
- `BIDDING` → button "Close bidding" (advance). Deadline picker enabled.
- `SETTLEMENT` → button "Run settlement" (settle). No advance.
- `SOLVING` → button "Show results" (advance).
- `RESULTS` → button "Start next round" (`POST /api/admin/rounds`).
- Pause/Resume toggle always available; disable all phase buttons while `paused`.
- Always refetch `GET /api/rounds/current` after any of these calls; on `CONCURRENT_UPDATE` refetch and retry.

Settlement rule for display: all eligible bids of the round (at or above the problem's reserve price) are ranked by amount, then earlier submission. A bid wins only if its bidder has fewer than 3 wins so far and the problem still has a free slot. Every winner of a problem pays that problem's price: the first bid turned away because the problem was full, else the reserve price (`basePrice`). When nobody would win more than 3 problems this is the same as the plain per-problem Vickrey rule.

### Exactly 3 problems per person (Phase 9)

Every participant must end each round with exactly 3 problems (`game.wins-per-round`). Admin flow for one round:

1. In `PREVIEW`, after the problems are added and sign-ups are done: press **Auto slots**. Dry run first, then apply. Press it again whenever a new participant has signed in. Nobody edits slots by hand.
2. Open bidding (blocked with `INVALID_PHASE` until there are at least 3 problems and 3 x participants slots).
3. While bidding is open: **Bid coverage** shows who has bid on few or no problems.
4. After settlement (`SOLVING`): **Allocation view** flags everyone not at 3. Press **Auto fill** (dry run first). Whoever it cannot finish appears in `stillShort`: finish them with **Manual assignment**.

```ts
interface AutoSlotsResponse {
  dryRun: boolean;
  participants: number;              // participants with a completed profile
  problemCount: number;
  slotsNeeded: number;               // 3 x participants
  slotsAssigned: number;             // equals slotsNeeded when the round has at least 3 problems
  slotsPerProblem: { problemId: number; title: string; slots: number }[];
}
interface BidCoverageResponse {
  roundId: number; roundNumber: number; problemCount: number; winsPerRound: number;
  participants: { userId: number; name: string | null; hackerRankId: string | null;
                  bidCount: number; missingBids: number }[];   // fewest bids first
}
interface AllocationViewResponse {
  roundId: number; roundNumber: number; winsPerRound: number;
  participantCount: number; atTargetCount: number;
  participants: { userId: number; name: string | null; hackerRankId: string | null; coins: number;
                  wins: number; unaffordableBids: number; missing: number; excess: number;
                  atTarget: boolean }[];                        // people not at 3 first
  problems: { problemId: number; title: string; slots: number; sold: number; unaffordable: number;
              unsold: number; overCapacity: number }[];
}
interface AutoFillResponse {
  dryRun: boolean; winsPerRound: number;
  assignments: { userId: number; name: string | null; hackerRankId: string | null;
                 problemId: number; problemTitle: string; price: number }[];
  stillShort: { userId: number; name: string | null; hackerRankId: string | null;
                wins: number; missing: number }[];
}
interface ManualAssignmentRequest { userId: number; problemId: number }
interface ManualAssignmentResponse {
  userId: number; problemId: number; problemTitle: string;
  pricePaid: number; balanceAfter: number; winsNow: number;
  overCapacity: boolean;             // true: the problem now has more winners than slots (allowed on purpose)
}
```

| Call | Body | Success | Rules |
|---|---|---|---|
| `POST /api/admin/rounds/current/slots/auto?dryRun=true\|false` | none | `200` `AutoSlotsResponse` | `dryRun` is required (`400 INVALID_PARAMETER` if missing). Only in `PREVIEW` (`INVALID_PHASE`). `VALIDATION_FAILED` with `details.participants` when nobody has completed a profile, or `details.problems` when the round has fewer than 3 problems. Safe to press again |
| `GET /api/admin/rounds/current/bid-coverage` | none | `200` `BidCoverageResponse` | Any phase of the current round. `404 ROUND_NOT_FOUND` before round 1 |
| `GET /api/admin/rounds/current/allocation` | none | `200` `AllocationViewResponse` | Only in `SOLVING` or `RESULTS` (`INVALID_PHASE` before) |
| `POST /api/admin/rounds/current/auto-fill?dryRun=true\|false` | none | `200` `AutoFillResponse` | `dryRun` is required. Only in `SOLVING` (`INVALID_PHASE`). Gives everyone below 3 problems from the unsold slots at the reserve price, skipping problems they already hold and ones they cannot afford. Safe to press again. If two presses collide, one may get `409 CONCURRENT_UPDATE`: refetch |
| `POST /api/admin/rounds/current/assignments` | `ManualAssignmentRequest` | `200` `ManualAssignmentResponse` | Only in `SOLVING`. Gives one problem to one participant at its reserve price. `CONFLICT` if they already hold it or already have 3 problems, `INSUFFICIENT_BALANCE`, `PROBLEM_NOT_FOUND` (also outside the current round), `USER_NOT_FOUND` (also for admins), `VALIDATION_FAILED` for missing fields |

Auto-filled and manually assigned problems behave exactly like normal wins: they appear in `GET /api/me/bids` with status `WON`, `pricePaid` and the HackerRank link, in the ledger (type `BID_SETTLEMENT`, the admin named in `actorName`, reason says "automatically" or "by an admin"), and as a `BID_WON` pop-up. Show a confirm popup before auto fill (after the dry run) and before every manual assignment.

Suggested admin screens: a **Slots** card in the PREVIEW controls (dry run result, then Apply), a **Bid coverage** table during BIDDING (highlight rows with `missingBids > 0`), and an **Allocation** page in SOLVING with the flagged participants on top, unsold slots per problem, the Auto fill button and a per-row "Give a problem" action for `stillShort` people.

### Problem bank

| Call | Body | Success |
|---|---|---|
| `GET /api/admin/problems` (`?roundId=` optional) | none | `200` `AdminProblemResponse[]` |
| `GET /api/admin/problems/{problemId}` | none | `200` `AdminProblemResponse` |
| `POST /api/admin/problems` | `ProblemRequest` | `201` `AdminProblemResponse` |
| `PUT /api/admin/problems/{problemId}` | `ProblemRequest` (full replace) | `200` `AdminProblemResponse` |
| `DELETE /api/admin/problems/{problemId}` | none | `204` |
| `PUT /api/admin/problems/{problemId}/preview` | `ProblemPreviewRequest` | `200` `AdminProblemResponse` |
| `PUT /api/admin/problems/{problemId}/hints/{level}` | `HintRequest` | `200` `HintResponse` |
| `GET /api/admin/problems/{problemId}/winners` | none | `200` `WinnerResponse[]` (after settlement) |

- Problems attached to a round can be created, edited, moved or deleted only while that round is in `PREVIEW`; otherwise `409 INVALID_PHASE`. Disable those controls when `phase !== 'PREVIEW'`.
- `PUT` replaces everything; send all fields. It does not touch the question preview.
- The question preview is its own call, `PUT .../preview`. It is what participants read before bidding (the HackerRank link is never shown to bidders). Like the other problem fields it is editable only while the round is in `PREVIEW` (`409 INVALID_PHASE` afterwards). A blank or `null` text removes it. Add a "Question preview" text area to the problem editor.
- `{level}` is 1, 2 or 3 (`400 VALIDATION_FAILED` with `details.level` otherwise). Creates or replaces that level's hint. Hints stay editable at any time.
- Build the editor as one form per problem with three hint rows (level 1, 2, 3), each with text and password.

### Participants

| Call | Body | Success |
|---|---|---|
| `GET /api/admin/users` (`?q=` optional) | none | `200` `MeResponse[]`, participants only, sorted by name. With `q`, only participants whose name or HackerRank ID contains the text (any letter case, spaces around it ignored); a blank `q` returns everyone, no match returns `[]`. Use it for the search box: type a name or HackerRank ID, read the `id`, and use it in the wallet buttons |
| `GET /api/admin/users/{userId}/hints/{problemId}` | none | `200` `HintResponse`: the hint version and password matching this participant's current corruption level; `404 NOT_FOUND` if no hint has been written for that level yet |
| `PUT /api/admin/users/{userId}/corruption-level` | `CorruptionLevelRequest` | `200` `MeResponse` |
| `POST /api/admin/users/{userId}/revoke-sessions` | none | `204` |

`revoke-sessions` signs the participant out everywhere: every token issued to them earlier is rejected with `401 TOKEN_INVALID` on its next use. They can sign in again normally. `404 USER_NOT_FOUND` for an unknown id. Use it for a cheater or a lost device; show a confirm popup first.

The participant manager table shows name, HackerRank ID, email, coins and corruption level, with a search box
(`?q=`) above it and a row action menu for every button below. After a round, the admin searches for a participant,
then uses the row's credit buttons, so nobody ever has to type or know a user id. "Give hint" flow: confirm popup → `GET .../hints/{problemId}` to show which text and
password to hand out → `POST /api/admin/wallet/hint-deduction`.

### Wallet and devil buttons
Every call returns the resulting ledger entry (`balanceAfter` included). **Show a confirm popup with the participant's name and the amount before every call.** Get amounts for the popup text from `GET /api/config`.

| Button | `POST` path | Body | Success | Effect and errors |
|---|---|---|---|---|
| Solved Bronze / Silver / Gold | `/api/admin/wallet/credit-preset` | `PresetCreditRequest` | `200` `LedgerEntryResponse` | Credits `config.solveReward[tier]` |
| Hint | `/api/admin/wallet/hint-deduction` | `HintDeductionRequest` | `200` `LedgerEntryResponse` | Deducts `config.hintPrice`. `problemNumber` is a label for the log, not an id. `INSUFFICIENT_BALANCE` |
| Custom | `/api/admin/wallet/custom` | `CustomAmountRequest` | `200` `LedgerEntryResponse` | Positive credits, negative deducts, `0` → `INVALID_AMOUNT`. `INSUFFICIENT_BALANCE` |
| Devil's deal | `/api/admin/wallet/devil-deal` | `DevilActionRequest` | `200` `DevilActionResponse` | Corruption level +1, coins + `config.devil.dealPayout`. `CORRUPTION_LIMIT_REACHED` at level 3 |
| Redemption | `/api/admin/wallet/redemption` | `DevilActionRequest` | `200` `DevilActionResponse` | Corruption level −1, coins − `config.devil.redemptionCost`. `CORRUPTION_LIMIT_REACHED` at level 1, `INSUFFICIENT_BALANCE` |
| Undo | `/api/admin/wallet/undo/{entryId}` | `UndoRequest` | `200` `LedgerEntryResponse` | Adds a `REVERSAL` entry. Undoing a devil entry also restores the previous corruption level. `404 NOT_FOUND`; `409 CONFLICT` if already undone or if the entry is itself a reversal |
| Credit everyone | `/api/admin/wallet/credit-all` | `CreditAllRequest` | `200` `CreditAllResponse` | Gives every participant the same coins in one transaction: one `CUSTOM` ledger entry and one pop-up each. Admins are excluded; people who sign up later do not get it. `VALIDATION_FAILED` for amount < 1 (max 1,000,000), a blank `reason` (max 200, shown to every participant) or a `requestId` shorter than 8 characters, and `details.participants` when there are no participants. Same `requestId` again: `409 CONFLICT`, nobody is credited twice. A wrong run is undone entry by entry with the normal undo |

Disable "Devil's deal" at level 3 and "Redemption" at level 1 using the participant's `corruptionLevel`.

```ts
interface CreditAllRequest {
  amount: number;       // whole coins, 1..1000000
  reason: string;       // required, max 200, shown to every participant
  requestId: string;    // 8..100 chars; make a NEW one (e.g. a UUID) each time the confirm popup opens, and reuse it on a retry
}
interface CreditAllResponse { participants: number; amountEach: number; totalCredited: number }
```

Confirm popup for "Credit everyone": show the amount, the reason and the number of participants (count them from `GET /api/admin/users`). Run it after sign-ups close.
`reason` on custom and undo is shown to the participant in their history, so label it for them.

### Ledger viewer

`GET /api/admin/ledger?userId={id}&limit={n}` → `200` `AdminLedgerEntryResponse[]`, newest first.
`userId` optional (all participants); `limit` defaults to 100, max 500.
Show an "Undo" button only when `type !== 'REVERSAL'` and `undone === false`.

### Audit log

`GET /api/admin/audit?actorId={id}&limit={n}` → `200` `AuditLogResponse[]`, newest first.
`actorId` optional (everyone); `limit` defaults to 100, max 500.

```ts
interface AuditLogResponse {
  id: number;
  actorId: number;
  actorName: string | null;
  actorEmail: string;
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string;        // e.g. "/api/admin/rounds/current/settle"
  status: number;      // HTTP status the call ended with: 2xx went through, 4xx/5xx was refused or failed
  createdAt: string;
}
```

- Every state-changing call to `/api/admin/**` is logged automatically, including refused ones and attempts by participants (status 403). Read-only calls are not logged.
- Request bodies are never stored. For coin details use the ledger viewer.
- Show it as a read-only table in the admin panel; there is nothing to create or edit.

---

## 8. Screen to endpoint map

**Participant app**

| Screen | Calls |
|---|---|
| Login | `POST /api/auth/google`, `GET /api/config` |
| Profile form | `PUT /api/me/profile` |
| Dashboard (coins, round, phase, timer, corruption level) | `GET /api/me`, `GET /api/rounds/current` |
| Round preview | `GET /api/rounds/current/problems` |
| Sealed bid screen | `GET /api/rounds/current/problems`, `GET /api/me/bids`, `PUT .../problems/{id}/bid` |
| Transaction pop-ups + notification bell | `GET /api/me/events` |
| Transaction history | `GET /api/me/history` |
| My problems (HackerRank links) | `GET /api/me/bids` (only `WON`) |
| Leaderboard | `GET /api/leaderboard` |
| Rules | static page; prices from `GET /api/config` |

**Admin panel**

| Screen | Calls |
|---|---|
| Round and phase controls | `GET /api/rounds/current`, `/api/admin/rounds...` |
| Auction controls (deadline, settle, summary) | `PUT .../bidding-deadline`, `POST .../settle`, `GET .../settlement` |
| Problem bank manager | `/api/admin/problems...` |
| Participant manager | `GET /api/admin/users`, `PUT .../corruption-level` |
| Action buttons with confirm popups | `/api/admin/wallet/...` (including credit-all) |
| Slots, bid coverage, allocation, auto fill, manual assignment | `POST .../slots/auto`, `GET .../bid-coverage`, `GET .../allocation`, `POST .../auto-fill`, `POST .../assignments` |
| Hint lookup | `GET /api/admin/users/{userId}/hints/{problemId}` |
| Ledger viewer + undo | `GET /api/admin/ledger`, `POST /api/admin/wallet/undo/{entryId}` |
| Audit log viewer | `GET /api/admin/audit` |

### Participant screen behaviour by phase

| Phase | Participant sees |
|---|---|
| none (`ROUND_NOT_FOUND`) | "Event has not started yet" |
| `PREVIEW` | Problem cards (title, category, tier, teaser, slots, reserve price). No bidding |
| `BIDDING` | Sealed bid screen: amount input per problem, countdown to `biddingDeadline`, remaining balance after bids. Paused → read-only banner |
| `SETTLEMENT` | "Bidding closed, results coming" |
| `SOLVING` | Own results: won problems (exactly 3 after auto fill) with HackerRank link and price paid; lost/unaffordable bids |
| `RESULTS` | Same as solving plus leaderboard highlight |

---

## 9. API client skeleton

```ts
const BASE = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string,
              public details?: Record<string, string>) { super(message); }
}

export async function api<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
  if (res.status === 204) return undefined as T;
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const e = body as ErrorResponse | null;
    // TOKEN_EXPIRED / TOKEN_INVALID / UNAUTHORIZED -> logout; PROFILE_INCOMPLETE -> profile form
    throw new ApiError(res.status, e?.code ?? 'INTERNAL_ERROR', e?.message ?? 'Request failed', e?.details);
  }
  return body as T;
}
```

---

## 10. Mock data for working offline

Use MSW or a JSON fixture with the types in section 4. Minimum fixtures to cover every screen:
one participant and one admin `MeResponse`; a `RoundResponse` in each of the five phases; three `ProblemPreviewResponse`
(one per tier); `BidResponse` in each status; `LedgerEntryResponse` of each `LedgerType`; `EventResponse` of each
`EventType` (personal and broadcast); one `SettlementSummaryResponse`; a `LeaderboardEntry[]` with a tie; an
`ErrorResponse` for each code in section 3.
