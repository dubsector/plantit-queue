# plantit-queue

[![Build](https://github.com/dubsector/plantit-queue/actions/workflows/build.yml/badge.svg)](https://github.com/dubsector/plantit-queue/actions/workflows/build.yml)
[![CodeQL](https://github.com/dubsector/plantit-queue/actions/workflows/codeql.yml/badge.svg)](https://github.com/dubsector/plantit-queue/actions/workflows/codeql.yml)
[![Dependency Check](https://github.com/dubsector/plantit-queue/actions/workflows/dependency-check.yml/badge.svg)](https://github.com/dubsector/plantit-queue/actions/workflows/dependency-check.yml)
[![Zizmor](https://github.com/dubsector/plantit-queue/actions/workflows/zizmor.yml/badge.svg)](https://github.com/dubsector/plantit-queue/actions/workflows/zizmor.yml)
[![Security Policy](https://img.shields.io/badge/Security-Policy-green)](SECURITY.md)
[![Dependabot](https://img.shields.io/badge/Dependabot-enabled-025E8C?logo=dependabot)](https://github.com/dubsector/plantit-queue/network/updates)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/dubsector/plantit-queue/badge)](https://securityscorecards.dev/viewer/?uri=github.com/dubsector/plantit-queue)

Proxy-level queue system for [Plant It](https://github.com/dubsector/plantit) game servers, running on [Velocity](https://papermc.io/software/velocity).

Players on any configured server (lobby, survival, creative, etc.) can queue for the game. The queue dispatches players directly to whichever game server signals it has an open slot — supporting any number of game servers with optional Pterodactyl auto-scaling.

## Requirements

- Velocity 3.3.x
- Java 21

## Installation

1. Download the latest JAR from [Releases](https://github.com/dubsector/plantit-queue/releases)
2. Drop it into your Velocity `plugins/` folder
3. Restart Velocity — a default `config.yml` is generated in `plugins/plantit-queue/`
4. Edit `config.yml` to match your server names (must match names in `velocity.toml`)
5. Reload with `/piq reload`

## Configuration

```yaml
# Servers players can queue from (must match velocity.toml server names)
queue-servers:
  - Lobby
  - Survival
  - Creative

# Game servers allowed to pull players from the queue.
# Empty list = accept slot signals from any backend server.
game-servers:
  - plantit-1
  - plantit-2

# How often (seconds) to push position updates to queued players
position-broadcast-interval: 3

# Max players to fill into one server before starting to fill the next.
# Match this to your game's player capacity.
max-players-per-server: 10

# Debug mode — bypasses the queue and instantly dispatches players.
# For local testing only. Requires /piq reload to take effect.
debug:
  enabled: false

# Optional Pterodactyl auto-scaler
pterodactyl:
  enabled: false
  panel-url: "https://panel.example.com"
  api-key: "ptlc_your_key_here"
  scale-up-threshold: 8
  scale-down-idle-minutes: 5
  servers:
    plantit-1:
      identifier: "abc12345"
      always-on: true
    plantit-2:
      identifier: "def67890"
      always-on: false
```

## Commands

### Player commands

| Command | Description |
|---|---|
| `/piq join` | Join the queue |
| `/piq leave` | Leave the queue |
| `/piq pos` | Check your current position |

### Admin commands

Requires the `plantit.admin` permission.

| Command | Description |
|---|---|
| `/piq stop` | Close the queue and clear all waiting players |
| `/piq start` | Re-open the queue |
| `/piq reload` | Reload `config.yml` from disk |
| `/piq open <server> <slots>` | *(debug mode only)* Manually dispatch players to a server |

## How it works

1. A player runs `/piq join` on any queue-eligible server
2. They see a **boss bar** showing their position, **tab list** footer with live count, and **action bar** updates
3. When a Plant It game server finishes a match, it sends a `SLOT_OPEN:<count>` plugin message to Velocity over the `plantit:queue` channel
4. The queue dispatches the next players directly to that server
5. Players see a "Connecting..." title and are transferred automatically

Each game server operates independently — multiple servers can signal open slots simultaneously and each fills from the queue separately.

## Multi-server scaling

Add as many game servers as you want in `velocity.toml` and `config.yml`:

```yaml
game-servers:
  - plantit-1
  - plantit-2
  - plantit-3
```

No other changes needed. Each server signals its own availability.

## Pterodactyl auto-scaling

When enabled, the queue will automatically start stopped Pterodactyl server instances when demand is high and stop idle ones to save resources. Requires pre-created server allocations in your panel — the scaler starts and stops them, it does not create new ones.

Set `pterodactyl.enabled: true` and fill in your panel URL, Client API key, and server identifiers (the short ID in the panel URL for each server).

## Debug mode

Enable `debug.enabled: true` in `config.yml` and run `/piq reload`. A warning is printed to the console on every startup while this is on.

While active, admins gain access to an additional command:

| Command | Description |
|---|---|
| `/piq open <server> <slots>` | Manually push queued players to a server, bypassing the `SLOT_OPEN` requirement |

This lets you test queue dispatch without a game server sending signals — useful when the server is online but the game plugin isn't loaded yet.

**Disable before going live.**

## Plugin messaging protocol

The `plantit` Paper plugin communicates with this plugin over the `plantit:queue` channel. Messages are UTF-8 strings:

| Direction | Message | Meaning |
|---|---|---|
| Game server → Velocity | `SLOT_OPEN:<count>` | Server has `count` open player slots |

## Building from source

```bash
git clone https://github.com/dubsector/plantit-queue
cd plantit-queue
mvn clean package -DskipTests
# JAR is in target/
```

Requires Java 21 and Maven 3.8+.

## Security

Release JARs are built by CI and signed with [build provenance attestations](https://docs.github.com/en/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds). Verify a downloaded JAR with:

```bash
gh attestation verify plantit-queue-*.jar --repo dubsector/plantit-queue
```

To report a vulnerability, see [SECURITY.md](SECURITY.md). Please use [GitHub private advisories](https://github.com/dubsector/plantit-queue/security/advisories/new) rather than opening a public issue.

## License

[GPL-3.0](LICENSE) — forks must also be open source.
