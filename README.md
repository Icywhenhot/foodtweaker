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
| `attribute_modifiers` | **Permanent** stat changes applied when eaten (e.g. eat a golden apple once for +1 heart forever). Saved with the player; removable with `/foodtweaker reset` |

It can also **turn a non-food item into food** (e.g. make a stick edible) — just give it at least
`nutrition` and `saturation`.

Each field is optional: omit one and the item keeps its current value. This means you can tweak
just the eat time of a modded steak and leave everything else alone.

---

## What it cannot do

- It does **not** change non-food properties (stack size, durability, cooldowns, crafting, etc.).
- It only edits the item's **food data**. The "leftover" item from eating (e.g. a bowl from stew) is
  preserved automatically but cannot currently be changed.
- Attribute modifiers are granted **per player, on eating** — they are not a property of the item,
  so they don't show up in the item's tooltip.
- It must be installed on **both the server and the client**. On 1.20.1 food data lives on the item
  itself and is never synced, so a vanilla client will mispredict: eat animations run at the wrong
  length, and items this mod made edible won't animate at all. Everything still resolves correctly
  server-side, but the client experience is wrong without the mod present.

---

## How it works (under the hood)

When a world/server is about to start (all mods are loaded and the registries are frozen by then),
FoodTweaker reads its config and, for each listed item, builds a new `FoodProperties` and swaps it
onto that item. Unlisted items are never touched, and a reload first reverts everything it
previously changed before re-applying — so removing an entry restores the item to vanilla/original
behavior. Because 1.20.1 stores food data on the `Item` rather than on the stack, changes take
effect immediately on every existing stack — no need to re-`/give` anything.

Attribute modifiers work differently, since they apply to the *player* rather than the item. The
mod watches for a completed eat and adds a permanent modifier to the player's attribute map;
vanilla saves permanent modifiers into the player's NBT, so they survive relogs and restarts with
no extra save data of our own. Every modifier is named `FoodTweaker: <item id>`, which is what
`/foodtweaker reset` matches on when taking them back.

There are **no mixins**. The mod only uses reflection on a single vanilla field plus Architectury
events, which keeps it about as conflict-free as a mod that rewrites item data can be.

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
  ],
  "attribute_modifiers": [   // optional; PERMANENT stat changes granted on eating
    {
      "id": "minecraft:generic.max_health", // vanilla or modded attribute id
      "amount": 2.0,                        // for max_health, 2.0 = one heart
      "operation": "addition",              // addition | multiply_base | multiply_total
      "stacks": false,                      // false = grant once ever; true = every meal
      "max_total": 8.0                      // optional cap when stacks is true
    }
  ]
}
```

### Attribute modifiers

These are **permanent and saved with the player** — they are not status effects and they do not
expire. Treat them as progression rewards, not seasoning.

| Field | Default | Meaning |
|-------|---------|---------|
| `id` | *(required)* | Attribute id. Vanilla ones are namespaced with a `generic.`/`player.` prefix, e.g. `minecraft:generic.max_health` |
| `amount` | `0` | How much to grant per meal |
| `operation` | `addition` | `addition`, `multiply_base`, or `multiply_total` — same meaning as vanilla equipment modifiers |
| `stacks` | `false` | `false` grants the bonus **once ever**, no matter how often the food is eaten. `true` accumulates on every meal |
| `max_total` | *(none)* | Caps the accumulated amount when `stacks` is `true` |

Commonly useful ids: `minecraft:generic.max_health`, `minecraft:generic.movement_speed`,
`minecraft:generic.attack_damage`, `minecraft:generic.attack_speed`, `minecraft:generic.armor`,
`minecraft:generic.armor_toughness`, `minecraft:generic.knockback_resistance`,
`minecraft:generic.luck`. Modded attribute ids work too.

```json
"minecraft:golden_apple": {
  "attribute_modifiers": [
    { "id": "minecraft:generic.max_health", "amount": 2.0 }
  ]
},
"minecraft:cooked_beef": {
  "attribute_modifiers": [
    { "id": "minecraft:generic.max_health", "amount": 1.0, "stacks": true, "max_total": 8.0 }
  ]
}
```

The first grants exactly one extra heart, however many golden apples get eaten. The second grants
half a heart per steak, up to +4 hearts total.

Because `stacks: true` is permanent and irreversible from the player's side, always set a
`max_total` on it unless you genuinely want unbounded growth.

To take the bonuses back — after a config change, a balance pass, or a mistake:

```
/foodtweaker reset [<players>]
```

With no argument it resets the player running it. It removes **every** modifier FoodTweaker has
ever granted, including ones from config entries you have since deleted.

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

Either of these re-reads the file and re-applies it (both require permission level 2 / cheats):

```
/foodtweaker reload
```

```
/reload
```

`/reload` works because FoodTweaker registers a server-data reload listener, so the config rides
along with your datapack reloads. `/foodtweaker reload` does the same thing and additionally
reports **in chat** if the JSON failed to parse — worth using while you are actively editing,
because a malformed config silently falls back to applying nothing.

Changes to food stats apply to items you are already holding. Attribute modifiers already granted
to players are *not* revoked by a reload; use `/foodtweaker reset` for that.

### Finding item & effect ids

Press **F3 + H** in-game to enable advanced tooltips — every item then shows its id. Recipe-viewer
mods (JEI / REI) also display ids.

---


### Mod icon

```
common/src/main/resources/assets/foodtweaker/icon.png
```

It will be bundled into both the Fabric and NeoForge jars automatically.
