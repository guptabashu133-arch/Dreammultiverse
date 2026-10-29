# Dreammultiverse

Multiverse-Core, Multiverse-Portals, Multiverse-NetherPortals, Multiverse-Inventories
and Multiverse-SignPortals in **one plugin**, for **Paper, Folia and Spigot** (1.21.x, Java 21).

Build: `mvn package` -> `target/Dreammultiverse.jar`

## Folia

Everything runs on Folia's region threads (teleports with `teleportAsync`, block changes on
the owning region, per-player tasks on the player's own scheduler). One limit comes from
Folia itself, not from this plugin: **Folia refuses to create, load or unload worlds while
the server is running** (`createWorld`/`unloadWorld` throw `UnsupportedOperationException`).
So on Folia:

| Works on Folia | Not possible on Folia |
|---|---|
| Teleports, all destinations, anchors, spawns | `/mv create`, `/mv import`, `/mv load`, `/mv unload`, `/mv clone` |
| Every world property (`/mv modify`), gamerules | Deleting a *loaded* world |
| Portals, nether/end linking, sign portals | |
| Inventories, entry fees, access, player limits | |
| `/mv regen` -> terrain is reset on the next restart | |

Only the worlds the server loads itself (`level-name` plus its nether and end) exist on Folia.
Folia also doesn't fire `PlayerChangedWorldEvent`, `PlayerRespawnEvent` or `PlayerPortalEvent`,
so Dreammultiverse watches world changes, respawns and portals itself there.

## Commands

### `/mv` (Core) - aliases `/dmv`, `/dreammultiverse`
| Command | What it does |
|---|---|
| `/mv create <name> <normal\|nether\|end> [-s seed] [-g generator[:id]] [-t normal\|flat\|large_biomes\|amplified] [-n] [-a]` | New world (`-n` no structures, `-a` don't adjust spawn) |
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
