# Extracts every vanilla recipe from the Minecraft server jar into one compact table.
#
# Why: a Minecraft client only ever sees recipes the player has UNLOCKED, so a purely
# client-side planner cannot route through a recipe you have not discovered yet. The server
# jar carries the authoritative, version-exact recipe set as JSON, so we normalise it once at
# build time and ship the result inside the mod.
#
# Output: src/main/resources/recipes.tsv, one recipe per line:
#
#   type <TAB> resultId <TAB> resultCount <TAB> grid <TAB> ingredient*qty,ingredient*qty
#
#   type        craft | smelt (blasting/smoking collapse into smelt)
#   grid        smallest crafting grid that fits: 2, 3, or 0 for smelting
#   ingredient  an item id, a #tag, or alternatives joined by |
#
# Regenerate after a Minecraft version bump:
#   powershell -File scripts/extract-recipes.ps1

param(
    [string]$ServerJar = "$env:USERPROFILE\.gradle\caches\neoformruntime\artifacts\minecraft_26.2_server.jar",
    [string]$OutFile = "$PSScriptRoot\..\src\main\resources\recipes.tsv"
)

Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not (Test-Path $ServerJar)) {
    Write-Error "server jar not found: $ServerJar  (run 'gradlew build' first so ModDevGradle downloads it)"
    exit 1
}

# Mojang ships server.jar as a bundler; the real jar is nested under META-INF/versions/.
$outer = [System.IO.Compression.ZipFile]::OpenRead($ServerJar)
$nested = $outer.Entries | Where-Object { $_.FullName -match "^META-INF/versions/.*/server-.*\.jar$" } | Select-Object -First 1

if ($nested) {
    $tmp = Join-Path $env:TEMP "mccontroler-server-inner.jar"
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($nested, $tmp, $true)
    $outer.Dispose()
    $zip = [System.IO.Compression.ZipFile]::OpenRead($tmp)
    Write-Host "using nested jar: $($nested.FullName)"
} else {
    $zip = $outer
    Write-Host "using server jar directly"
}

# Turns an ingredient value (string, or array of alternatives) into "a|b" form.
function Read-Ingredient($value) {
    if ($null -eq $value) { return $null }
    if ($value -is [string]) { return $value }
    if ($value -is [array]) { return (($value | ForEach-Object { Read-Ingredient $_ }) -join "|") }
    # Older/odd shapes occasionally wrap the id in an object.
    if ($value.PSObject.Properties.Name -contains "item") { return $value.item }
    if ($value.PSObject.Properties.Name -contains "tag") { return "#" + $value.tag }
    return $null
}

function Get-ResultId($result) {
    if ($result -is [string]) { return $result }
    if ($result.PSObject.Properties.Name -contains "id") { return $result.id }
    return $null
}

function Get-ResultCount($result) {
    if ($result -is [string]) { return 1 }
    if ($result.PSObject.Properties.Name -contains "count") { return [int]$result.count }
    return 1
}

$lines = New-Object System.Collections.Generic.List[string]
$skipped = @{}
$entries = $zip.Entries | Where-Object { $_.FullName -match "^data/minecraft/recipe/.+\.json$" }
Write-Host "scanning $($entries.Count) recipe files..."

foreach ($entry in $entries) {
    $reader = New-Object System.IO.StreamReader($entry.Open())
    $json = $reader.ReadToEnd()
    $reader.Close()

    try { $r = $json | ConvertFrom-Json } catch { continue }

    $type = ($r.type -replace "^minecraft:", "")
    $resultId = Get-ResultId $r.result
    if (-not $resultId) { continue }
    $resultCount = Get-ResultCount $r.result

    # ingredient token -> quantity
    $counts = [ordered]@{}
    $grid = 0
    $kind = $null

    switch -Regex ($type) {
        '^crafting_shaped$' {
            $kind = "craft"
            $width = 0
            foreach ($row in $r.pattern) { if ($row.Length -gt $width) { $width = $row.Length } }
            $grid = [Math]::Max($width, $r.pattern.Count)
            foreach ($row in $r.pattern) {
                foreach ($ch in $row.ToCharArray()) {
                    if ($ch -eq ' ') { continue }
                    $tok = Read-Ingredient $r.key.$ch
                    if (-not $tok) { continue }
                    if ($counts.Contains($tok)) { $counts[$tok] += 1 } else { $counts[$tok] = 1 }
                }
            }
        }
        '^crafting_shapeless$' {
            $kind = "craft"
            $grid = if ($r.ingredients.Count -le 4) { 2 } else { 3 }
            foreach ($ing in $r.ingredients) {
                $tok = Read-Ingredient $ing
                if (-not $tok) { continue }
                if ($counts.Contains($tok)) { $counts[$tok] += 1 } else { $counts[$tok] = 1 }
            }
        }
        '^(smelting|blasting|smoking)$' {
            $kind = "smelt"
            $grid = 0
            $tok = Read-Ingredient $r.ingredient
            if ($tok) { $counts[$tok] = 1 }
        }
        default {
            # crafting_transmute, stonecutting, smithing_*, and the special crafting_* recipes
            # are not modelled by the planner yet.
            if ($skipped.ContainsKey($type)) { $skipped[$type] += 1 } else { $skipped[$type] = 1 }
        }
    }

    if (-not $kind -or $counts.Count -eq 0) { continue }

    $ingredients = ($counts.Keys | ForEach-Object { "$_*$($counts[$_])" }) -join ","
    $lines.Add("$kind`t$resultId`t$resultCount`t$grid`t$ingredients")
}

# ── Block drops ──────────────────────────────────────────────────────────────────────────────
#
# Recipes alone cannot explain where a diamond comes from: it is a loot-table drop from diamond
# ore, not a crafted item. Without this the planner has no route to diamonds, raw iron, coal or
# redstone. We walk each block loot table and record which items it can yield.
#
# Self-drops (oak_log dropping oak_log) are excluded — those are already covered by "an item with
# no recipe is mined directly". What matters here is block X dropping a *different* item Y.

function Get-LootItems($node, $sink) {
    if ($null -eq $node) { return }
    if ($node -is [array]) {
        foreach ($child in $node) { Get-LootItems $child $sink }
        return
    }
    if ($node -isnot [System.Management.Automation.PSCustomObject]) { return }

    $props = $node.PSObject.Properties.Name
    if (($props -contains "type") -and ($node.type -eq "minecraft:item") -and ($props -contains "name")) {
        [void]$sink.Add([string]$node.name)
    }
    foreach ($p in $node.PSObject.Properties) { Get-LootItems $p.Value $sink }
}

# True when a condition list makes a drop unreachable for a bot that has no silk touch.
#
# Infested stone "drops stone" only under silk touch; break it bare and you get nothing but a
# silverfish. Recording it as a plain source sent the planner hunting silverfish blocks instead of
# smelting cobblestone, and the run failed with "cannot make stone".
#
#   match_tool + silk_touch  blocks
#   any_of                   blocks only when EVERY branch blocks, so leaves (shears OR silk) stay
#   all_of                   blocks when ANY branch blocks
#   inverted                 never blocks — that IS the without-silk-touch case
function Test-SilkGated($conditions) {
    if ($null -eq $conditions) { return $false }
    foreach ($c in @($conditions)) {
        if ($null -eq $c) { continue }
        switch ($c.condition) {
            "minecraft:match_tool" {
                $enchants = $c.predicate.predicates."minecraft:enchantments"
                foreach ($e in @($enchants)) {
                    if ($e.enchantments -eq "minecraft:silk_touch") { return $true }
                }
            }
            "minecraft:any_of" {
                $terms = @($c.terms)
                if ($terms.Count -gt 0) {
                    $allBlock = $true
                    foreach ($t in $terms) {
                        if (-not (Test-SilkGated @($t))) { $allBlock = $false; break }
                    }
                    if ($allBlock) { return $true }
                }
            }
            "minecraft:all_of" {
                foreach ($t in @($c.terms)) {
                    if (Test-SilkGated @($t)) { return $true }
                }
            }
        }
    }
    return $false
}

# Collects loot item names, skipping any subtree gated behind silk touch.
function Get-ReachableLootItems($node, $sink) {
    if ($null -eq $node) { return }
    if ($node -is [array]) {
        foreach ($child in $node) { Get-ReachableLootItems $child $sink }
        return
    }
    if ($node -isnot [System.Management.Automation.PSCustomObject]) { return }

    $props = $node.PSObject.Properties.Name
    if ($props -contains "conditions") {
        if (Test-SilkGated $node.conditions) { return }
    }
    if (($props -contains "type") -and ($node.type -eq "minecraft:item") -and ($props -contains "name")) {
        [void]$sink.Add([string]$node.name)
    }
    foreach ($p in $node.PSObject.Properties) {
        if ($p.Name -eq "conditions") { continue }
        Get-ReachableLootItems $p.Value $sink
    }
}

# item -> set of blocks that drop it
$dropSources = @{}
$lootEntries = $zip.Entries | Where-Object { $_.FullName -match "^data/minecraft/loot_table/blocks/.+\.json$" }
Write-Host "scanning $($lootEntries.Count) block loot tables..."

foreach ($entry in $lootEntries) {
    $blockName = "minecraft:" + [System.IO.Path]::GetFileNameWithoutExtension($entry.FullName)
    $reader = New-Object System.IO.StreamReader($entry.Open())
    $json = $reader.ReadToEnd()
    $reader.Close()
    try { $lt = $json | ConvertFrom-Json } catch { continue }

    # Infested blocks release a silverfish and drop nothing without silk touch. This is a
    # harvester, not a combat bot, so they are never a source worth routing through.
    if ($blockName -like "minecraft:infested_*") { continue }

    $found = New-Object System.Collections.Generic.HashSet[string]
    Get-ReachableLootItems $lt $found

    foreach ($item in $found) {
        if ($item -eq $blockName) { continue }   # self-drop, already handled
        if (-not $dropSources.ContainsKey($item)) {
            $dropSources[$item] = New-Object System.Collections.Generic.HashSet[string]
        }
        [void]$dropSources[$item].Add($blockName)
    }
}

# Blocks whose loot is ONLY themselves, e.g. oak_log -> oak_log, diorite -> diorite.
#
# This is the difference between "mine this block to get this item" and "mine this block to get
# something else". Iron ore is naturally generated, but mining it yields raw iron — so counting
# iron ore in the inventory to decide when to stop never increases, and the bot mines every ore
# it can find forever. Only pure self-droppers may be mined to obtain themselves.

# True when an "alternatives" tree contains an item entry for $blockName that is reachable
# without needing a special enchant. Gravel's loot table is: silk touch -> gravel, otherwise
# an alternatives node of (fortune-gated chance -> flint, unconditioned fallback -> gravel).
# That fallback makes gravel a reliable, if not literally 100%, self-drop — the same shape as
# an ore's silk-touch branch would give diamond ore, except here the *fallback* is the block
# itself rather than the silk-touch branch. A plain "others.Count -eq 0" check misses this
# because flint is technically also possible; walk the tree instead and ignore item entries
# gated behind conditions like match_tool (silk touch) or table_bonus (fortune chance).
function Test-ReliableSelfDrop($node, [string]$blockName) {
    if ($null -eq $node) { return $false }
    if ($node -is [array]) {
        foreach ($child in $node) { if (Test-ReliableSelfDrop $child $blockName) { return $true } }
        return $false
    }
    if ($node -isnot [System.Management.Automation.PSCustomObject]) { return $false }

    $props = $node.PSObject.Properties.Name
    if (($props -contains "type") -and ($node.type -eq "minecraft:item") -and ($props -contains "name") -and ($node.name -eq $blockName)) {
        $conds = @()
        if ($props -contains "conditions") { $conds = @($node.conditions) }
        # survives_explosion only matters when the block is destroyed by an explosion, not a
        # normal player break, so it does not make the drop unreliable for mining purposes.
        $blocking = @($conds | Where-Object { $_.condition -ne "minecraft:survives_explosion" })
        if ($blocking.Count -eq 0) { return $true }
    }
    foreach ($p in $node.PSObject.Properties) {
        if (Test-ReliableSelfDrop $p.Value $blockName) { return $true }
    }
    return $false
}

$selfDropLines = New-Object System.Collections.Generic.List[string]
foreach ($entry in $lootEntries) {
    $blockName = "minecraft:" + [System.IO.Path]::GetFileNameWithoutExtension($entry.FullName)
    $reader = New-Object System.IO.StreamReader($entry.Open())
    $json = $reader.ReadToEnd()
    $reader.Close()
    try { $lt = $json | ConvertFrom-Json } catch { continue }

    $found = New-Object System.Collections.Generic.HashSet[string]
    Get-LootItems $lt $found

    # A block counts as self-dropping when breaking it can actually yield itself:
    #   - nothing else drops (oak_log, diorite, cobblestone),
    #   - shears turn it into itself (fern, grass, leaves) — tools.tsv records that requirement,
    #     and the planner obtains the shears first, or
    #   - it has an unconditioned fallback branch that yields itself (gravel; flint is only a
    #     rare fortune-gated alternative).
    # Ore is excluded: iron_ore only yields itself under silk touch, and its ordinary drop is
    # raw iron. Counting ore as self-dropping made "mine 9 iron ore" a target that never
    # registered progress, so the bot stripped every ore it could find.
    $others = @($found | Where-Object { $_ -ne $blockName })
    $shearable = $json -match "minecraft:shears"
    $reliableSelf = Test-ReliableSelfDrop $lt $blockName
    if ($found.Contains($blockName) -and ($others.Count -eq 0 -or $shearable -or $reliableSelf)) {
        $selfDropLines.Add($blockName)
    }
}
$selfDropFile = Join-Path (Split-Path -Parent $OutFile) "selfdrop.tsv"
[System.IO.File]::WriteAllLines($selfDropFile, ($selfDropLines | Sort-Object), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "wrote $($selfDropLines.Count) self-dropping blocks -> $selfDropFile"

# ── Tool requirements ────────────────────────────────────────────────────────────────────────
#
# Breaking a block is not the same as harvesting it. A stone pickaxe shatters diamond ore and
# yields nothing; a fern breaks into thin air unless you are holding shears. Without this the
# planner cheerfully sends the bot to "mine" things it physically cannot collect, and the bot
# stands there destroying them.
#
# Tool TYPE comes from the mineable/* tags, tool TIER from the needs_*_tool tags, and the shears
# requirement from the block's own loot table.
#
# Output: tools.tsv   blockId <TAB> pickaxe|axe|shovel|hoe|shears|none <TAB> none|stone|iron|diamond

function Read-BlockTag($zip, $path) {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq $path } | Select-Object -First 1
    if (-not $entry) { return @() }
    $reader = New-Object System.IO.StreamReader($entry.Open())
    $json = $reader.ReadToEnd()
    $reader.Close()
    try { $tag = $json | ConvertFrom-Json } catch { return @() }

    $out = New-Object System.Collections.Generic.List[string]
    foreach ($v in $tag.values) {
        $id = if ($v -is [string]) { $v } elseif ($v.PSObject.Properties.Name -contains "id") { $v.id } else { $null }
        if (-not $id) { continue }
        if ($id.StartsWith("#")) {
            # One level of tag nesting is enough for the vanilla tool tags.
            $nested = "data/minecraft/tags/block/" + ($id.Substring(1) -replace "^minecraft:", "") + ".json"
            foreach ($n in (Read-BlockTag $zip $nested)) { $out.Add($n) }
        } else {
            $out.Add($id)
        }
    }
    return $out
}

$toolOf = @{}
foreach ($kind in @("pickaxe", "axe", "shovel", "hoe")) {
    foreach ($block in (Read-BlockTag $zip "data/minecraft/tags/block/mineable/$kind.json")) {
        $toolOf[$block] = $kind
    }
}

$tierOf = @{}
foreach ($tier in @("stone", "iron", "diamond")) {
    foreach ($block in (Read-BlockTag $zip "data/minecraft/tags/block/needs_${tier}_tool.json")) {
        $tierOf[$block] = $tier
    }
}

# Blocks that only yield themselves to shears (ferns, grass, leaves, cobwebs, vines).
foreach ($entry in $lootEntries) {
    $blockName = "minecraft:" + [System.IO.Path]::GetFileNameWithoutExtension($entry.FullName)
    $reader = New-Object System.IO.StreamReader($entry.Open())
    $json = $reader.ReadToEnd()
    $reader.Close()
    if ($json -match "minecraft:shears" -and $json -match [regex]::Escape($blockName)) {
        $toolOf[$blockName] = "shears"
    }
}

$toolLines = New-Object System.Collections.Generic.List[string]
$allToolBlocks = New-Object System.Collections.Generic.HashSet[string]
foreach ($k in $toolOf.Keys) { [void]$allToolBlocks.Add($k) }
foreach ($k in $tierOf.Keys) { [void]$allToolBlocks.Add($k) }
foreach ($block in ($allToolBlocks | Sort-Object)) {
    $tool = if ($toolOf.ContainsKey($block)) { $toolOf[$block] } else { "none" }
    $tier = if ($tierOf.ContainsKey($block)) { $tierOf[$block] } else { "none" }
    $toolLines.Add("$block`t$tool`t$tier")
}
$toolFile = Join-Path (Split-Path -Parent $OutFile) "tools.tsv"
[System.IO.File]::WriteAllLines($toolFile, $toolLines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "wrote $($toolLines.Count) tool requirements -> $toolFile"

$dropLines = New-Object System.Collections.Generic.List[string]
foreach ($item in ($dropSources.Keys | Sort-Object)) {
    $dropLines.Add("$item`t$(($dropSources[$item] | Sort-Object) -join ',')")
}
$dropFile = Join-Path (Split-Path -Parent $OutFile) "drops.tsv"
[System.IO.File]::WriteAllLines($dropFile, $dropLines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "wrote $($dropLines.Count) drop mappings -> $dropFile"

# ── Naturally generated blocks ───────────────────────────────────────────────────────────────
#
# Knowing the recipes is not enough to plan sensibly. Diorite HAS a recipe (cobblestone + nether
# quartz), but it also generates in every overworld cave — so the sane route is to mine it, not
# to go to the Nether for quartz. Without this the planner happily plans a trip to the Nether and
# the bot digs forever looking for quartz ore in the overworld.
#
# Worldgen features name the blocks they place, and the target predicate reveals the dimension.
#
# Output: natural.tsv   blockId <TAB> overworld|nether|end

function Get-BlockNames($node, $sink) {
    if ($null -eq $node) { return }
    if ($node -is [array]) {
        foreach ($child in $node) { Get-BlockNames $child $sink }
        return
    }
    if ($node -isnot [System.Management.Automation.PSCustomObject]) { return }
    foreach ($p in $node.PSObject.Properties) {
        if ($p.Name -eq "Name" -and $p.Value -is [string]) { [void]$sink.Add([string]$p.Value) }
        else { Get-BlockNames $p.Value $sink }
    }
}

$natural = @{}
$featureEntries = $zip.Entries | Where-Object { $_.FullName -match "^data/minecraft/worldgen/configured_feature/.+\.json$" }
Write-Host "scanning $($featureEntries.Count) worldgen features..."

foreach ($entry in $featureEntries) {
    $reader = New-Object System.IO.StreamReader($entry.Open())
    $json = $reader.ReadToEnd()
    $reader.Close()
    try { $cf = $json | ConvertFrom-Json } catch { continue }

    # The target stone tells us which dimension this feature belongs to.
    $dim = "overworld"
    if ($json -match "base_stone_nether|minecraft:netherrack|nether_ore_replaceables") { $dim = "nether" }
    elseif ($json -match "minecraft:end_stone") { $dim = "end" }

    $found = New-Object System.Collections.Generic.HashSet[string]
    Get-BlockNames $cf $found
    foreach ($block in $found) {
        # Overworld wins ties: a block reachable without a portal is always the better route.
        if (-not $natural.ContainsKey($block) -or $dim -eq "overworld") { $natural[$block] = $dim }
    }
}

$naturalLines = New-Object System.Collections.Generic.List[string]
foreach ($block in ($natural.Keys | Sort-Object)) {
    # Silverfish blocks generate naturally but are never a sane mining target: they drop nothing
    # without silk touch and release a hostile mob. This is a harvester, not a combat bot.
    if ($block -like "minecraft:infested_*") { continue }
    $naturalLines.Add("$block`t$($natural[$block])")
}
$naturalFile = Join-Path (Split-Path -Parent $OutFile) "natural.tsv"
[System.IO.File]::WriteAllLines($naturalFile, $naturalLines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "wrote $($naturalLines.Count) naturally generated blocks -> $naturalFile"

$zip.Dispose()

$lines = $lines | Sort-Object -Unique
$dir = Split-Path -Parent $OutFile
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
[System.IO.File]::WriteAllLines($OutFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "wrote $($lines.Count) recipes -> $OutFile"
if ($skipped.Count -gt 0) {
    Write-Host "skipped unsupported types:"
    $skipped.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { Write-Host "  $($_.Key) x$($_.Value)" }
}
