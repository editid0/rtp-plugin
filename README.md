# SwiftRTP

Lag-free random teleport for **Paper & Folia 26.3**.

Most RTP plugins pick a random spot *when you type the command*, then load/generate terrain while you wait — and the
server stutters. SwiftRTP does that work ahead of time: every RTP-enabled world keeps a **pool of safe destinations**
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
  min distance from other players; WorldGuard region avoidance; generated/ungenerated chunk preference.
- **Everything is per-world** — every section (area, search, pool, teleport, cooldown, cost) can be overridden per
  world on top of a shared `defaults` template.
- **Teleport experience** — warmup with move/damage cancel (jumping is fine), action-bar and boss-bar countdowns,
  sounds, particles, arrival title, potion effects, post-arrival invulnerability and fall-damage protection, commands
  run after teleport.
- **Cooldowns & costs** — permission-tier cooldowns (shortest wins), per-world or shared, persisted across restarts;
  Vault money, XP levels and item costs with refunds on failure.
- **Ways in** — `/rtp` (+ aliases `/wild`, `/randomtp`, `/wilderness`), world-selector GUI, `[RTP]` signs, first-join
  and respawn triggers, world redirects (e.g. nether → overworld), public API & events.
- **Integrations (optional)** — Vault, PlaceholderAPI, WorldGuard.
- **Folia-native** — uses only region/entity/global schedulers (`folia-supported: true`).
- **Admin-friendly** — `/rtp admin pool` live pool stats, hot reload, typo detection with exact config paths, automatic
  config upgrades with backups, MiniMessage messages.

## Requirements

- Paper or Folia **26.3** (Java 25+)
- Optional: Vault + an economy plugin, PlaceholderAPI, WorldGuard

## Installation

1. Drop `SwiftRTP-<version>.jar` into `plugins/`.
2. Start the server. `config.yml`, `messages.yml` and `gui.yml` are created in `plugins/SwiftRTP/`.
3. List the worlds you want under `worlds:` (overworld, nether and end are pre-configured), then `/rtp admin reload`.
4. Watch the pools fill with `/rtp admin pool`.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/rtp` | `swiftrtp.use` | Random teleport (or open the GUI if `open-on-bare-command`) |
| `/rtp <world>` | `swiftrtp.use` (+ world permission) | Random teleport in a world |
| `/rtp <world> <players>` | `swiftrtp.others` | Send players (selectors allowed; skips warmup/cooldown/cost) |
| `/rtp gui` | `swiftrtp.gui` | World selector menu |
| `/rtp cancel` | `swiftrtp.use` | Cancel a pending teleport |
| `/rtp help` | `swiftrtp.use` | Help |
| `/rtp admin reload` | `swiftrtp.admin` | Reload config, messages and GUI |
| `/rtp admin info` | `swiftrtp.admin` | Version, platform, hooks, chunk-loader state |
| `/rtp admin pool [world]` | `swiftrtp.admin` | Pool status: size, found/served, avg search time, chunks held |
| `/rtp admin pool clear\|refill <world>` | `swiftrtp.admin` | Drop and rebuild / top up a pool |
| `/rtp admin cooldown reset <player> [world]` | `swiftrtp.admin` | Reset cooldowns |

A world whose name collides with a subcommand (`admin`, `gui`, `help`, `cancel`) can be targeted with quotes:
`/rtp "admin"`.

## Permissions

| Permission | Default | |
|---|---|---|
| `swiftrtp.use` | everyone | `/rtp` |
| `swiftrtp.gui` | everyone | GUI |
| `swiftrtp.sign.use` | everyone | Use RTP signs |
| `swiftrtp.world.*` | op | Every world that requires a permission (see `worlds.<w>.permission`) |
| `swiftrtp.others` | op | Teleport other players |
| `swiftrtp.sign.create` | op | Create RTP signs |
| `swiftrtp.bypass.cooldown` / `.cost` / `.warmup` | op | Bypasses |
| `swiftrtp.cooldown.<tier>` | — | Cooldown tier from `cooldown.tiers` (shortest applicable wins) |
| `swiftrtp.admin` | op | `/rtp admin …` |

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

## PlaceholderAPI

`%swiftrtp_cooldown%`, `%swiftrtp_cooldown_<world>%`, `%swiftrtp_cooldown_seconds[_<world>]%`,
`%swiftrtp_ready[_<world>]%`, `%swiftrtp_pool_size[_<world>]%`, `%swiftrtp_pool_target[_<world>]%`,
`%swiftrtp_pool_percent[_<world>]%`, `%swiftrtp_pending%`

## Developer API

```java
SwiftRTPApi api = Bukkit.getServicesManager().load(SwiftRTPApi.class);
api.teleport(player, world, /* force */ false).thenAccept(outcome -> { /* SUCCESS, ON_COOLDOWN, ... */ });
api.findLocation(world);                       // safe location without teleporting
api.registerFilter(new LocationFilter() { ... });  // veto candidate spots (claims, custom rules)
```

Events: `RtpPreTeleportEvent` (cancellable, change world), `RtpTeleportEvent` (cancellable, change destination),
`RtpPostTeleportEvent`.

## Building

```sh
./gradlew build          # -> build/libs/SwiftRTP-<version>.jar
```

Any JDK 25+ works; the output targets Java 25 bytecode (`--release 25`).

## License

GPL-3.0 — see [LICENSE](LICENSE).
