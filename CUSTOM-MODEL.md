# Reskinning the terminal

Everything the terminal looks like lives in the resource pack under the
`terminal` namespace - repaint a PNG, rebuild the combined pack, done.

## The 3D model's textures

`resource-pack/assets/terminal/textures/entity/`

| file | what it paints |
|---|---|
| `terminal_case.png` | monitor shell, desk plate |
| `terminal_screen.png` | the display while someone is LOGGED IN (fullbright) |
| `terminal_screen_off.png` | the display while idle - dark glass |
| `terminal_keys.png` | keyboard top |
| `terminal_dark.png` | stand, undersides |
| `terminal_vent.png` | the monitor's back |

The on/off swap is automatic: the plugin switches the display between the
`terminal` and `terminal_off` models (and full/ambient brightness) when the
first user logs in and the last one leaves.

> **Why textures turn black/magenta.** Block and item models can only use
> textures that are stitched into Minecraft's *blocks atlas*, and by default
> the atlas only collects `textures/block/` and `textures/item/`. Pointing a
> model at any other folder gives the missing-texture look even though the
> path is "correct". This pack registers `textures/entity/` into the atlas
> (`assets/minecraft/atlases/blocks.json`), so every namespace's
> `textures/entity/` works for models - drop your own Blockbench textures
> there. If you maintain a separate pack, copy that atlas file into it.

All 16x16. Keep the filenames - the model
(`resource-pack/assets/terminal/models/entity/terminal.json`) points at them
by name. The model itself is plain Blockbench-compatible JSON: open it in
Blockbench (Generic Model), edit, save back, and it keeps working as long as
the texture keys (`#case`, `#screen`, ...) survive.

`tools/gen_textures.py` regenerates the default set if you ever want to
start over.

## The GUI overlays

`resource-pack/assets/terminal/textures/font/` - the SCiPNET panels drawn
over the chest/anvil GUIs (`gui_chest54.png`, `gui_chest27.png`,
`gui_anvil.png`). Regenerate or restyle with `tools/gen_gui.py`; sizes and
the font metrics in `assets/terminal/font/gui.json` must stay paired
(ascent 13, height = texture height).

## After any texture change

Rebuild and publish the combined pack (prints the new sha1 for
server.properties):

```
~/Lab/tools/build-pack.sh
```
