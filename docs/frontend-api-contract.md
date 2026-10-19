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
`/api/admin/**` call from a non-admin with `403 FORBIDDEN`.

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
| `BID_EXCEEDS_BALANCE` | 409 | "Your bids add up to more coins than you have" |
| `INVALID_PHASE` | 409 | Show message; refetch the round |
| `BIDDING_CLOSED` | 409 | Lock the bid screen, refetch the round |
| `ALREADY_SETTLED` | 409 | Show message |
| `CORRUPTION_LIMIT_REACHED` | 409 | Show message |
| `TOO_MANY_REQUESTS` | 429 | "Slow down" |
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
  actorName: string | null;          // admin who did it; null for system actions (settlement)
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
- Only valid in phase `BIDDING`, not paused, before `biddingDeadline`.
- Errors: `BIDDING_CLOSED`, `BID_EXCEEDS_BALANCE` (sum of all the participant's bids in the round would exceed their coins), `PROBLEM_NOT_FOUND` (also when the problem is not in the current round), `VALIDATION_FAILED` (amount < 1).
- Bids are secret; a participant only ever sees their own.

### `GET /api/me/bids`
`200` `BidResponse[]`: the participant's bids in the current round (only problems they bid on).
After settlement: `status` is `WON`, `LOST` or `UNAFFORDABLE`, and winners get `pricePaid` and `hackerRankLink`.
"My problems" = bids with `status === 'WON'`.

### `GET /api/leaderboard`
`200` `LeaderboardEntry[]`, sorted by `coins` descending (participants only). Highlight the row where `userId` equals the signed-in user.

---

## 7. Admin endpoints (`ADMIN` only)

### Round and auction control

| Call | Body | Success | Rules |
|---|---|---|---|
| `POST /api/admin/rounds` | none | `201` `RoundResponse` | Starts round 1, or the next round. `INVALID_PHASE` unless there is no round yet or the latest is in `RESULTS` |
| `POST /api/admin/rounds/current/advance` | none | `200` `RoundResponse` | Next phase. `INVALID_PHASE` if paused, finished, leaving `PREVIEW` with no problems, or in `SETTLEMENT` (use settle instead) |
| `POST /api/admin/rounds/current/pause` | none | `200` `RoundResponse` | Idempotent. Paused rounds reject bids |
| `POST /api/admin/rounds/current/resume` | none | `200` `RoundResponse` | Idempotent |
| `PUT /api/admin/rounds/current/bidding-deadline` | `BiddingDeadlineRequest` | `200` `RoundResponse` | Allowed in `PREVIEW` or `BIDDING`. `null` clears it. Bids at or after the deadline are rejected |
| `POST /api/admin/rounds/current/settle` | none | `200` `SettlementSummaryResponse` | Only in `SETTLEMENT`. Runs the Vickrey settlement, charges winners, moves the round to `SOLVING`. `ALREADY_SETTLED` if repeated, `INVALID_PHASE` otherwise |
| `GET /api/admin/rounds/current/settlement` | none | `200` `SettlementSummaryResponse` | Re-read the summary after settlement. `INVALID_PHASE` before settlement |

Phase buttons by `phase`:
- `PREVIEW` → button "Open bidding" (advance). Deadline picker enabled.
- `BIDDING` → button "Close bidding" (advance). Deadline picker enabled.
- `SETTLEMENT` → button "Run settlement" (settle). No advance.
- `SOLVING` → button "Show results" (advance).
- `RESULTS` → button "Start next round" (`POST /api/admin/rounds`).
- Pause/Resume toggle always available; disable all phase buttons while `paused`.
- Always refetch `GET /api/rounds/current` after any of these calls; on `CONCURRENT_UPDATE` refetch and retry.

Settlement rule for display: for each problem, the top `slots` bidders win and all pay the (slots+1)th highest bid, or the problem's `basePrice` if there are not enough bids; ties go to the earlier bid.

### Problem bank

| Call | Body | Success |
|---|---|---|
| `GET /api/admin/problems` (`?roundId=` optional) | none | `200` `AdminProblemResponse[]` |
| `GET /api/admin/problems/{problemId}` | none | `200` `AdminProblemResponse` |
| `POST /api/admin/problems` | `ProblemRequest` | `201` `AdminProblemResponse` |
| `PUT /api/admin/problems/{problemId}` | `ProblemRequest` (full replace) | `200` `AdminProblemResponse` |
| `DELETE /api/admin/problems/{problemId}` | none | `204` |
| `PUT /api/admin/problems/{problemId}/hints/{level}` | `HintRequest` | `200` `HintResponse` |
| `GET /api/admin/problems/{problemId}/winners` | none | `200` `WinnerResponse[]` (after settlement) |

- Problems attached to a round can be created, edited, moved or deleted only while that round is in `PREVIEW`; otherwise `409 INVALID_PHASE`. Disable those controls when `phase !== 'PREVIEW'`.
- `PUT` replaces everything; send all fields.
- `{level}` is 1, 2 or 3 (`400 VALIDATION_FAILED` with `details.level` otherwise). Creates or replaces that level's hint. Hints stay editable at any time.
- Build the editor as one form per problem with three hint rows (level 1, 2, 3), each with text and password.

### Participants

| Call | Body | Success |
|---|---|---|
| `GET /api/admin/users` | none | `200` `MeResponse[]`, participants only, sorted by name |
| `GET /api/admin/users/{userId}/hints/{problemId}` | none | `200` `HintResponse`: the hint version and password matching this participant's current corruption level |
| `PUT /api/admin/users/{userId}/corruption-level` | `CorruptionLevelRequest` | `200` `MeResponse` |

The participant manager table shows name, HackerRank ID, email, coins and corruption level, with a row action
menu for every button below. "Give hint" flow: confirm popup → `GET .../hints/{problemId}` to show which text and
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

Disable "Devil's deal" at level 3 and "Redemption" at level 1 using the participant's `corruptionLevel`.
`reason` on custom and undo is shown to the participant in their history, so label it for them.

### Ledger viewer

`GET /api/admin/ledger?userId={id}&limit={n}` → `200` `AdminLedgerEntryResponse[]`, newest first.
`userId` optional (all participants); `limit` defaults to 100, max 500.
Show an "Undo" button only when `type !== 'REVERSAL'` and `undone === false`.

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
| Action buttons with confirm popups | `/api/admin/wallet/...` |
| Hint lookup | `GET /api/admin/users/{userId}/hints/{problemId}` |
| Ledger viewer + undo | `GET /api/admin/ledger`, `POST /api/admin/wallet/undo/{entryId}` |

### Participant screen behaviour by phase

| Phase | Participant sees |
|---|---|
| none (`ROUND_NOT_FOUND`) | "Event has not started yet" |
| `PREVIEW` | Problem cards (title, category, tier, teaser, slots, reserve price). No bidding |
| `BIDDING` | Sealed bid screen: amount input per problem, countdown to `biddingDeadline`, remaining balance after bids. Paused → read-only banner |
| `SETTLEMENT` | "Bidding closed, results coming" |
| `SOLVING` | Own results: won problems with HackerRank link and price paid; lost/unaffordable bids |
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
