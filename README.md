<p align="center">
  <img src="fabric/src/main/resources/assets/pathadol/icon.png" width="160" alt="Pathadol"/>
</p>

# Pathadol

**Pathadol** is a fork of [Baritone](https://github.com/cabaletta/baritone) for Minecraft **26.3** (Fabric). The goal is to keep Baritone's pathfinding brain and make every part of it act like a human player: camera, movement, pathing, mining, bridging, combat, inventory and GUIs.

> Status: **P0, rebrand + cloud build.** Right now it behaves exactly like Baritone 26.3. The humanization work follows the phases in [docs/HUMANIZATION_PLAN.md](docs/HUMANIZATION_PLAN.md).

## Install
1. Install [Fabric Loader](https://fabricmc.net/use/) (≥ 0.19.5) for Minecraft 26.3.
2. Download the latest JAR from **Releases**, or from the newest green run under **Actions → Build Pathadol → Artifacts**.
3. Put `pathadol-standalone-fabric-*.jar` into `.minecraft/mods/`. Don't install Baritone at the same time; Pathadol replaces it.
4. In game, commands use the `#` prefix (for example `#goto 100 70 100`, `#mine diamond_ore`, `#help`).

Settings and cache live in `.minecraft/pathadol/` (Baritone used `.minecraft/baritone/`).

## Which JAR?
| File | Use it for |
|---|---|
| `pathadol-standalone-fabric-*.jar` | Normal play (recommended) |
| `pathadol-api-fabric-*.jar` | Building add-ons against the API |
| `pathadol-unoptimized-fabric-*.jar` | Debugging (not obfuscated) |

## Building
Nothing is built locally. Every push builds on GitHub Actions ([.github/workflows/gradle_build.yml](.github/workflows/gradle_build.yml)), and pushing a tag like `v0.1.0` publishes a Release.

## Compatibility
- Java packages are still `baritone.*`, and the mod `provides` the id `baritone`, so add-ons written for the Baritone API keep working.
- Upstream Baritone fixes can be merged from `cabaletta/baritone` branch `26.3`.

## Credits and license
Pathadol is built on Baritone by leijurv, Brady and the Baritone contributors. Their original README is in [docs/BARITONE_README.md](docs/BARITONE_README.md).

Licensed under the **GNU LGPL v3.0**, the same as Baritone (see [LICENSE](LICENSE)). The original copyright headers are kept in every file.

> Many servers ban any automation, however human it looks. Use Pathadol in singleplayer or on servers that allow it.
