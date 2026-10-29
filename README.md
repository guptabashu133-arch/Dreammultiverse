# Dreammultiverse

Multiverse-Core, Multiverse-Portals, Multiverse-NetherPortals, Multiverse-Inventories
and Multiverse-SignPortals in **one plugin**, for **Paper, Folia and Spigot** (1.21.x, Java 21).

Build: `mvn package` -> `target/Dreammultiverse.jar`

## Folia

Everything runs on Folia's region threads (teleports with `teleportAsync`, block changes on
the owning region, per-player tasks on the player's own scheduler). Folia itself refuses to
create, load or unload worlds **while the server is running**, so on Folia:

- **`/mv create` still works, but the world appears after a restart.** Folia (like Paper) loads
  every datapack dimension at startup as a real world, so Dreammultiverse writes the new world
  into `<level-name>/datapacks/dreammultiverse/`. After the restart it is a normal world named
  `<level-name>_dmv_<name>` with `<name>` as its alias - `/mv tp spawn`, `/mv modify spawn ...`
  all work with the short name. `-t void`, `-t flat`, `large_biomes`, `amplified`, nether/end and
  `-g Plugin` generators (via `bukkit.yml`) are supported; these worlds share the main world's seed.
- `/mv delete` on such a world removes it at the next restart.
- `/mv regen` resets terrain on the next restart.
- Not possible on Folia: `/mv import`, `/mv load`, `/mv unload`, `/mv clone`.

Example (Folia):
```
/mv create spawn normal -t void
/mv create duels normal -t void
(restart the server)
/mv tp spawn      -> paste your build, /mv setspawn
```

Folia doesn't fire `PlayerChangedWorldEvent`, `PlayerRespawnEvent` or `PlayerPortalEvent`, so
Dreammultiverse watches world changes, respawns and portals itself there.

## Commands

### `/mv` (Core) - aliases `/dmv`, `/dreammultiverse`
| Command | What it does |
|---|---|
| `/mv create <name> <normal\|nether\|end> [-s seed] [-g generator[:id]] [-t normal\|flat\|void\|large_biomes\|amplified] [-n] [-a]` | New world (`-n` no structures, `-a` don't adjust spawn, `-t void` completely empty) |
| `/mv import <folder> <env> [-g generator]` | Load an existing world folder |
| `/mv load <world>` / `/mv unload <world> [--nosave]` | Load / unload |
| `/mv remove <world>` | Stop managing it (files kept) |
| `/mv delete <world>` | Delete world + files (needs `/mv confirm`) |
| `/mv clone <world> <new>` | Copy a world |
| `/mv regen <world> [-s seed \| --random] [--keep-gamerules]` | Regenerate (main world / Folia: on next restart) |
| `/mv list [page]`, `/mv info [world]`, `/mv who [world]`, `/mv coord` | Info |
| `/mv tp [player] <destination>` | Teleport (see destinations) |
| `/mv spawn [player]`, `/mv setspawn [world x y z [yaw pitch]]` | Spawns |
| `/mv modify <world> set\|reset\|add\|remove\|clear\|list [property] [value]` | World settings |
| `/mv gamerule <rule> <value> [world]`, `/mv gamerules [world]` | Gamerules |
| `/mv purge [world\|all] [all\|animals\|monsters\|<entity>]` | Remove mobs (named/tamed kept) |
| `/mv anchor <name>`, `/mv anchor -d <name>`, `/mv anchors` | Saved locations |
| `/mv bed` | Go to your bed / respawn anchor |
| `/mv generators`, `/mv environments` | Generator plugins, env/types |
| `/mv migrate multiverse` | Import worlds from `plugins/Multiverse-Core/worlds.yml` |
| `/mv config <key> [value]`, `/mv reload`, `/mv version`, `/mv confirm` | Admin |

Shortcuts: `/mvtp`, `/mvspawn`, `/mvsetspawn`, `/mvlist`, `/mvwho`, `/mvcoord`, `/mvconfirm`.

**World properties** (`/mv modify`): `alias`, `gamemode`, `difficulty`, `pvp`, `animals`, `monsters`,
`animalexceptions`, `monsterexceptions`, `hunger`, `autoheal`, `allowflight`, `weather`, `playerlimit`,
`entryfee`, `currency` (`money` via Vault, or an item like `DIAMOND`), `respawnworld`, `bedrespawn`,
`adjustspawn`, `autoload`, `hidden`, `portalform` (`all/nether/end/none`), `scale`, `blacklist`.

### Empty void worlds (built in - no VoidGen needed)
`/mv create spawn normal -t void` makes a world with **no blocks at all** (no bedrock, mobs or
structures), ready for pasting a spawn/lobby/arena with WorldEdit:

1. `/mv create spawn normal -t void`
2. Go into creative, `/mv tp spawn` (you arrive flying at 0, 64, 0)
3. `//paste` your build, stand on it, `/mv setspawn`

Pick the biome with `-g void:<biome>`, e.g. `-g void:the_void` (default `plains`, so grass and
leaves keep their normal colours). Until a spawn exists, players who can't fly are not sent there
(they'd fall into the void).

The generator is also registered as a normal Bukkit generator called `Dreammultiverse`, so it works
from `bukkit.yml` too - including for **Folia**, where it's the only way to get a void world
(set it before the world is first generated):
```yaml
worlds:
  spawn:            # = level-name in server.properties
    generator: Dreammultiverse:void
```

### Destinations
| Format | Meaning |
|---|---|
| `world` or `w:world` | World spawn (name or alias) |
| `e:world:x,y,z[:pitch:yaw]` | Exact location |
| `pl:player` | Another player |
| `a:anchor` | An anchor |
| `b:` / `b:player` | Bed / respawn anchor |
| `p:portal` | A portal's exit |
| `ca:world:x,y,z:pitch:yaw:speed` | Cannon - teleport then launch |

### `/mvp` (Portals)
`wand`, `pos1`, `pos2`, `create <name> [dest]`, `remove <name>`, `list [world]`, `info <name>`,
`select <name>`, `modify <name> dest|price|currency|safe|owner|exit|region [value]`,
`fill <name> <water|lava|nether_portal|end_gateway|air|...>`.
Left-click with the wand (wooden axe) = position 1, right-click = position 2.

### `/mvnp` (Nether portals)
`link <nether|end> <from> <to|none>`, `unlink <nether|end> <from>`, `show`.
Without a link, `name` <-> `name_nether` and `name` <-> `name_the_end` are paired automatically.

### `/mvinv` (Inventories)
`create <group> [worlds...]`, `delete <group>`, `addworld <group> <world>`, `removeworld <group> <world>`,
`share <group> <sharable|all>...`, `unshare <group> <sharable|all>...`, `list`, `info <group|world>`,
`gamemode <true|false>` (separate inventories per gamemode), `reload`.
Sharables: `inventory`, `armor`, `offhand`, `ender_chest`, `health`, `hunger`, `experience`, `potions`, `bed_spawn`.
On first start the server's own overworld/nether/end are put in a `default` group that shares everything.

### Sign portals
Line 1 `[mv]`, lines 2-3 the destination. Right-click to use, or power it with redstone to send
everyone standing next to it.

## Permissions
| Node | Default |
|---|---|
| `dreammultiverse.admin` - everything below except bypasses | op |
| `dreammultiverse.command.<sub>` (e.g. `.create`, `.tp`, `.modify`) | op (`spawn`, `list`, `who`, `coord`: everyone) |
| `dreammultiverse.teleport.self.<w\|e\|pl\|a\|b\|p\|ca>` / `.other.<type>` | op |
| `dreammultiverse.access.<world>` (when `enforce-access: true`) | op |
| `dreammultiverse.exempt.<world>` - no entry fee | op |
| `dreammultiverse.bypass.playerlimit`, `.bypass.blacklist` | op |
| `dreammultiverse.bypass.gamemode`, `.bypass.flight` | nobody |
| `dreammultiverse.portal.create/remove/modify/list` | op |
| `dreammultiverse.portal.access.<portal>` (when `portals.enforce-access: true`) | everyone |
| `dreammultiverse.portal.exempt.<portal>` - free paid portals | op |
| `dreammultiverse.netherportals.link/show` | op |
| `dreammultiverse.inventory.group/info` | op |
| `dreammultiverse.inventory.bypass` - never swap inventories | nobody |
| `dreammultiverse.signportal.create` / `.use` | op / everyone |

## Placeholders (PlaceholderAPI)
`%dreammultiverse_world%`, `_world_alias`, `_world_gamemode`, `_world_difficulty`, `_world_pvp`,
`_world_players`, `_world_limit`, `_world_environment`, `_world_seed`, `_world_fee`, `_world_group`,
`_worlds_loaded`, `_worlds_total`, `_players_<world>`, `_alias_<world>`.

## Files
`config.yml`, `worlds.yml`, `anchors.yml`, `portals.yml`, `netherportals.yml`,
`inventories/groups.yml`, `inventories/players/<uuid>.yml`, `pending-resets.yml`.
All writes happen on a background thread, in order.

Don't run this together with the real Multiverse plugins - the `/mv` commands would clash.
Use `/mv migrate multiverse` to bring your Multiverse worlds over.
