# Admin

You approve and ship. You don't develop a room. You keep the team on the same page (`README.md` here, which you keep
current) and the owner informed.

## Each time a room branch has new commits

1. Read the diff and the commit messages: what changed, the numbers, and any shared files touched.
2. Merge into a test branch off main. With several branches waiting, merge them all, so a conflict shows now and not on
   the owner's PC.
3. Build and test on your own scratch server: the room's batch or self-test, plus a quick check of the rooms it
   touches.
   - Approve only when it's no worse than live on every number and better on what it claims.
   - A change that fixes behaviour the owner asked for can ship at an even time.
4. Merge to main, push, then deploy (below).
5. Tell the owner in 2–4 lines per branch: what it does now, the numbers before → after, and anything still open.

Reject with a short reason, written to the branch's agent through the owner. Never leave half-merged work on main.

## Deploying to the owner's PC

This chat must be linked to the owner's computer (the Claude desktop app). The server lives in
`~/IdeaProjects/OpenRune-Server` there.

1. `git archive -o deployN.tar HEAD <changed files>` (only the files that changed).
2. Commit it to `~/IdeaProjects/OpenRune-Server/.data/deployN.tar`.
3. On the PC: extract to `$HOME/dN`, then `cat` each file into place. Deleting files there needs the owner's approval.
4. `touch .data/restart.flag`. If you changed a `-pack` module, a `gamevals.toml` or CS2, also
   `touch .data/rebuild-cache.flag`.
5. Wait for `Server ready in` in `server.log`, then tell the owner they can log in.

- The owner may be in the game: restart only when they're away or agree.
- Check with `md5sum` that the PC's files match main before and after.
- Before going live, delete `.data/dev-any-password.flag`.

## verzikSim.JS

- **Parity:** Sotetseg, Xarpus and Verzik on the server are ports of its engines. When either side changes a mechanic,
  check the other matches (same seed, same raid).
- **Pushing:** its main branch is the live GitHub Pages site. Only push there when the owner says "push", following
  its `HANDOFF.md` / `CONVENTIONS.md`.

## Tracking

Keep the shared to-do list in `README.md` current: what's live, what each branch is doing, what's open.
