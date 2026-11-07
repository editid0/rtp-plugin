# PrimedRTP

Lag-free random teleport for **Paper & Folia 26.3**.

Most RTP plugins pick a random spot *when you type the command*, then load/generate terrain while you wait — and the
server stutters. PrimedRTP does that work ahead of time: every RTP-enabled world keeps a **pool of safe destinations**
that were found, **pre-generated** and **kept loaded** in the background. `/rtp` just hands you one, so the teleport is
instant and the terrain is already there when you arrive.

## Features

- **Pre-generated location pools** — per-world pool of ready destinations; surrounding chunks generated in advance
  (`pregen-radius`) and held loaded with plugin chunk tickets (`keep-loaded-radius`). Pools are saved on shutdown and
  re-validated on startup, so they're full right after a restart. Entries expire and are spaced apart (`min-spacing`).
- **Zero-latency flow** — the destination is reserved when the warmup *starts*; if a pool is ever empty, an urgent live
  search runs *during* the countdown.
- **Server-friendly background work** — one throttled chunk loader: concurrency cap, loads/second token bucket,
  auto-pause above an MSPT threshold, optional pause while the server is empty, startup delay, per-world failure
  back-off.
- **Smart, safe search** — square or circular (uniform-by-area) rings around world spawn / fixed point / border centre;
  respects the world border; biome whitelist/blacklist with globs (checked *before* loading chunks); surface, cave
  (nether) and end-island modes; unsafe floor/nearby block lists with `#tags`; liquid avoidance; sky-access check;
  min distance from other players; WorldGuard region and GriefPrevention claim avoidance; generated/ungenerated
  chunk preference.
- **Everything is per-world** — every section (area, search, pool, teleport, cooldown, cost) can be overridden per
  world on top of a shared `defaults` template.
- **Teleport experience** — warmup with move/damage cancel (jumping is fine), action-bar and boss-bar countdowns,
  sounds, particles, arrival title, potion effects, post-arrival invulnerability and fall-damage protection, commands
  run after teleport.
- **Cooldowns & costs** — permission-tier cooldowns (shortest wins), per-world or shared, persisted across restarts;
  Vault money, XP levels and item costs with refunds on failure.
- **Ways in** — `/rtp` (+ aliases `/wild`, `/randomtp`, `/wilderness`), world-selector GUI, `[RTP]` signs, first-join
  and respawn triggers, world redirects (e.g. nether → overworld), public API & events.
- **Integrations (optional)** — Vault, PlaceholderAPI, WorldGuard, GriefPrevention ([details](#integrations)).
- **Folia-native** — uses only region/entity/global schedulers (`folia-supported: true`).
- **Admin-friendly** — `/rtp admin pool` live pool stats, hot reload, typo detection with exact config paths, automatic
  config upgrades with backups, MiniMessage messages.

## Requirements

- **Paper 26.3** or a Paper fork (Purpur, Pufferfish, …), or **Folia 26.3**. Java 25+.
- Optional: Vault + an economy plugin, PlaceholderAPI, WorldGuard, GriefPrevention.

Spigot and CraftBukkit are **not** supported. The server log calls PrimedRTP a "Bukkit plugin" because it ships a
classic `plugin.yml`, but the code needs Paper's API: async chunk loading and generation (the whole point of the
plugin), `teleportAsync`, the region schedulers, Brigadier commands and Adventure text.

## Installation

1. Drop `PrimedRTP-<version>.jar` into `plugins/`.
2. Start the server. `config.yml`, `messages.yml` and `gui.yml` are created in `plugins/PrimedRTP/`.
3. List the worlds you want under `worlds:` (overworld, nether and end are pre-configured), then `/rtp admin reload`.
4. Watch the pools fill with `/rtp admin pool`.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/rtp` | `primedrtp.use` | Random teleport (or open the GUI if `open-on-bare-command`) |
| `/rtp <world>` | `primedrtp.use` (+ world permission) | Random teleport in a world |
| `/rtp <world> <players>` | `primedrtp.others` | Send players (selectors allowed; skips warmup/cooldown/cost) |
| `/rtp gui` | `primedrtp.gui` | World selector menu |
| `/rtp cancel` | `primedrtp.use` | Cancel a pending teleport |
| `/rtp help` | `primedrtp.use` | Help |
| `/rtp admin reload` | `primedrtp.admin` | Reload config, messages and GUI |
| `/rtp admin info` | `primedrtp.admin` | Version, platform, hooks, chunk-loader state |
| `/rtp admin pool [world]` | `primedrtp.admin` | Pool status: size, found/served, avg search time, chunks held |
| `/rtp admin pool clear\|refill <world>` | `primedrtp.admin` | Drop and rebuild / top up a pool |
| `/rtp admin cooldown reset <player> [world]` | `primedrtp.admin` | Reset cooldowns |

A world whose name collides with a subcommand (`admin`, `gui`, `help`, `cancel`) can be targeted with quotes:
`/rtp "admin"`.

## Permissions

| Permission | Default | |
|---|---|---|
| `primedrtp.use` | everyone | `/rtp` |
| `primedrtp.gui` | everyone | GUI |
| `primedrtp.sign.use` | everyone | Use RTP signs |
| `primedrtp.world.*` | op | Every world that requires a permission (see `worlds.<w>.permission`) |
| `primedrtp.others` | op | Teleport other players |
| `primedrtp.sign.create` | op | Create RTP signs |
| `primedrtp.bypass.cooldown` / `.cost` / `.warmup` | op | Bypasses |
| `primedrtp.cooldown.<tier>` | — | Cooldown tier from `cooldown.tiers` (shortest applicable wins) |
| `primedrtp.admin` | op | `/rtp admin …` |

## Configuration highlights

```yaml
worlds:
  defaults:                 # full template, every world inherits it
    area:   { center: { mode: world_spawn }, shape: circle, min-radius: 500, max-radius: 5000 }
    search: { mode: auto, biomes: { mode: blacklist, list: ["*ocean*", "*river*"] }, avoid-claims: true }
    pool:   { size: 10, keep-loaded-radius: 1, pregen-radius: 3, expire-after: 30m, min-spacing: 256 }
    teleport: { warmup: 3s, invulnerability: 3s, fall-damage-protection: 10s }
    cooldown: { base: 5m, tiers: { vip: 2m, mvp: 30s }, per-world: false }
    cost:   { money: 0, xp-levels: 0, items: [], refund-on-failure: true }
  world: {}                 # pure defaults
  world_nether:             # only what differs
    search: { mode: cave, min-y: 32, max-y: 120 }
    pool:   { size: 5 }
```

Every option is documented inline in the generated `config.yml`. Durations accept `500ms`, `30s`, `5m`, `1h30m`, `2d`.
Block lists accept names, `#tags` and `!removals`. Biome lists accept keys and globs.

### Tuning the pool

| Goal | Change |
|---|---|
| More players RTP-ing at once | raise `pool.size` |
| Less memory / fewer loaded chunks | lower `keep-loaded-radius` (each entry holds `(2r+1)²` chunks) |
| Arrive with more terrain already generated | raise `pregen-radius` (costs background generation time) |
| Weak host | lower `performance.max-chunk-loads-per-second` / `max-concurrent-chunk-loads`, keep `pause-above-mspt` |
| Restrictive search (whitelisted biomes, claims) | raise `search.max-attempts`; check `/rtp admin pool` failure counts with `debug: true` |

## Integrations

All integrations are optional and switch on by themselves when the other plugin is installed and enabled. Each one is
isolated: if an installed version is incompatible, only that integration is turned off and a warning is logged.
`/rtp admin info` lists the active ones.

### Vault (money costs)

Set `cost.money` to charge for `/rtp`. Needs Vault **and** an economy plugin (EssentialsX, CMI, …); an economy that
registers after PrimedRTP is picked up automatically. Money is checked before the warmup, taken when the warmup
ends and refunded if the teleport then fails (`refund-on-failure`). Players with `primedrtp.bypass.cost` pay nothing. Without an
economy, money costs are skipped (XP and item costs still apply).

### WorldGuard and GriefPrevention (claim avoidance)

With `search.avoid-claims: true` (the default) players never land in protected land:

| Plugin | What is avoided |
|---|---|
| WorldGuard | Any region except `__global__` |
| GriefPrevention | Any claim, player or admin, at any height (a cave under a claim counts as inside it) |

Spots are checked when they are found **and** again right before the teleport, so a claim made after a spot entered
the pool is still respected. If the claim plugin throws an error the spot is allowed and a warning is logged once, so
a broken integration never stops RTP. Set `avoid-claims: false` per world to turn it off. Other claim plugins can be
supported with a [`LocationFilter`](#developer-api).

### PlaceholderAPI

Provides `%primedrtp_…%` placeholders for scoreboards, tab lists, holograms and menus. Add `_<world>` to ask about a
specific world (underscores in world names are fine: `%primedrtp_pool_size_world_nether%`). Without it, the world a
bare `/rtp` would send the player to is used (after `world-redirects`).

| Placeholder | Returns | Example |
|---|---|---|
| `%primedrtp_cooldown[_<world>]%` | Remaining cooldown, formatted with `time.*` from `messages.yml` | `2m 30s`, `now` |
| `%primedrtp_cooldown_seconds[_<world>]%` | Remaining cooldown in whole seconds, rounded up | `150` |
| `%primedrtp_ready[_<world>]%` | Whether the player is off cooldown | `true` |
| `%primedrtp_pool_size[_<world>]%` | Destinations ready right now | `7` |
| `%primedrtp_pool_target[_<world>]%` | Configured pool size | `10` |
| `%primedrtp_pool_percent[_<world>]%` | How full the pool is, 0–100 | `70` |
| `%primedrtp_pending%` | Whether the player has a teleport warmup running | `false` |

Player-specific placeholders return an empty string when there is no online player, and world placeholders return an
empty string for an unknown world. This works one way only: PrimedRTP provides placeholders but does not fill in other
plugins' placeholders inside its own messages.

### Any plugin: commands after a teleport

`teleport.commands` runs commands after every successful teleport, so you can hook up any plugin that has commands
(quests, stats, broadcasts, kits, …):

```yaml
teleport:
  commands:
    - "console: broadcast {player} went exploring in {world}!"
    - "player: kit explorer"
```

| Prefix | Runs as |
|---|---|
| `console:` (or no prefix) | The console |
| `player:` | The player, with their own permissions |
| `op:` | The console too. Players are never opped temporarily, because that can be abused. |

Placeholders: `{player}` `{uuid}` `{world}` `{x}` `{y}` `{z}` (the arrival block). A leading `/` is optional.

## Developer API

```java
PrimedRTPApi api = Bukkit.getServicesManager().load(PrimedRTPApi.class);
api.teleport(player, world, /* force */ false).thenAccept(outcome -> { /* SUCCESS, ON_COOLDOWN, ... */ });
api.findLocation(world);                       // safe location without teleporting
api.registerFilter(new LocationFilter() { ... });  // veto candidate spots (claims, custom rules)
```

Events: `RtpPreTeleportEvent` (cancellable, change world), `RtpTeleportEvent` (cancellable, change destination),
`RtpPostTeleportEvent`.

## Building

```sh
./gradlew build          # -> build/libs/PrimedRTP-<version>.jar
```

Any JDK 25+ works; the output targets Java 25 bytecode (`--release 25`).

## License

GPL-3.0 — see [LICENSE](LICENSE).
