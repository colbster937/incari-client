# ADR 0059: Incari chat: friends, messages and the chat menu

- Status: accepted
- Date: 2026-10-12
- Builds on: ADR 0058 (accounts and the online service)

## Context

ADR 0058 left friends, messages, and where blocking and reporting happen in a world for this ADR.
The client's documents set the wire format.

Decided by the maintainer:

- **One place for people: the Incari chat menu**, from a key or a button below the chat box.
- **Laid out like Discord:** people on the left, the conversation on the right.
- **Friends always above other Incari players**, and styled differently.
- **Messages and friend requests can each be turned off completely.**
- **Block, Add Friend, Accept and the like are easy to reach.**
- **No age gate:** no real names, active moderation, free blocking. An age question only teaches
  people to type a higher age.

## Decision

### The chat menu

- **Opens with the Friends key** (O) and an **Incari Chat** button below the chat box (with an
  unread count). Mojang's Friends overlay needs a Microsoft account, so the key was dead; one patch
  hands it to `ChatMenu.toggle`. The button is a `ScreenEdits` edit, no patch.
- **Left bar:** Add Friend; **Friend Requests** (Accept and Decline on the row, Cancel for sent);
  **Friends** (online first, presence dot, server if shared); **On This Server** (other Incari
  players, with skin faces, below every friend); your own strip with Chat Settings.
- **Conversation:** a header with labelled Unfriend/Add Friend, Block (second click within 4 s),
  Report. Messages grouped by sender, Discord-style. Sending shows dimmed; refused shows red "Not
  delivered". Scroll up for older pages.
- **Chat Settings:** who can message you, friend requests on/off, sharing your server, Blocked
  Players, account.
- **Signed out**, it explains accounts and offers Sign In. If the service lacks friends support, it
  says so; blocking and reporting still work.
- **Toasts** for messages and requests while the menu is closed.

### Friends

- **Requests go to a username, or to an Incari player on your server** (by `pid`). Usernames are
  shared only between friends; sending a request shares yours.
- **A request to someone with requests off, or across a block, answers `ok` and is never
  delivered.** Unknown usernames are `bad_request`.
- **Crossing requests make a friendship.** Friends see each other's usernames, online state, and
  server if shared. **Blocking a friend ends the friendship** on both sides.

### Messages

- **Who can message you:** `everyone` (friends and Incari players on your server), `friends`
  (default), or `nobody`. Refused or blocked messages both answer `closed`, indistinguishably.
- **Friends' messages are kept 30 days** and delivered later, with unread counts and history pages.
- **Same-server messages** go by `pid` and live only in the client while both are there (the service
  keeps them for reports, never as history).
- **≤ 500 characters**, trimmed. Shown at once as sending, then sent or failed.

### Code

`:online`'s `Social` and `OnlineService` requests (`FakeService` answers them); `client`'s
`ChatMenuScreen`, `ChatMenu`, `ChatToast`, `Avatars`, and new icons. Wire format in
`docs/online-protocol.md` (new types only; older services answer `bad_request`).

## Consequences

- With messages `nobody` and requests off, nobody can reach the player through Incari; they can
  still reach out themselves.
- A `pid` conversation can't continue after either player leaves the server.
- The Friends key opens Incari Chat; Mojang's friends screen never worked without a Microsoft account.
- One more patch.
- Tests: `SocialTest`; tried on the desktop against `tools/onlinedev` with scripted peers;
  `smokeOnlineA` + `smokeOnlineB` end to end (see each other, `pid` request, messages, a block, accounts deleted).

## Open

- Group conversations, read receipts and typing indicators aren't planned.
- Unread counts for same-server players live only in the client.
