# terminal-plugin

SCiPNET terminals for SCP roleplay servers (Paper 1.21.5+): a placeable
computer block with a custom 3D model, a LuckPerms-backed login screen, and a
clearance-gated entry database written by the players themselves.

Part of the SCP server family: [lab-datapack](https://github.com/alavesa/lab-datapack) ·
[labra-plugin](https://github.com/alavesa/labra-plugin) ·
[scp-mobs-plugin](https://github.com/alavesa/scp-mobs-plugin) ·
[scp914-plugin](https://github.com/alavesa/scp914-plugin) ·
[idcards-plugin](https://github.com/alavesa/idcards-plugin)

## The terminal

A glowing CRT + keyboard, built with the house spawner-model method (marker
anchor + item display + interaction box). Placement is **directional**: the
screen turns toward whoever placed it, snapped to 90°.

- `/terminal give [player]` — hand out the placeable terminal item.
  Allowed for **ops, and for any player in creative mode** (a plugin cannot
  add items to the real creative menu; this is the Paper-shaped equivalent).
- Place it like a block — the block placement becomes a machine spawn.
- `/terminal place` — spawn one on the block you are looking at.
- `/terminal remove` — remove the nearest terminal (ops).

## Logging in

Right-click the terminal: the SCiPNET login shows your name, your LuckPerms
rank prefix and your clearance (`/lp user <name> meta set clearance 3` — the
same meta the ID cards use). **LOG IN** opens the entry database:

- Entries at or below your clearance open as books.
- Entries above it show as **ACCESS DENIED** with the required level.
- **NEW ENTRY** (if your clearance allows writing) hands you a draft book —
  write it, **sign it**, and the signed title and pages are filed as an entry
  at your own clearance level.

## Redactions

Authors can black out passages inside their text:

| you write | who sees it |
|---|---|
| `[[the truth]]` | only the author (and ops) |
| `[[3:the truth]]` | the author + anyone Level 3 and up |

Everyone else sees a black bar of the same length: `████████`. Authorized
readers see the passage in dark red, document-style.

## The database console (ops)

`/terminal admin` — manage the database entirely in-game:

- click an entry: **left** raises its clearance, **right** lowers it,
  **shift+right EXPUNGES** it
- the repeater at the bottom sets the minimum clearance required to write
  new entries (left/right to adjust)

Storage is `plugins/Terminal/entries.yml` — plain YAML if you ever want to
edit it by hand, but the console is the intended interface.

## Install

1. `Terminal-x.y.z.jar` → `plugins/`
2. The combined resource pack `scp_and_chemistry.zip`
   ([always-current download](https://github.com/alavesa/lab-datapack/releases/tag/pack-latest))
   — the terminal model ships in it.
3. LuckPerms recommended (without it everyone is "Personnel", Level 0).

## License

MIT
