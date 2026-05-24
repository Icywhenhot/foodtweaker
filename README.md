# FoodTweaker

A lightweight, config-driven utility mod for modpack developers. It lets you override the food
stats of **any item — vanilla or modded** — from a single JSON file, without writing code or
touching the original mod.

- **Loaders:** Fabric and Forge (built with [Architectury](https://docs.architectury.dev/))
- **Minecraft:** 1.20.1
- **Author:** icywhenhot

> FoodTweaker changes **nothing** on its own. It only does something once you add entries to its
> config, so it's safe to ship in a pack and configure later.

---

## Requirements

| Loader | Required mods |
|--------|---------------|
| Fabric | Fabric API, Architectury API (≥ 9.2.14) |
| Forge | Architectury API (≥ 9.2.14) |

---

## What it can do

For any item id you list, you can override:

| Field | What it changes |
|-------|-----------------|
| `nutrition` | Hunger points restored (2 = one half-drumstick) |
| `saturation` | Saturation **modifier** (same meaning as vanilla). Saturation gained = `nutrition × saturation × 2` |
| `can_always_eat` | If `true`, the item can be eaten on a full hunger bar (like golden apples) |
| `eat_seconds` | Eat speed. On 1.20.1 there are only two speeds: a value of `0.8` or less marks the food as **fast** (eaten in ~0.8s, like dried kelp); anything higher is normal speed (~1.6s) |
| `effects` | Status effects applied when eaten — **works with vanilla *and* modded effects** (e.g. pufferfish-style Hunger/Poison/Nausea, or a modded buff) |

It can also **turn a non-food item into food** (e.g. make a stick edible) — just give it at least
`nutrition` and `saturation`.

Each field is optional: omit one and the item keeps its current value. This means you can tweak
just the eat time of a modded steak and leave everything else alone.

---

## What it cannot do

- It does **not** add permanent attribute modifiers (like a permanent +max-health on eat). The
  "pufferfish attributes" use case is covered because those are **status effects**, which are fully
  supported — but lasting stat changes are a different system and are out of scope.
- It does **not** change non-food properties (stack size, durability, cooldowns, crafting, etc.).
- It only edits the item's **food data** (the `minecraft:food` component). The "leftover" item from
  eating (e.g. a bowl from stew) is preserved automatically but cannot currently be changed.
- **Existing item stacks don't retro-update.** Minecraft snapshots an item's data when the stack is
  created, so items already sitting in inventories/chests keep their old stats. Newly obtained
  copies (e.g. via `/give`, crafting, or loot) use the new values. After `/foodtweaker reload`,
  grab a fresh copy to see changes.
- It is effectively **server-side**: the change is applied where the world runs. In multiplayer,
  install it on the server; connected clients receive the updated stats automatically.

---

## How it works (under the hood)

When a world/server is about to start (all mods are loaded and the registries are frozen by then),
FoodTweaker reads its config and, for each listed item, builds a new `FoodProperties` and swaps it
onto that item's default data components. Unlisted items are never touched, and a reload first
reverts everything it previously changed before re-applying — so removing an entry restores the
item to vanilla/original behavior.

If an item id or effect id can't be found (e.g. that mod isn't installed, or a typo), FoodTweaker
logs a clear warning and **skips only that entry** — it never crashes the game.

---

## Configuration

The config file is created automatically on first launch at:

```
<game folder>/config/foodtweaker.json
```


### Top-level options

| Key | Default | Meaning |
|-----|---------|---------|
| `enabled` | `true` | Master switch. `false` reverts everything to vanilla. |
| `logChanges` | `true` | Log each applied override to the game log. |
| `foods` | `{}` | The map of `item id → overrides`. Empty = no changes. |

Any key starting with `_` is ignored, so you can keep comments and the bundled `_example` block
right inside the file.

### Per-food fields

```jsonc
"<namespace>:<item>": {
  "nutrition": 8,            // int
  "saturation": 1.0,         // float (modifier)
  "can_always_eat": true,    // bool
  "eat_seconds": 0.8,        // float
  "effects": [               // optional; if present, REPLACES the item's effects
    {
      "id": "minecraft:absorption", // vanilla or modded effect id
      "amplifier": 0,               // 0 = level I, 1 = level II, ...
      "duration_seconds": 60,       // float
      "probability": 1.0,           // 0.0..1.0 chance to apply
      "ambient": false,             // beacon-style softer particles
      "show_particles": true,
      "show_icon": true
    }
  ]
}
```

### Example

```json
{
  "enabled": true,
  "logChanges": true,
  "foods": {
    "minecraft:apple": {
      "nutrition": 8,
      "saturation": 1.0,
      "can_always_eat": true,
      "eat_seconds": 0.8,
      "effects": [
        { "id": "minecraft:absorption", "amplifier": 0, "duration_seconds": 60 }
      ]
    },
    "minecraft:stick": {
      "nutrition": 2,
      "saturation": 0.1
    },
    "somemod:super_steak": {
      "nutrition": 20,
      "saturation": 20.0,
      "can_always_eat": true,
      "effects": [
        { "id": "minecraft:regeneration", "amplifier": 1, "duration_seconds": 10 },
        { "id": "minecraft:hunger",       "amplifier": 0, "duration_seconds": 15, "probability": 0.5 }
      ]
    }
  }
}
```

### Reloading without a restart

Run this in-game (requires permission level 2 / cheats):

```
/foodtweaker reload
```

It re-reads the file and re-applies it. Remember: grab a **fresh** copy of an item afterwards to see
the new stats.

### Finding item & effect ids

Press **F3 + H** in-game to enable advanced tooltips — every item then shows its id. Recipe-viewer
mods (JEI / REI) also display ids.

---


### Mod icon

```
common/src/main/resources/assets/foodtweaker/icon.png
```

It will be bundled into both the Fabric and NeoForge jars automatically.
