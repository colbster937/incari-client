# ADR 0058: Incari accounts and the online service (client side)

- Status: accepted
- Date: 2026-10-12

## Context

Friends, messages, cosmetics and seeing who else uses Incari need an identity that lasts across
servers. A Minecraft name can't be it: on offline-mode and Eagler servers anyone can take any name.

Incari runs its own online service. Its server is closed source and not in this repository; the
protocol is public ([`docs/online-protocol.md`](../online-protocol.md)). This ADR is the client side.

Already decided: accounts are **optional**; a **username and password** only (no email or personal
data); **recovery codes** instead of email; cosmetics belong to the account; players can block.

Constraints: many players are students playing at school, so collect little and never let strangers use the
service to find someone. Schools may block the service but not game servers, and the game mustn't
notice. The page may run from `file://` (origin `null`). The browser has only WebSockets.

## Decision

### Connection

- **One `wss://` connection**, opened after the title screen on the background pool, never holding
  up a join. JSON text frames with numbered requests, answers and events. The first message each way
  states the protocol version.
- **URL set at build time** (`incari.online.url`; empty turns accounts off). No keys or secrets in
  the client.
- **Back-off:** 1 s doubling to 5 minutes with jitter, reset after a minute connected. Offline, the
  game carries on; nothing waits on the service.

### Accounts

- **Sign-up:** username (3–16 of `[A-Za-z0-9_]`, unique ignoring case, separate from any Minecraft
  name), password (10+ characters, not a common one), and a **proof of work** sized for about a
  second on the reference Chromebook, solved on the background pool, in place of a CAPTCHA.
- **Recovery codes:** ten at sign-up, shown once with Copy and Download, and the player confirms
  saving them. One code plus a new password resets it. No password and no code: the account is
  lost, and the screens say so.
- **Sign-in** returns a session token, stored in `incari/online/session.json` (never the password).
- **Account screen** from the title screen's player card (not a module): Sign In, Create Account,
  Forgot Password; signed in, Change Password, New Recovery Codes, Sign Out, Sign Out Everywhere,
  Delete Account. Password fields are starred whole (`HiddenPasswords`).
- **Errors are general:** a failed sign-in says only "wrong username or password", and rate limits
  look the same, so usernames can't be probed.

### Presence and lookups

- **Joining a world** reports a `serverHash` (SHA-256 of the normalized address, or a relay code),
  the Minecraft name there, and the plain address only if "Show friends which server I'm on" is on
  (default on; friends only). Singleplayer sends nothing. Leaving clears it.
- **Lookups:** the client sends tab-list names; the service answers only for names an account has
  reported on the asker's own `serverHash` right now, with a per-server id (so accounts can't be
  followed between servers), friend status and cosmetics. Two accounts claiming one name get nothing.
- **Modules see it** through `ModuleSystem.online()` (`incari.framework.online.Online`): signed in,
  account name, `incariPlayer(UUID)`, friends, events.

### Blocking and reporting

- **Blocks are on accounts** and **enforced by the service**: no requests, no messages, neither shown
  to the other as an Incari player. The blocked player isn't told.
- **Reports** carry a reason; the service keeps the reported account's recent messages to the reporter.

### Code

- **`:online`** (depends on `platform-api`, `framework`): connection, protocol, session store, proof
  of work, presence and lookups. No game types; tested against an in-process fake.
- **`client`'s `incari.client.online`:** the screens, and a tick that reports joins and reads the
  tab list, so no patch is needed.
- **`framework`'s `incari.framework.online`:** the `Online` view, events, a test fake.

## Consequences

- With the service blocked, the game is unchanged apart from "offline" on the account screen.
- The service learns, per signed-in player: username, server hashes and names there, the plain
  address if shared, friends, blocks, messages, and connection IP. Its privacy notice says how long.
- `serverHash` keeps plain addresses out of storage and logs; it isn't protection against someone
  with the database and a list of popular servers.
- Different addresses for one network split players into groups that can't see each other (accepted).
- A stored token is readable by anything in the page's origin. The page loads no third-party
  scripts, and must stay that way. Sign Out Everywhere is the remedy.
- The service must accept origin `null` for offline HTML builds; it yields nothing without credentials.

## Built (2026-10-12)

- `:online`: `OnlineService`, `WorldPlayers`, `ServerHash`, `ProofOfWork` (4,096 nonces a step, no
  allocation), `SessionStore`, `Backoff`, `Credentials`, `RecoveryCodes`.
- `incari.framework.online`: `Online`, `IncariPlayer`, `FakeOnline`.
- `incari.client.online`: the account screens on one `FormScreen`.
- `tools/onlinedev` serves the fake service on `ws://127.0.0.1:8090/v1/ws` for trying the screens.
- `smokeOnlineA` + `smokeOnlineB`: two clients make accounts, see each other, go through friends,
  messages and blocking (ADR 0059), then delete their accounts. Passed against Incari's service.
- Since built: the Incari badge (ADR 0062).
- Friend requests from the tab list were settled by ADR 0059 (by per-server id).
