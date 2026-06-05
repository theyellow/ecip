# Design Handoffs — EMCIP Admin UI

Stable index of design handoffs from Claude Design. Check here before starting any frontend work to see if there are pending specs.

Each handoff ships as a directory inside `emcip-admin-ui/` containing a `README.md`, a `CLAUDE.md` (drop into repo root to apply), and `design_references/` with token CSS and JSX prototypes.

---

## Handoff queue

| Directory | Topic | Status |
|---|---|---|
| `design_handoff_emcip_admin/` | Admin UI v2: Reply composer (Flags), domain glossary (Watched Group / Watcher / Role), Teal replaces Violet, Watched Groups nav rename, Roles page planned, SpaceBackground v3 description | ✅ Applied — `emcip-admin-ui/CLAUDE.md` + skill updated |

---

## Discipline for Claude Design sessions

Before producing a handoff:

1. **Read this repo's `emcip-admin-ui/CLAUDE.md`** — paste it at session start or fetch it via `github_read_file`.
2. **Run `github_get_tree`** on any directory before writing file paths — never assume the structure.
3. **Read `emcip-admin-ui/src/main/frontend/src/theme/variables.css`** before touching tokens — it is the master. Handoffs may add new tokens but must not restate existing ones with different values.
4. **Read the page file(s) you are speccing** — never invent existing content.
5. **Read `Sidebar.jsx` and `permissions.js`** when adding or changing nav items.

---

## How to apply a handoff

1. Drop the handoff's `CLAUDE.md` into `emcip-admin-ui/` (overwrite the existing one after diffing).
2. Update `emcip-admin-ui/.claude/skills/emcip-admin-ui.md` if the skill section changed.
3. Implement each page/component using production patterns — not the prototype's literal code.
4. Run `npm test -- --run` after all changes.
5. Update this table: change status to `✅ Applied` with a note of what was changed.
