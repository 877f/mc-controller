# Setup

Everything below has been run on this machine already unless marked **you**.

## What is installed

| Tool | Version | Location |
|---|---|---|
| JDK | Microsoft OpenJDK 25.0.4 LTS | `C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot` |
| Gradle | 9.2.1 (via wrapper) | downloaded on first `gradlew` run |
| Git | 2.54.0 | already present |

Minecraft 26.2 needs **Java 25** — Java 21 will not work. Gradle comes from the wrapper, so
there is nothing to install for it.

## Version pins

| Piece | Version | Why |
|---|---|---|
| Minecraft | `26.2` | the target |
| NeoForge | `26.2.0.59` | matching loader build |
| ModDevGradle | `2.0.143` | NeoForge's current Gradle plugin |
| Baritone | `1.18.0` (branch `26.2`) | pathfinding, mining, building |

## Layout

```
c:\MCcontroler\
  build.gradle          the mod build
  gradle.properties     all version pins live here
  libs/                 Baritone jars, produced by the step below
  src/main/java/        mod sources
  src/main/resources/   control panel (web/) and mod metadata
  external/baritone/    Baritone source checkout (branch 26.2)
  external/mdk/         NeoForge MDK, kept only as an API reference
```

`external/` is tooling, not part of the mod. It is safe to delete and re-clone.

## Building Baritone

Upstream publishes no release for 26.2 yet — the newest tagged build is 1.15.0 for MC 1.21.8,
and the only downloadable 26.2 jar anywhere is Fabric-only. So Baritone is built from source off
the upstream `26.2` branch.

```powershell
cd c:\MCcontroler\external\baritone
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot"
.\gradlew.bat :neoforge:build
```

This produces, in `external/baritone/dist/`:

- `baritone-api-neoforge-1.18.0.jar` — **the one to use.** Fully working mod, with the
  `baritone.api` package left readable so other mods can link against it.
- `baritone-standalone-neoforge-1.18.0.jar` — do **not** use with this mod. See below.
- `baritone-unoptimized-neoforge-1.18.0.jar` — nothing obfuscated, useful when debugging.

Copy the **api** jar into `c:\MCcontroler\libs\`. The build reads it from there for both
compiling and running.

### Use the api jar, never standalone

Both jars are complete mods and both load fine, so this fails in a confusing way. The standalone
jar is fully ProGuard-obfuscated and **does not contain `baritone.api.BaritoneAPI` at all**:

| jar | `baritone/api/*` entries |
|---|---|
| `baritone-api-…` | 183 |
| `baritone-standalone-…` | 4 |

Compiling against the api jar and running against standalone compiles cleanly, loads cleanly,
and then dies with `NoClassDefFoundError: baritone/api/BaritoneAPI` the moment any Baritone call
is made — which is the first job you run, not startup.

> The branch moves. It is pinned to a known-good commit in `external/baritone`; run
> `git -C external/baritone log -1` to see which. Re-pull only deliberately.

### Baritone's mod id is "baritoe"

Not a typo in this repo — Baritone's own `neoforge.mods.toml` declares `modId="baritoe"`, and the
loader reports it as `Baritone 1.18.0 (baritoe)`. Our dependency block has to match, or the game
refuses to start with *"requires baritone … currently not installed"*. Do not "fix" it.

### Keep Baritone on the classpath, not in run/mods

For the **dev run**, Baritone belongs on the runtime classpath (`localRuntime` in
`build.gradle`), which is already configured. Dropping the jar into `run/mods/` also makes FML
load it, but it then lands in its own module layer while this mod loads from `build/classes`,
and linking `baritone.api.*` fails at runtime. For a **real install**, both jars simply go in
`mods/` together and this does not apply.

### PowerShell gotcha

Do not pass `-Pmod_version=1.18.0` — PowerShell splits it at the dot and Gradle reads `.18.0`
as a task name. The version already lives in `gradle.properties`, so the flag is unnecessary.

## Building the mod

```powershell
cd c:\MCcontroler
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot"
.\gradlew.bat build
```

The mod jar lands in `build/libs/mccontroler-0.1.0.jar`.

## Running it

**In a dev client** (fastest loop — no launcher, no account needed):

```powershell
.\gradlew.bat runClient
```

Baritone is added to the dev run automatically from `libs/`.

**In your real game** — **you** need to do this part, because Minecraft is not installed on this
machine yet (there is no `%APPDATA%\.minecraft`):

1. Install the Minecraft launcher and run 26.2 once so the folder exists.
2. Install NeoForge 26.2.0.59.
3. Drop both jars into `%APPDATA%\.minecraft\mods\`:
   - `mccontroler-0.1.0.jar`
   - `baritone-api-neoforge-1.18.0.jar`  ← the **api** jar, not standalone

`gradlew bundle` collects exactly these two into `dist/` ready to copy across.

## Using it

Launch the game and join a world. The log prints:

```
[MC Controler] control panel ready at http://127.0.0.1:7654/
```

Open that in a browser. The port is loopback-only and walks upward from 7654 if something else
holds the port.

## Licence

Baritone is LGPL-3.0-only and this mod links against it, so the mod is LGPL-3.0-only too. That is
fine for personal use and for sharing the source; it does mean you cannot ship it under a more
restrictive licence.
