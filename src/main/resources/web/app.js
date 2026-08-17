'use strict';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

/** Rendering every registered item at once is slow and useless; the search narrows it down. */
const MAX_CELLS = 400;

const state = {
    items: [],
    fuels: [],
    kit: [],
    selected: null,
    lastStatus: null,
};

/* ── HTTP helpers ────────────────────────────────────────────── */

async function getJson(url) {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
    return res.json();
}

async function postJson(url, body) {
    const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    const text = await res.text();
    if (!res.ok) {
        let msg = text;
        try { msg = JSON.parse(text).error ?? text; } catch { /* not json */ }
        throw new Error(msg);
    }
    return text ? JSON.parse(text) : {};
}

/* ── Status ──────────────────────────────────────────────────── */

async function pollStatus() {
    try {
        const s = await getJson('/api/status');
        state.lastStatus = s;
        const el = $('#status');
        if (s.inWorld) {
            el.classList.add('status--on');
            el.classList.remove('status--off');
            $('#status-text').textContent =
                `${s.player} · ${s.x} ${s.y} ${s.z} · ${s.dimension.replace('minecraft:', '')}`;
        } else {
            el.classList.remove('status--on');
            el.classList.add('status--off');
            $('#status-text').textContent = 'not in a world';
        }
    } catch {
        $('#status').classList.remove('status--on');
        $('#status-text').textContent = 'game not reachable';
    }
}

/* ── Item grid ───────────────────────────────────────────────── */

function iconUrl(id) {
    return `/api/icon/${id.replace(':', '/')}.png`;
}

function renderGrid() {
    const q = $('#search').value.trim().toLowerCase();
    const blocksOnly = $('#blocks-only').checked;

    const matches = state.items.filter((it) => {
        if (blocksOnly && !it.block) return false;
        if (!q) return true;
        return it.name.toLowerCase().includes(q) || it.id.includes(q);
    });

    const grid = $('#grid');
    grid.textContent = '';
    $('#grid-empty').hidden = matches.length > 0;

    for (const it of matches.slice(0, MAX_CELLS)) {
        const cell = document.createElement('button');
        cell.className = 'cell';
        cell.title = it.id;

        const img = document.createElement('img');
        img.src = iconUrl(it.id);
        img.alt = '';
        img.loading = 'lazy';
        // A missing icon should not leave a broken-image glyph in the grid.
        img.addEventListener('error', () => { img.style.visibility = 'hidden'; });

        const label = document.createElement('span');
        label.textContent = it.name;

        cell.append(img, label);
        cell.addEventListener('click', () => openSheet(it));
        grid.append(cell);
    }
}

/* ── Order sheet ─────────────────────────────────────────────── */

function openSheet(item) {
    state.selected = item;
    $('#sheet-icon').src = iconUrl(item.id);
    $('#sheet-name').textContent = item.name;
    $('#sheet-id').textContent = item.id;
    $('#sheet-plan').hidden = true;
    $('#sheet').hidden = false;
    $('#sheet-count').focus();
    $('#sheet-count').select();
}

function closeSheet() {
    $('#sheet').hidden = true;
    state.selected = null;
}

function sheetCount() {
    return Math.max(1, Number($('#sheet-count').value) || 1);
}

/* ── Jobs ────────────────────────────────────────────────────── */

async function submitJob(payload) {
    try {
        await postJson('/api/job', payload);
    } catch (e) {
        log(`failed: ${e.message}`, 'err');
    }
}

/** Drops the newest screenshot into the log as a clickable thumbnail. */
function showLatestShot() {
    const li = document.createElement('li');
    const img = document.createElement('img');
    // Cache-buster: the URL is constant but the picture behind it changes.
    img.src = `/api/screenshot/latest?t=${Date.now()}`;
    img.className = 'shot-thumb';
    img.alt = 'latest screenshot';
    img.addEventListener('click', () => window.open(img.src, '_blank'));
    img.addEventListener('error', () => { li.textContent = 'no screenshot available'; });
    li.append(img);
    const list = $('#log');
    list.append(li);
    list.scrollTop = list.scrollHeight;
}

function log(line, cls) {
    const li = document.createElement('li');
    li.textContent = line;
    if (cls) li.className = cls;
    const list = $('#log');
    list.append(li);
    list.scrollTop = list.scrollHeight;
}

/** Live job status and log lines pushed from the mod. */
function openEventStream() {
    const es = new EventSource('/api/events');

    es.addEventListener('log', (e) => {
        const d = JSON.parse(e.data);
        log(d.line, d.level);
        // A failure snapshot ends with the screenshot note; show the picture inline.
        if (d.line.includes('screenshot saved')) {
            setTimeout(showLatestShot, 600);
        }
    });

    es.addEventListener('job', (e) => {
        const d = JSON.parse(e.data);
        $('#job-title').textContent = (d.paused ? 'Paused — ' : '') + (d.title || 'Idle');
        $('#progress-bar').style.width = `${Math.round((d.progress ?? 0) * 100)}%`;
        $('#stop').hidden = !d.active;
        const pause = $('#pause');
        pause.hidden = !d.active;
        pause.textContent = d.paused ? 'Resume' : 'Pause';
        pause.classList.toggle('btn--go', d.paused);
    });

    // EventSource retries on its own, but a mod restart closes the stream for good.
    es.addEventListener('error', () => {
        if (es.readyState === EventSource.CLOSED) setTimeout(openEventStream, 2000);
    });
}

/* ── Coordinates ─────────────────────────────────────────────── */

function fillCoords(prefix) {
    const s = state.lastStatus;
    if (!s || !s.inWorld) {
        log('cannot read player position — not in a world', 'err');
        return;
    }
    $(`#${prefix}x`).value = s.x;
    $(`#${prefix}y`).value = s.y;
    $(`#${prefix}z`).value = s.z;
    updateExcavateSize();
    updateBuildSize();
}

function coords(prefix) {
    const n = (id) => {
        const v = $(`#${id}`).value;
        return v === '' ? null : Number(v);
    };
    const c = { x: n(prefix + 'x'), y: n(prefix + 'y'), z: n(prefix + 'z') };
    return (c.x === null || c.y === null || c.z === null) ? null : c;
}

function span(a, b) {
    return {
        dx: Math.abs(a.x - b.x) + 1,
        dy: Math.abs(a.y - b.y) + 1,
        dz: Math.abs(a.z - b.z) + 1,
    };
}

function updateExcavateSize() {
    const a = coords('a');
    const b = coords('b');
    const out = $('#excavate-size');
    if (!a || !b) { out.textContent = ''; return; }
    const { dx, dy, dz } = span(a, b);
    out.textContent = `${dx} × ${dy} × ${dz} = ${(dx * dy * dz).toLocaleString()} blocks`;
}

function updateBuildSize() {
    const a = coords('ba');
    const b = coords('bb');
    const out = $('#build-size');
    if (!a || !b) { out.textContent = ''; return; }
    const { dx, dy, dz } = span(a, b);

    // Mirrors the server-side estimate so the block count shown matches what it gathers.
    const shape = $('#build-shape').value;
    let blocks = dx * dy * dz;
    if (shape === 'hollow') {
        blocks -= Math.max(0, dx - 2) * Math.max(0, dy - 2) * Math.max(0, dz - 2);
    } else if (shape === 'walls') {
        blocks = Math.max(0, 2 * dx + 2 * dz - 4) * dy;
    }
    out.textContent = `${dx} × ${dy} × ${dz} — about ${blocks.toLocaleString()} blocks needed`;
}

function updateTreeNote() {
    const count = Math.max(0, Number($('#tree-count').value) || 0);
    const replants = $('#tree-mode').value === 'loop';
    $('#tree-count-note').textContent = count === 0
        ? `0 means keep going until you press Stop${replants ? ', replanting as it goes' : ''}.`
        : `Stops once ${count} more logs are collected${replants ? ', replanting as it goes' : ''}.`;
}

/* ── Wiring ──────────────────────────────────────────────────── */

function wire() {
    // Tabs, and the per-tab data each one needs fresh.
    $$('.tab').forEach((tab) => tab.addEventListener('click', () => {
        $$('.tab').forEach((t) => t.classList.toggle('is-active', t === tab));
        $$('.panel').forEach((p) => p.classList.toggle('is-active', p.dataset.panel === tab.dataset.tab));

        if (tab.dataset.tab === 'places') {
            loadChest();
            loadStations();
            loadWaypoints();
        } else if (tab.dataset.tab === 'settings') {
            loadSettings();
            loadMaterials();
            loadFuels();
        }
    }));

    // Sub-nav inside Tasks.
    $$('.seg').forEach((seg) => seg.addEventListener('click', () => {
        $$('.seg').forEach((s) => s.classList.toggle('is-active', s === seg));
        $$('.sub').forEach((s) => s.classList.toggle('is-active', s.dataset.sub === seg.dataset.sub));
    }));

    $('#search').addEventListener('input', renderGrid);
    $('#blocks-only').addEventListener('change', renderGrid);

    /* Order sheet */
    $('#sheet-cancel').addEventListener('click', closeSheet);
    $('#sheet').addEventListener('click', (e) => { if (e.target === $('#sheet')) closeSheet(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeSheet(); });

    $$('.quick button').forEach((b) => b.addEventListener('click', () => {
        $('#sheet-count').value = b.dataset.count;
    }));

    $('#sheet-go').addEventListener('click', async () => {
        const item = state.selected;
        if (!item) return;
        const count = sheetCount();
        closeSheet();
        await submitJob({ type: 'acquire', item: item.id, count });
    });

    // Straight from the picker into the batch list, so building a list does not mean
    // typing ids by hand.
    $('#sheet-add').addEventListener('click', () => {
        const item = state.selected;
        if (!item) return;
        addKit(item.id, sheetCount());
        closeSheet();
        $('#list-card').open = true;
        loadKits();
    });

    $('#sheet-count').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') $('#sheet-go').click();
    });

    // Preview asks the planner what it would do without committing to it. Worth a look before
    // handing over an hour of mining.
    $('#sheet-preview').addEventListener('click', previewPlan);
    $('#plan-close').addEventListener('click', () => { $('#sheet-plan').hidden = true; });

    $('#inv-card').addEventListener('toggle', () => { if ($('#inv-card').open) loadInventory(); });

    /* Coordinates */
    $$('[data-fill]').forEach((b) => b.addEventListener('click', () => fillCoords(b.dataset.fill)));
    ['ax', 'ay', 'az', 'bx', 'by', 'bz'].forEach((id) =>
        $(`#${id}`).addEventListener('input', updateExcavateSize));
    ['bax', 'bay', 'baz', 'bbx', 'bby', 'bbz'].forEach((id) =>
        $(`#${id}`).addEventListener('input', updateBuildSize));
    $('#build-shape').addEventListener('change', updateBuildSize);

    /* Trees */
    $('#tree-count').addEventListener('input', updateTreeNote);
    $('#tree-mode').addEventListener('change', updateTreeNote);
    updateTreeNote();

    $('[data-job="tree"]').addEventListener('click', () => submitJob({
        type: 'tree',
        log: $('#tree-kind').value,
        mode: $('#tree-mode').value,
        count: Math.max(0, Number($('#tree-count').value) || 0),
    }));

    /* Excavate */
    $('[data-job="excavate"]').addEventListener('click', () => {
        const a = coords('a');
        const b = coords('b');
        if (!a || !b) {
            log('excavation needs both corners', 'err');
            return;
        }
        submitJob({ type: 'excavate', ax: a.x, ay: a.y, az: a.z, bx: b.x, by: b.y, bz: b.z });
    });

    /* Build */
    $('[data-job="buildbox"]').addEventListener('click', () => {
        const a = coords('ba');
        const b = coords('bb');
        const block = $('#build-block').value.trim();
        if (!a || !b) {
            log('the build needs both corners', 'err');
            return;
        }
        if (!block) {
            log('choose a block to build with', 'err');
            return;
        }
        submitJob({
            type: 'build',
            block: block.includes(':') ? block : `minecraft:${block}`,
            shape: $('#build-shape').value,
            ax: a.x, ay: a.y, az: a.z,
            bx: b.x, by: b.y, bz: b.z,
        });
    });

    /* Places */
    $('[data-job="surface"]').addEventListener('click', () => submitJob({ type: 'surface' }));
    $('[data-job="portal"]').addEventListener('click', () => submitJob({ type: 'portal' }));

    $('#chest-set').addEventListener('click', async () => {
        try {
            const r = await postJson('/api/chest', {});
            log(`home chest set at ${r.at}`, 'ok');
            loadChest();
        } catch (e) {
            log(`could not set home chest: ${e.message}`, 'err');
        }
    });
    $('#chest-go').addEventListener('click', () => submitJob({ type: 'deposit' }));

    $$('[data-assign]').forEach((b) => b.addEventListener('click', async () => {
        try {
            const r = await postJson('/api/stations', { kind: b.dataset.assign });
            log(`${r.kind} assigned at ${r.at}`, 'ok');
            loadStations();
        } catch (e) {
            log(`could not assign: ${e.message}`, 'err');
        }
    }));

    $$('[data-clear]').forEach((b) => b.addEventListener('click', async () => {
        try {
            await postJson('/api/stations', { kind: b.dataset.clear, clear: 'true' });
            log(`${b.dataset.clear} assignment cleared`, 'ok');
            loadStations();
        } catch (e) {
            log(`could not clear: ${e.message}`, 'err');
        }
    }));

    $('#wp-save').addEventListener('click', saveWaypoint);
    $('#wp-name').addEventListener('keydown', (e) => { if (e.key === 'Enter') saveWaypoint(); });

    /* Lists / kits */
    $('#kit-add').addEventListener('click', addKitLine);
    $('#kit-item').addEventListener('keydown', (e) => { if (e.key === 'Enter') addKitLine(); });
    $('#list-card').addEventListener('toggle', () => { if ($('#list-card').open) loadKits(); });

    $('#kit-run').addEventListener('click', async () => {
        if (state.kit.length === 0) {
            log('the list is empty', 'err');
            return;
        }
        try {
            const r = await postJson('/api/job/batch', { items: kitSpec() });
            log(`queued ${r.queued} item(s)`, 'ok');
        } catch (e) {
            log(`could not queue the list: ${e.message}`, 'err');
        }
    });

    $('#kit-save').addEventListener('click', async () => {
        const name = $('#kit-name').value.trim();
        if (!name || state.kit.length === 0) {
            log('give the list a name and at least one item', 'err');
            return;
        }
        try {
            await postJson('/api/kits', { name, items: kitSpec() });
            log(`saved list "${name}"`, 'ok');
            $('#kit-name').value = '';
            loadKits();
        } catch (e) {
            log(`could not save list: ${e.message}`, 'err');
        }
    });

    /* Settings */
    wireSettings();
    $('#mat-save').addEventListener('click', saveMaterials);
    $('#fuel-only-have').addEventListener('change', renderFuels);
    $('#fuel-save').addEventListener('click', saveFuels);

    /* Dock */
    $('#stop').addEventListener('click', () => postJson('/api/job/stop', {}).catch(() => {}));
    // No body: the server flips whichever way it is currently set.
    $('#pause').addEventListener('click', () => postJson('/api/job/pause', {}).catch(
        (e) => log(`could not pause: ${e.message}`, 'err')));
    $('#dock-toggle').addEventListener('click', () => $('#dock').classList.toggle('is-collapsed'));

    $('#shot').addEventListener('click', async () => {
        try {
            await postJson('/api/screenshot/take', {});
            // The write happens off-thread, so give it a moment before fetching.
            setTimeout(showLatestShot, 600);
        } catch (e) {
            log(`screenshot failed: ${e.message}`, 'err');
        }
    });

    $('#drop-junk').addEventListener('click', async () => {
        try {
            await postJson('/api/inventory/drop', {});
        } catch (e) {
            log(`drop failed: ${e.message}`, 'err');
        }
    });

    // Diagnostics land in the log via SSE, so nothing needs rendering here.
    $('#diagnose').addEventListener('click', async () => {
        try {
            await getJson('/api/diagnostics');
        } catch (e) {
            log(`diagnose failed: ${e.message}`, 'err');
        }
    });

    $('#copy-log').addEventListener('click', async () => {
        const text = Array.from($('#log').children).map((li) => li.textContent).join('\n');
        try {
            await navigator.clipboard.writeText(text);
            log('log copied to clipboard', 'ok');
        } catch {
            // Clipboard needs a secure context; loopback usually counts, but not always.
            log('could not copy — select the log text manually', 'err');
        }
    });
}

/* ── Inventory ───────────────────────────────────────────────── */

async function loadInventory() {
    try {
        const data = await getJson('/api/inventory');
        const ul = $('#inv-list');
        ul.textContent = '';
        $('#inv-empty').hidden = data.items.length > 0;
        $('#inv-summary').textContent =
            `${data.items.length} kind(s) · ${data.free} free slot(s)`;

        for (const it of data.items) {
            const li = document.createElement('li');
            li.classList.toggle('is-equipped', it.equipped);
            li.title = it.equipped ? `${it.id} (in hand)` : it.id;

            const img = document.createElement('img');
            img.src = iconUrl(it.id);
            img.alt = '';
            img.loading = 'lazy';
            img.addEventListener('error', () => { img.style.visibility = 'hidden'; });

            const name = document.createElement('span');
            name.className = 'inv-name';
            name.textContent = it.name;

            const count = document.createElement('span');
            count.className = 'inv-count';
            count.textContent = `×${it.count}`;

            li.append(img, name, count);

            // Durability is only meaningful for tools; -1 means it does not wear out.
            if (it.durability >= 0) {
                const dur = document.createElement('span');
                dur.className = 'inv-dur';
                // The tool belt treats anything under 10% as already gone.
                dur.classList.toggle('is-low', it.durability < 10);
                dur.textContent = `${it.durability}%`;
                li.append(dur);
            }
            ul.append(li);
        }
    } catch (e) {
        log(`could not read the inventory: ${e.message}`, 'err');
    }
}

/* ── Plan preview ────────────────────────────────────────────── */

async function previewPlan() {
    const item = state.selected;
    if (!item) return;
    const box = $('#sheet-plan');
    const list = $('#plan-steps');
    list.textContent = '';
    box.hidden = false;

    const waiting = document.createElement('li');
    waiting.textContent = 'working it out…';
    list.append(waiting);

    try {
        const r = await getJson(
            `/api/plan?item=${encodeURIComponent(item.id)}&count=${sheetCount()}`);
        list.textContent = '';

        if (!r.ok) {
            const li = document.createElement('li');
            li.className = 'plan-err';
            li.dataset.kind = 'no';
            li.textContent = r.error;
            list.append(li);
            return;
        }
        if (r.steps.length === 0) {
            const li = document.createElement('li');
            li.dataset.kind = '—';
            li.textContent = 'nothing to do';
            list.append(li);
            return;
        }
        for (const step of r.steps) {
            const li = document.createElement('li');
            li.className = `k-${step.kind}`;
            li.dataset.kind = step.kind;
            // The server already renders each step as a sentence; reuse it rather than
            // rebuilding the wording here and letting the two drift apart.
            li.textContent = step.text.replace(new RegExp(`^${step.kind}\s+`, 'i'), '');
            list.append(li);
        }
    } catch (e) {
        list.textContent = '';
        const li = document.createElement('li');
        li.className = 'plan-err';
        li.dataset.kind = 'err';
        li.textContent = e.message;
        list.append(li);
    }
}

/* ── Queue ───────────────────────────────────────────────────── */

async function pollQueue() {
    try {
        const q = await getJson('/api/queue');
        const list = $('#queue-list');
        list.textContent = '';
        $('#queue-count').textContent = q.waiting.length;
        $('#queue-empty').hidden = q.waiting.length > 0;

        q.waiting.forEach((title, index) => {
            const li = document.createElement('li');

            const name = document.createElement('span');
            name.className = 'q-name';
            name.textContent = title;
            name.title = title;

            const drop = document.createElement('button');
            drop.className = 'q-drop';
            drop.textContent = '×';
            drop.title = 'Remove from the queue';
            drop.addEventListener('click', async () => {
                try {
                    await postJson('/api/queue/remove', { index });
                    pollQueue();
                } catch (e) {
                    log(`could not remove: ${e.message}`, 'err');
                }
            });

            li.append(name, drop);
            list.append(li);
        });
    } catch {
        // Panel open with no game running; leave the last view in place.
    }
}

/* ── Lists / kits ────────────────────────────────────────────── */

function renderKitLines() {
    const ul = $('#kit-lines');
    ul.textContent = '';
    $('#kit-empty').hidden = state.kit.length > 0;

    state.kit.forEach((line, index) => {
        const li = document.createElement('li');

        const name = document.createElement('span');
        name.className = 'r-name';
        name.textContent = line.item.replace('minecraft:', '');

        const count = document.createElement('span');
        count.className = 'r-meta';
        count.textContent = `× ${line.count}`;

        const remove = document.createElement('button');
        remove.className = 'btn btn--sm';
        remove.textContent = 'Remove';
        remove.addEventListener('click', () => {
            state.kit.splice(index, 1);
            renderKitLines();
        });

        li.append(name, count, remove);
        ul.append(li);
    });
}

/** Adding the same item twice tops up the existing line rather than duplicating it. */
function addKit(item, count) {
    const existing = state.kit.find((l) => l.item === item);
    if (existing) {
        existing.count += count;
    } else {
        state.kit.push({ item, count });
    }
    renderKitLines();
}

function addKitLine() {
    const input = $('#kit-item');
    const raw = input.value.trim();
    if (!raw) return;
    addKit(raw.includes(':') ? raw : `minecraft:${raw}`,
           Math.max(1, Number($('#kit-count').value) || 1));
    input.value = '';
    input.focus();
}

function kitSpec() {
    return state.kit.map((l) => `${l.item}*${l.count}`).join(',');
}

async function loadKits() {
    try {
        const data = await getJson('/api/kits');
        const ul = $('#kit-saved');
        ul.textContent = '';
        $('#kit-saved-empty').hidden = data.kits.length > 0;

        for (const kit of data.kits) {
            const li = document.createElement('li');

            const name = document.createElement('span');
            name.className = 'r-name';
            name.textContent = kit.name;

            const summary = document.createElement('span');
            summary.className = 'r-meta';
            summary.textContent = kit.items.replace(/minecraft:/g, '');
            summary.title = kit.items;

            const run = document.createElement('button');
            run.className = 'btn btn--sm btn--go';
            run.textContent = 'Run';
            run.addEventListener('click', async () => {
                try {
                    const r = await postJson('/api/job/batch', { kit: kit.name });
                    log(`queued ${r.queued} item(s) from "${kit.name}"`, 'ok');
                } catch (e) {
                    log(`could not run list: ${e.message}`, 'err');
                }
            });

            const load = document.createElement('button');
            load.className = 'btn btn--sm';
            load.textContent = 'Edit';
            load.addEventListener('click', () => {
                state.kit = kit.items.split(',').filter(Boolean).map((tok) => {
                    const [item, count] = tok.split('*');
                    return { item, count: Number(count) || 1 };
                });
                $('#kit-name').value = kit.name;
                renderKitLines();
            });

            const del = document.createElement('button');
            del.className = 'btn btn--sm';
            del.textContent = 'Delete';
            del.addEventListener('click', async () => {
                try {
                    await postJson('/api/kits/delete', { name: kit.name });
                    loadKits();
                } catch (e) {
                    log(`delete failed: ${e.message}`, 'err');
                }
            });

            li.append(name, summary, run, load, del);
            ul.append(li);
        }
    } catch (e) {
        log(`could not load lists: ${e.message}`, 'err');
    }
}

/* ── Toggles ─────────────────────────────────────────────────── */

async function loadSettings() {
    try {
        const s = await getJson('/api/settings');
        $$('[data-setting]').forEach((box) => {
            box.checked = Boolean(s[box.dataset.setting]);
        });
        $('#set-deposit').value = s.depositAtFreeSlots ?? 3;
    } catch (e) {
        log(`could not load settings: ${e.message}`, 'err');
    }
}

function wireSettings() {
    const deposit = $('#set-deposit');
    deposit.addEventListener('change', async () => {
        const value = Math.max(0, Math.min(30, Number(deposit.value) || 0));
        deposit.value = value;
        try {
            await postJson('/api/settings', { depositAtFreeSlots: String(value) });
            log(`will unload with ${value} slot(s) left`, 'ok');
        } catch (e) {
            log(`could not save setting: ${e.message}`, 'err');
        }
    });

    $$('[data-setting]').forEach((box) => {
        box.addEventListener('change', async () => {
            try {
                // Saves immediately: a toggle you have to remember to save is a toggle that
                // silently does not apply.
                await postJson('/api/settings', { [box.dataset.setting]: String(box.checked) });
                log(`${box.dataset.setting} ${box.checked ? 'on' : 'off'}`, 'ok');
            } catch (e) {
                log(`could not save setting: ${e.message}`, 'err');
                box.checked = !box.checked;
            }
        });
    });
}

/* ── Materials ───────────────────────────────────────────────── */

async function loadMaterials() {
    try {
        const m = await getJson('/api/materials');
        $('#mat-prefer').value = (m.preferred || '').split(',').filter(Boolean).join('\n');
        $('#mat-ban').value = (m.banned || '').split(',').filter(Boolean).join('\n');
    } catch (e) {
        log(`could not load materials: ${e.message}`, 'err');
    }
}

async function saveMaterials() {
    // Textareas are one id per line; the API takes comma-separated.
    const lines = (el) => el.value.split('\n')
        .map((s) => s.trim())
        .filter(Boolean)
        .map((s) => (s.includes(':') ? s : `minecraft:${s}`));

    try {
        await postJson('/api/materials', {
            preferred: lines($('#mat-prefer')).join(','),
            banned: lines($('#mat-ban')).join(','),
        });
        log('material preferences saved', 'ok');
        loadMaterials();
    } catch (e) {
        log(`could not save materials: ${e.message}`, 'err');
    }
}

/* ── Fuel ────────────────────────────────────────────────────── */

async function loadFuels() {
    try {
        const data = await getJson('/api/fuels');
        state.fuels = data.fuels;
        renderFuels();
    } catch (e) {
        log(`could not load fuels: ${e.message}`, 'err');
    }
}

function renderFuels() {
    const onlyHave = $('#fuel-only-have').checked;
    const shown = onlyHave ? state.fuels.filter((f) => f.have > 0) : state.fuels;

    const ul = $('#fuel-list');
    ul.textContent = '';
    $('#fuel-empty').hidden = shown.length > 0;

    shown.forEach((fuel) => {
        const li = document.createElement('li');
        li.classList.toggle('is-blocked', fuel.blocked);

        const tick = document.createElement('input');
        tick.type = 'checkbox';
        tick.checked = !fuel.blocked;
        tick.addEventListener('change', () => {
            fuel.blocked = !tick.checked;
            li.classList.toggle('is-blocked', fuel.blocked);
        });

        const name = document.createElement('span');
        name.className = 'fuel-name';
        name.textContent = fuel.name;

        const meta = document.createElement('span');
        meta.className = 'fuel-meta';
        meta.textContent = `smelts ${fuel.smelts}${fuel.have ? ` · have ${fuel.have}` : ''}`;

        // Order in the list is the order the bot reaches for them.
        const up = document.createElement('button');
        up.className = 'btn fuel-move';
        up.textContent = '↑';
        up.title = 'Use this sooner';
        up.addEventListener('click', () => moveFuel(fuel, -1));

        const down = document.createElement('button');
        down.className = 'btn fuel-move';
        down.textContent = '↓';
        down.title = 'Use this later';
        down.addEventListener('click', () => moveFuel(fuel, 1));

        li.append(tick, name, meta, up, down);
        ul.append(li);
    });
}

function moveFuel(fuel, delta) {
    const from = state.fuels.indexOf(fuel);
    const to = from + delta;
    if (to < 0 || to >= state.fuels.length) return;
    state.fuels.splice(from, 1);
    state.fuels.splice(to, 0, fuel);
    renderFuels();
}

async function saveFuels() {
    const blocked = state.fuels.filter((f) => f.blocked).map((f) => f.id);
    const preferred = state.fuels.filter((f) => !f.blocked).map((f) => f.id);
    try {
        await postJson('/api/fuels', { blocked: blocked.join(','), preferred: preferred.join(',') });
        log(`fuel settings saved — ${blocked.length} blocked`, 'ok');
    } catch (e) {
        log(`could not save fuel settings: ${e.message}`, 'err');
    }
}

/* ── Places ──────────────────────────────────────────────────── */

async function loadChest() {
    try {
        const c = await getJson('/api/chest');
        $('#chest-state').textContent = c.set
            ? `${c.x} ${c.y} ${c.z} · ${(c.dimension || '').replace('minecraft:', '')}`
            : 'not set';
    } catch {
        $('#chest-state').textContent = 'unknown';
    }
}

async function loadStations() {
    try {
        const s = await getJson('/api/stations');
        for (const kind of ['table', 'furnace']) {
            const at = s[kind];
            $(`#station-${kind}`).textContent = at
                ? `${kind}: ${at.x} ${at.y} ${at.z}`
                : `${kind}: any`;
        }
    } catch {
        // Not in a world yet.
    }
}

async function loadWaypoints() {
    try {
        const data = await getJson('/api/waypoints');
        renderWaypoints(data.waypoints);
    } catch (e) {
        log(`could not load saved places: ${e.message}`, 'err');
    }
}

function renderWaypoints(list) {
    const ul = $('#wp-list');
    ul.textContent = '';
    $('#wp-empty').hidden = list.length > 0;

    for (const wp of list) {
        const li = document.createElement('li');

        const name = document.createElement('span');
        name.className = 'r-name';
        name.textContent = wp.name;

        const pos = document.createElement('span');
        pos.className = 'r-meta';
        pos.textContent = `${wp.x} ${wp.y} ${wp.z} · ${wp.dimension.replace('minecraft:', '')}`;

        const go = document.createElement('button');
        go.className = 'btn btn--sm btn--go';
        go.textContent = 'Go';
        go.addEventListener('click', () => submitJob({ type: 'travel', name: wp.name }));

        const del = document.createElement('button');
        del.className = 'btn btn--sm';
        del.textContent = 'Delete';
        del.addEventListener('click', async () => {
            try {
                await postJson('/api/waypoints/delete', { name: wp.name });
                loadWaypoints();
            } catch (e) {
                log(`delete failed: ${e.message}`, 'err');
            }
        });

        li.append(name, pos, go, del);
        ul.append(li);
    }
}

async function saveWaypoint() {
    const input = $('#wp-name');
    const name = input.value.trim();
    if (!name) {
        log('give the place a name first', 'err');
        return;
    }
    try {
        const wp = await postJson('/api/waypoints', { name });
        log(`saved "${wp.name}" at ${wp.x} ${wp.y} ${wp.z}`, 'ok');
        input.value = '';
        loadWaypoints();
    } catch (e) {
        log(`save failed: ${e.message}`, 'err');
    }
}

/* ── Catalogue-driven inputs ─────────────────────────────────── */

/** Autocomplete for the build and list fields, placeable items only. */
function populateBlockList() {
    const list = $('#block-list');
    for (const it of state.items.filter((i) => i.block)) {
        const opt = document.createElement('option');
        opt.value = it.id;
        opt.textContent = it.name;
        list.append(opt);
    }
}

function populateLogTypes() {
    const sel = $('#tree-kind');
    const logs = state.items.filter((it) => /(^|:)[a-z_]*_log$/.test(it.id));
    for (const it of logs) {
        const opt = document.createElement('option');
        opt.value = it.id;
        opt.textContent = it.name;
        sel.append(opt);
    }
}

/* ── Boot ────────────────────────────────────────────────────── */

async function boot() {
    wire();
    openEventStream();
    pollStatus();
    setInterval(pollStatus, 1500);
    pollQueue();
    setInterval(pollQueue, 2000);
    loadWaypoints();
    loadChest();
    loadStations();
    loadSettings();
    renderKitLines();

    try {
        const data = await getJson('/api/items');
        state.items = data.items;
        renderGrid();
        populateLogTypes();
        populateBlockList();
    } catch (e) {
        log(`could not load the item list: ${e.message}`, 'err');
    }
}

boot();
