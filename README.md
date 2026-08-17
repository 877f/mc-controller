# MC Controler

A client-side NeoForge mod for **Minecraft 26.2** that opens a local web control panel. Pick a
block in the browser and your player goes and gets it — mining what it can, crafting what it
must. Also runs tree farms, area excavations, and schematic builds.

Pathfinding, mining and building are done by [Baritone](https://github.com/cabaletta/baritone).
This mod is the brain and the UI on top of it.

> **Status:** in progress. See *Current state* below for what actually works today.

## How it fits together

```
browser  ──HTTP──>  WebServer (loopback)  ──GameThread──>  Minecraft client thread
   ^                                                              │
   └──────────── server-sent events ──── EventStream <── JobManager (per tick)
                                                                  │
                                                          BaritoneBridge
                                                                  │
                                                              Baritone
```

- **WebServer** — JDK's bundled HTTP server on `127.0.0.1:7654`. No external web framework.
- **GameThread** — HTTP handlers hop onto the client thread before touching game state.
- **JobManager** — runs one job at a time from a queue, advanced once per client tick.
- **EventStream** — pushes job progress and log lines back to the panel over SSE.
- **BaritoneBridge** — the single place that talks to Baritone.

Jobs are ticked rather than run on their own threads, because everything they do — reading
inventory, opening a chest, asking Baritone to mine — is only legal on the client thread.

## Design decisions worth knowing

**Loopback only.** The panel binds to `127.0.0.1`, so nothing on your network can drive your
player. If the port is busy it walks upward from 7654 rather than failing to start.

**Baritone is built from source.** There is no released Baritone for 26.2 — the newest tagged
release is 1.15.0 for MC 1.21.8, and the only 26.2 jar published anywhere is Fabric-only. We
build the upstream `26.2` branch, which does produce a working NeoForge jar. See [SETUP.md](SETUP.md).

**One job at a time.** Two jobs would both be commanding the same player. Extra requests queue.

**LGPL-3.0.** Baritone is LGPL-3.0-only and this mod links against it.

## Current state

| Piece | State |
|---|---|
| Toolchain, JDK 25, Gradle 9.2.1 | done |
| Baritone 26.2 built for NeoForge | done — `libs/baritone-*-neoforge-1.18.0.jar` |
| Mod scaffold, mod metadata | done |
| Web server + control panel UI | done |
| Job manager, SSE event stream | done |
| Baritone bridge | done |
| Mod loads in game, panel binds | **verified** — see below |
| Gather / tree / excavate jobs | written, compiles; not yet run in game |
| Sapling replanting (tree loop mode) | written, compiles; not yet run in game |
| Item icons rendered from the game | not started |
| Craft planner (recursive recipes) | not started — see *Recipe visibility* |
| Chest deposit, junk filter, tools | not started |
| Schematic upload + build | not started |

### What a dev client run proved

```
[MC Controler] control panel ready at http://127.0.0.1:7654/
Reloading ResourceManager: vanilla, mod_resources, mod/baritoe, mod/neoforge, mod/mccontroler
```

The mod loads, Baritone loads beside it, and the HTTP server binds — confirming
`com.sun.net.httpserver` is reachable under NeoForge's module layer, which was the main
runtime unknown.

### Blocked: this machine cannot render 26.2

The dev client crashes a few seconds after startup, in vanilla code:

```
java.lang.IllegalStateException: Missing uniform Globals (should be UNIFORM_BUFFER)
  at TextureAtlas.cycleAnimationFrames → SpriteContents$AnimationState.drawToAtlas
  at com.mojang.blaze3d.opengl.GlCommandEncoder.trySetup
```

No mod code is in that path — it is vanilla texture-atlas animation upload.

```
Using graphics device: AMD Radeon(TM) 880M Graphics
Using graphics backend OpenGL, using drivers: 3.3.0 Core Profile Context
```

(The 3.3 core profile is the context Minecraft asks for, not a driver limitation — it is normal.)

`Missing uniform Globals` is blaze3d failing to find a uniform block in a linked shader program —
a known failure mode on some AMD drivers, where a uniform block that appears unused gets
optimised out during shader compilation, so the reflection lookup finds nothing.

**The fix is one line of FML config — not a driver update, and not Vulkan.**

```toml
# run/config/fml.toml
earlyWindowControl = false
```

NeoForge's early loading screen creates the GLFW window and an OpenGL context itself, then hands
the window to Minecraft. On this AMD driver that leaves the context in a state where the shader
program's `Globals` uniform block cannot be found, and the first animated-texture upload dies.
Letting Minecraft create its own window avoids it entirely. With that set, the client renders
every texture atlas and starts the sound engine with no exception, still on OpenGL.

Two dead ends, recorded so nobody repeats them:

- **Vulkan.** 26.2 does ship `com.mojang.blaze3d.vulkan.VulkanBackend`, selectable via
  `preferredGraphicsBackend:"vulkan"` in `options.txt` (`DEFAULT | OPENGL | VULKAN`). The Vulkan
  device initialises fine — AMD driver `1.4.329 (LLPC)`, all required extensions — but window
  creation then fails with *"Vulkan: Window surface creation requires the window to have the
  client API set to GLFW_NO_API"*, because FML already made the window an OpenGL one. Fixing
  `earlyWindowControl` made OpenGL work, so Vulkan was never needed. Note that Minecraft resets
  this option to `default` by itself after a backend fails to start.
- **Driver updates.** Windows Update offers no graphics driver on this machine — only a wireless
  module, Insyde firmware and two Acer components, none of which touch OpenGL.

So: the job logic below compiles against the real 26.2 API and the mod loads, but no job has
been watched actually mining a block yet.

## Recipe visibility

Worth knowing before the craft planner is built: **a client cannot see every recipe.**

`ClientRecipeContainer` carries only furnace input sets and stonecutter recipes. Full recipe
data reaches the client as `RecipeDisplayEntry` objects in `ClientRecipeBook`, and those arrive
only for recipes the player has *unlocked*. So a purely client-side planner can resolve exactly
the recipes your recipe book knows about.

Two ways to close the gap, when we get there:

1. **Recipe book only** — pure client-side, picks up modded recipes for free, but cannot plan a
   chain through a recipe you have not unlocked.
2. **Bundle a vanilla recipe table** extracted from the server jar at build time, and fall back
   to it. Complete for vanilla, blind to modded recipes.

The hybrid — book first, bundled table as fallback — covers both, and is the intended approach.

## Panel

- **Gather** — searchable grid of every block/item; pick one, choose a count, go.
- **Trees** — sustainable fell-and-replant loop, or a one-shot target count.
- **Excavate** — two corners, then clear everything between them.
- **Build** — upload a schematic and build it from an origin.

A dock along the bottom shows the running job, a progress bar, and a live log.

## Building

See [SETUP.md](SETUP.md). Short version:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot"
.\gradlew.bat build      # mod jar -> build/libs/
.\gradlew.bat runClient  # dev client with Baritone already loaded
```
