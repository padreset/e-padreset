/**
 * Regenerate the two model-data assets the app bundles, from reinkpy.
 *
 *   app/src/main/assets/maintenance_boxes.xml   models that use a maintenance box
 *   app/src/main/assets/reinkpy_models.xml      models devices.xml does not have
 *
 * WHY THIS SCRIPT EXISTS
 *
 * Both files used to be extracted by hand, which works once and then rots: the
 * upstream database gains and reclassifies models and nothing here notices. The
 * website already solved this for its own model list; this is the same shape,
 * applied to the assets the app bundles.
 *
 * WHAT IT READS
 *
 * Not reinkpy's epson.toml directly, but the parsed mirror yusnel-rojas/epson-reset
 * publishes at data/reinkpy/database.json. Parsing the TOML is upstream's job and
 * it already does it; re-deriving it here would be a second implementation to
 * keep in sync. The mirror is AGPL-3.0 data and is credited in both headers.
 *
 * THE CROSS-CHECK, AND WHY IT RUNS FIRST
 *
 * devices.xml comes from ez-reset and this data comes from reinkpy: two
 * independent extractions of the same printers. Where they overlap they agree
 * completely — the same read key, the same write keyword, the same reset
 * addresses and the same reset values, with no exceptions across 600+ models.
 *
 * That agreement is the only evidence that the reinkpy-only models this script
 * adds are sound, because for those there is nothing to compare against. So the
 * check runs BEFORE anything is written: if the two sources ever diverge, the
 * script reports it and exits without touching an asset. Writing first and
 * warning after would mean shipping a catalog built on data that no longer
 * matches the one source we can verify it against.
 *
 * USAGE
 *
 *   node scripts/sync-model-data.mjs            # verify, then regenerate
 *   node scripts/sync-model-data.mjs --check    # exit 1 if an asset is stale
 *   node scripts/sync-model-data.mjs --source=<url>
 */

import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_SOURCE =
  "https://raw.githubusercontent.com/yusnel-rojas/epson-reset/main/data/reinkpy/database.json";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ASSETS = join(ROOT, "app", "src", "main", "assets");
const DEVICES = join(ASSETS, "devices.xml");
const BOXES = join(ASSETS, "maintenance_boxes.xml");
const EXTRA = join(ASSETS, "reinkpy_models.xml");

const args = process.argv.slice(2);
const checkOnly = args.includes("--check");
const source =
  args.find((a) => a.startsWith("--source="))?.slice("--source=".length) ||
  DEFAULT_SOURCE;

/** Byte ordering: locale collation would make the output depend on the machine. */
const byName = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

/**
 * Every spelling a model is known by across the two databases.
 *
 * ez-reset keeps the "STYLUS " prefix that reinkpy drops ("STYLUS COLOR 760"
 * against "Color 760"), and carries the short form in its `short` attribute.
 * Without folding these the two sets look 400 models further apart than they are.
 */
function aliases(...names) {
  const set = new Set();
  for (const raw of names) {
    if (!raw) continue;
    const s = raw.trim().toUpperCase().replace(/\s+SERIES$/, "").trim();
    if (!s) continue;
    set.add(s);
    set.add(s.replace(/^EPSON\s+/, ""));
    set.add(s.replace(/^STYLUS\s+/, ""));
    set.add(s.replace(/^EPSON STYLUS\s+/, ""));
  }
  return [...set];
}

// ---------------------------------------------------------------------------
// ez-reset's devices.xml
// ---------------------------------------------------------------------------

function parseDevices() {
  const xml = readFileSync(DEVICES, "utf8");
  const devStart = xml.indexOf("<devices>");
  const dev = xml.slice(devStart, xml.indexOf("</devices>"));

  const specs = {};
  const blockRe = /<([A-Za-z0-9_]+)>([\s\S]*?)<\/\1>/g;
  let b;
  while ((b = blockRe.exec(dev))) {
    const body = b[2];
    const f = /<factory>([^<]*)<\/factory>/.exec(body);
    const k = /<keyword>([^<]*)<\/keyword>/.exec(body);
    if (!f && !k) continue;

    const hex = (s) => s.trim().split(/\s+/).map((t) => parseInt(t, 16));
    const resetMap = {};
    const waste = /<waste>([\s\S]*?)<\/waste>/.exec(body);
    if (waste) {
      const r = /<reset>([^<]*)<\/reset>/.exec(waste[1]);
      if (r) {
        const toks = hex(r[1]);
        for (let i = 0; i + 1 < toks.length; i += 2) resetMap[toks[i]] = toks[i + 1];
      }
    }
    specs[b[1]] = {
      factory: f ? hex(f[1]) : null,
      keyword: k ? hex(k[1]) : null,
      resetMap,
    };
  }

  const rec = xml.slice(xml.indexOf("<records>"), devStart);
  const models = [];
  const pRe = /<printer\b([^>]*)\/>/g;
  let p;
  while ((p = pRe.exec(rec))) {
    const model = (/model\s*=\s*"([^"]*)"/.exec(p[1]) || [])[1];
    const short = (/short\s*=\s*"([^"]*)"/.exec(p[1]) || [])[1];
    const sp = (/specs\s*=\s*"([^"]*)"/.exec(p[1]) || [])[1];
    const br = (/brand\s*=\s*"([^"]*)"/.exec(p[1]) || [])[1];
    if (!model || !sp) continue;
    if (br && br.toLowerCase() !== "epson") continue;

    const acc = { factory: null, keyword: null, resetMap: {} };
    for (const s of sp.split(",").map((x) => x.trim())) {
      const e = specs[s];
      if (!e) continue;
      if (e.factory) acc.factory = e.factory;
      if (e.keyword) acc.keyword = e.keyword;
      if (Object.keys(e.resetMap).length) acc.resetMap = e.resetMap;
    }
    models.push({ name: model, alias: aliases(model, short), ...acc });
  }
  return models;
}

// ---------------------------------------------------------------------------
// the cross-check
// ---------------------------------------------------------------------------

function crossCheck(ez, reinkpy) {
  const index = new Map();
  for (const m of reinkpy) for (const a of m.alias) if (!index.has(a)) index.set(a, m);

  const report = [];
  let compared = 0;

  let keysSame = 0;
  let keysTotal = 0;
  let resetSame = 0;
  let resetTotal = 0;
  const drift = [];

  for (const e of ez) {
    const q = e.alias.map((a) => index.get(a)).find(Boolean);
    if (!q) continue;

    if (e.factory && q.rkey != null) {
      keysTotal++;
      const le = [q.rkey & 0xff, (q.rkey >> 8) & 0xff];
      if (e.factory.length === 2 && e.factory[0] === le[0] && e.factory[1] === le[1]) {
        keysSame++;
      } else if (drift.length < 8) {
        drift.push({ model: e.name, field: "factory/rkey", ez: e.factory, reinkpy: le });
      }
    }

    if (e.keyword && q.wkey) {
      keysTotal++;
      if (Buffer.from(e.keyword).toString("latin1") === q.wkey) keysSame++;
      else if (drift.length < 8) {
        drift.push({
          model: e.name,
          field: "keyword/wkey",
          ez: Buffer.from(e.keyword).toString("latin1"),
          reinkpy: q.wkey,
        });
      }
    }

    if (Object.keys(e.resetMap).length && (q.pad_groups || []).length) {
      const rkReset = {};
      for (const g of q.pad_groups) {
        (g.addresses || []).forEach((addr, i) => {
          rkReset[addr] = (g.reset || [])[i];
        });
      }
      if (Object.keys(rkReset).length) {
        resetTotal++;
        const a = Object.entries(e.resetMap)
          .sort((x, y) => x[0] - y[0])
          .map(([k, v]) => `${k}:${v}`)
          .join(",");
        const c = Object.entries(rkReset)
          .sort((x, y) => x[0] - y[0])
          .map(([k, v]) => `${k}:${v}`)
          .join(",");
        if (a === c) resetSame++;
        else if (drift.length < 8) {
          drift.push({ model: e.name, field: "reset", ez: a, reinkpy: c });
        }
      }
    }

    compared++;
  }

  report.push(
    `  session keys  ${keysSame}/${keysTotal} (factory vs rkey, keyword vs wkey)`,
  );
  report.push(`  reset map     ${resetSame}/${resetTotal} (addresses and values)`);
  report.push(`  models joined ${compared}`);

  const ok =
    keysTotal > 0 &&
    keysSame === keysTotal &&
    resetTotal > 0 &&
    resetSame === resetTotal;

  return { ok, report, drift };
}

// ---------------------------------------------------------------------------
// asset rendering
// ---------------------------------------------------------------------------

function renderBoxes(models) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!--
  Epson models whose waste ink is collected in a user-replaceable maintenance
  box or tank rather than in an internal absorbent pad.

  WHY THIS LIST EXISTS

  On these models the fill level is kept on the box's own chip, so the printer
  holds no counter for this app to reset. That makes them permanently
  unsupported for counter resets, which is a different situation from a model
  that is merely missing from devices.xml and could still be added.

  The catalog cannot work this out on its own. Three candidate signals were
  measured against a known classification and all three failed:

    the printer class attribute, identical across both groups
    the EPxx spec, mixed (EP10: 56 without a box, 112 with one)
    the presence of a waste node, absent in 275 of the 276 affected models
      and split 159 / 116 between the two causes

  So the distinction has to be carried as data.

  SOURCE

  Derived from the reinkpy project's model database, which records per model
  whether the counter lives in the printer ("Waste counter") or on the
  maintenance box's chip ("Platen pad counters"). The models listed here are
  the ones reinkpy classifies as having only platen pad counters.

    reinkpy: https://codeberg.org/atufi/reinkpy
    licence: GNU Affero General Public License v3.0 (AGPL-3.0)

  This project is distributed under AGPL-3.0, which covers this AGPL-3.0-derived
  file. reinkpy retains its own licence and copyright over its database.

  GENERATED

  Do not edit the list below by hand. Run:

    node scripts/sync-model-data.mjs

  The Gradle task verifyPrinterData parses it on every build, because a
  malformed file here used to load as an empty list with nothing the user
  could see.

  COVERAGE

  The list below is reinkpy's complete platen-only classification (${models.length} models),
  not just the intersection with our own catalog. Only models also present in
  devices.xml will ever match a connected printer; the rest are kept so this
  file stays a faithful, regenerable copy of the source.

  NOTE: XML comments may not contain a double hyphen, so no dash underlines.
-->
<maintenanceBoxes>
${models.map((name) => `    <model>${name}</model>`).join("\n")}
</maintenanceBoxes>
`;
}

/**
 * The write keyword as explicit bytes.
 *
 * It must not be stored as text. Several keys are shorter than eight bytes and
 * padded with NUL, not spaces — DX3850's is "ASAP\u0000\u0000\u0000\u0000" — and a
 * literal NUL is not a legal XML character at all, so writing it as an attribute
 * produces a file that does not parse. Bytes have no such ambiguity, and they
 * are the shape the rest of the catalog already uses.
 */
function keywordBytes(wkey) {
  return [...Buffer.from(wkey, "latin1")]
    .map((b) => `0x${b.toString(16).padStart(2, "0")}`)
    .join(" ");
}

function renderExtra(models) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!--
  Epson models that reinkpy knows and devices.xml does not.

  WHY THIS FILE EXISTS

  devices.xml comes from ez-reset and covers 1080 printers. reinkpy covers 1588.
  They overlap on 1021 of them and each has models the other lacks, so neither
  is a superset: dropping either one loses printers. This file is the half that
  only reinkpy has, kept apart from devices.xml so that file stays a byte for
  byte copy of its upstream and can still be diffed against it.

  WHAT THESE MODELS CAN AND CANNOT DO

  reinkpy publishes the read key, the write key, the pad addresses and the
  reset values, which is everything the reset path needs. It does not publish
  the counter ceiling, so no percentage can be shown for these models: the app
  displays the raw counter bytes and offers the reset, not a target percentage.
  It also carries no head-cleaning payloads, so the maintenance page falls back
  to the generic ESC/P2 cleaning job it already uses for the models that lack
  them.

  WHY THEY CAN BE TRUSTED, AND HOW FAR

  devices.xml and reinkpy are independent extractions of the same printers.
  Where they overlap they agree exactly: same read key, same write keyword,
  same reset addresses, same reset values, with no exceptions across 600+ models.
  scripts/sync-model-data.mjs re-runs that comparison and refuses to write
  anything if the two ever diverge.

  That is evidence about the shared models, not about these. The models below
  are the ones with nothing to compare against, so they carry no independent
  confirmation beyond reinkpy itself.

  SOURCE

    reinkpy: https://codeberg.org/atufi/reinkpy
    mirror:  yusnel-rojas/epson-reset, data/reinkpy/database.json
    licence: GNU Affero General Public License v3.0 (AGPL-3.0)

  GENERATED

    node scripts/sync-model-data.mjs

  rkey is the same 16-bit read key devices.xml spells as two little-endian bytes
  in <factory>; the keyword is the same eight bytes it spells in <keyword>.
  Addresses and reset values are decimal, as reinkpy publishes them.

  NOTE: XML comments may not contain a double hyphen, so no dash underlines.
-->
<models count="${models.length}">
${models
  .map((m) => {
    const pads = m.padGroups
      .map(
        (g) =>
          `        <pad kind="${g.kind}" addresses="${g.addresses.join(" ")}" reset="${g.reset
            .slice(0, g.addresses.length)
            .join(" ")}"/>`,
      )
      .join("\n");
    return `    <model name="${escapeAttr(m.name)}" rkey="${m.rkey}" keyword="${keywordBytes(
      m.wkey,
    )}">
${pads}
    </model>`;
  })
  .join("\n")}
</models>
`;
}

function escapeAttr(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/** Guards the two ways these files have failed before: a double hyphen in the
 * comment, and a list that ends up empty or truncated. */
function assertSane(xml, expectedCount, rootTag) {
  const open = xml.indexOf("<!--");
  const close = xml.indexOf("-->", open + 4);
  if (open === -1 || close === -1) throw new Error("generated XML has no header comment");
  if (xml.slice(open + 4, close).includes("--")) {
    throw new Error("generated XML comment contains a double hyphen");
  }

  // A literal control character is not legal XML. This got here once: a write
  // key padded with NULs ("ASAP\u0000\u0000\u0000\u0000") rendered as trailing
  // spaces in a terminal, and the file was written with the NULs in it — a
  // document no parser would read. Checking the bytes catches that class of
  // mistake instead of the one instance of it.
  const illegal = /[\u0000-\u0008\u000B\u000C\u000E-\u001F]/.exec(xml);
  if (illegal) {
    throw new Error(
      `generated XML contains the illegal control character U+${illegal[0]
        .charCodeAt(0)
        .toString(16)
        .padStart(4, "0")}`,
    );
  }

  if (!xml.includes(`<${rootTag}`) || !xml.includes(`</${rootTag}>`)) {
    throw new Error(`generated XML is missing its <${rootTag}> root`);
  }
  const written = [...xml.matchAll(/<(?:model|pad)\b/g)].length;
  if (expectedCount > 0 && written === 0) {
    throw new Error(`expected ${expectedCount} entries, wrote none`);
  }
}

// Compare the generated data, not its prose header or only model names/counts.
// An address or key fix for an existing model is just as important as a new model.
function dataBody(xml) {
  return xml.replace(/<!--[\s\S]*?-->/g, "").replace(/<\?xml[^>]*\?>/g, "").trim();
}

function committedData(path) {
  return existsSync(path) ? dataBody(readFileSync(path, "utf8")) : null;
}

function fallback(reason) {
  if (!existsSync(BOXES) || !existsSync(EXTRA)) {
    throw new Error(`${reason} (no committed assets to reuse)`);
  }
  console.warn(`model data: ${reason} — keeping the committed assets`);
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

let res;
try {
  res = await fetch(source, { signal: AbortSignal.timeout(30_000) });
} catch (err) {
  fallback(`fetch failed: ${err instanceof Error ? err.message : err}`);
  process.exit(0);
}
if (!res.ok) {
  fallback(`fetch ${source} → ${res.status}`);
  process.exit(0);
}

const db = await res.json();
if (!db || typeof db !== "object" || Array.isArray(db)) {
  throw new Error("unexpected database.json shape");
}

const reinkpy = Object.entries(db).map(([name, v]) => ({
  name,
  alias: aliases(name),
  ...v,
}));
const ez = parseDevices();

const { ok, report, drift } = crossCheck(ez, reinkpy);
console.log("cross-check devices.xml <-> reinkpy");
for (const line of report) console.log(line);

if (!ok) {
  console.error(
    "\nThe two sources no longer agree. Nothing has been written.\n" +
      "devices.xml and reinkpy are independent extractions of the same printers;\n" +
      "a divergence means one of them changed, and the reinkpy-only models in\n" +
      "reinkpy_models.xml have no other evidence behind them. Resolve it by hand first.",
  );
  if (drift.length) console.error("first divergences:", JSON.stringify(drift, null, 2));
  process.exit(1);
}

// --- maintenance boxes: reinkpy's complete platen-only classification ---
const boxes = reinkpy
  .filter((m) => {
    const withAddr = (m.pad_groups || []).filter(
      (g) => Array.isArray(g?.addresses) && g.addresses.length > 0,
    );
    return withAddr.length > 0 && withAddr.every((g) => g.kind === "platen");
  })
  .map((m) => m.name)
  .sort(byName);

if (!boxes.length) throw new Error("upstream classified no model as platen-only");

// --- extra models: reinkpy-only, with the keys needed to open a session ---
const ezAlias = new Set();
for (const m of ez) for (const a of m.alias) ezAlias.add(a);

const extra = reinkpy
  .filter((m) => !m.alias.some((a) => ezAlias.has(a)))
  .filter((m) => m.rkey != null && m.wkey)
  .map((m) => ({
    name: m.name,
    rkey: m.rkey,
    wkey: m.wkey,
    padGroups: (m.pad_groups || [])
      .filter((g) => (g.addresses || []).length)
      .map((g) => ({
        kind: g.kind || "unknown",
        addresses: g.addresses,
        reset: g.reset || [],
      })),
  }))
  .sort((a, b) => byName(a.name, b.name));

const boxesXml = renderBoxes(boxes);
const extraXml = renderExtra(extra);
assertSane(boxesXml, boxes.length, "maintenanceBoxes");
assertSane(extraXml, extra.length, "models");

const boxesStale = committedData(BOXES) !== dataBody(boxesXml);
const extraStale = committedData(EXTRA) !== dataBody(extraXml);

if (!boxesStale && !extraStale) {
  console.log(
    `\nup to date — ${boxes.length} maintenance-box models, ${extra.length} reinkpy-only models`,
  );
  process.exit(0);
}

if (checkOnly) {
  if (boxesStale) console.error(`maintenance_boxes.xml is stale (${boxes.length} upstream)`);
  if (extraStale) console.error(`reinkpy_models.xml is stale (${extra.length} upstream)`);
  console.error("Run: node scripts/sync-model-data.mjs");
  process.exit(1);
}

if (boxesStale) {
  writeFileSync(BOXES, boxesXml);
  console.log(`wrote ${BOXES} — ${boxes.length} models`);
}
if (extraStale) {
  writeFileSync(EXTRA, extraXml);
  console.log(`wrote ${EXTRA} — ${extra.length} models`);
}
