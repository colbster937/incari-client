# Security

## Reporting a vulnerability

Please report security problems privately, through GitHub's
[private vulnerability reporting](https://github.com/incari-client/incari-client/security/advisories/new),
not in a public issue or discussion.

Tell us what's affected, how to reproduce it, and what an attacker could do with it. We'll reply
within a week. Please give us reasonable time to fix it before you talk about it publicly.

## What's in scope

- The client: anything a server, another player, a resource pack, a shared world or a crafted page
  address can do to a player's machine, browser storage or account.
- Incari accounts and the online service's protocol ([`docs/online-protocol.md`](docs/online-protocol.md)):
  sign-in, sessions, recovery codes, friends, messages and blocking. The service itself is closed
  source; report problems with it the same way.
- The build tool and CI: anything that could put Mojang content, secrets or builds into the
  repository, or run a fork's code on a maintainer's machine.

Out of scope: problems in Minecraft itself, in ViaVersion or TeaVM (report those upstream), and in
builds other people host. The project publishes no builds.

## Supported versions

Only the latest commit on `main` gets fixes.
