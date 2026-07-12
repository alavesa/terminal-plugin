# Reskinning the terminal

Everything the terminal looks like lives in the resource pack under the
`terminal` namespace - repaint a PNG, rebuild the combined pack, done.

## The 3D model's textures

`resource-pack/assets/terminal/textures/entity/`

| file | what it paints |
|---|---|
| `terminal_case.png` | monitor shell, desk plate |
| `terminal_screen.png` | the glowing display (drawn fullbright + shade off) |
| `terminal_keys.png` | keyboard top |
| `terminal_dark.png` | stand, undersides |
| `terminal_vent.png` | the monitor's back |

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
/Users/piia/Lab/tools/build-pack.sh
```
