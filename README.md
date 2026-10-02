# E-Pad Reset

<p align="center">
  <a href="https://github.com/tekware-it/android-epson-reset/releases">
    <img alt="GitHub Release" src="https://img.shields.io/github/v/release/tekware-it/android-epson-reset?display_name=tag" />
  </a>
</p>

<p align="center">
  <img src="play-store/Screenshot_20260312_215851_Android%20Epson%20Reset.jpg" alt="Android Epson Reset screenshot 1" width="30%" />
  <img src="play-store/Screenshot_20260312_215906_Android%20Epson%20Reset.jpg" alt="Android Epson Reset screenshot 2" width="30%" />
  <img src="play-store/Screenshot_20260312_215921_Android%20Epson%20Reset.jpg" alt="Android Epson Reset screenshot 3" width="30%" />
</p>

Android port of [`ez-reset`](https://github.com/CiRIP/ez-reset) for Epson printer maintenance over USB OTG.

This app is aimed at Epson inkjet printers that expose the same service protocol used by `ez-reset`. It can detect supported Epson USB devices, open the control channel, read printer status, inspect waste ink counters, issue waste counter write/reset commands, and send maintenance jobs such as nozzle check and head cleaning directly from an Android device.

## What It Does

- Detects Epson USB printers via Android USB Host API
- Reads IEEE 1284 device ID and resolves printer model data from the bundled Epson database
- Enters Epson IEEE 1284.4 / D4 mode and opens the `EPSON-CTRL` channel
- Reads printer status, including ink levels when exposed by the printer
- Reads waste ink counters from EEPROM
- Writes or resets waste ink counters using model-specific reset values
- Reads and keeps the bytes it is about to overwrite before any write, and stops at the first write the printer refuses
- Runs model-dependent head cleaning commands over Epson service protocol where supported
- Falls back to USB ESC/P2 maintenance jobs for head cleaning on models that reject service cleaning
- Prints nozzle check patterns over USB
- Connects to models that have no resettable counter — maintenance-box printers — so head cleaning and the nozzle check still work, and explains why there is nothing to reset
- Names the reason a connection failed instead of reporting a generic error, and offers a prefilled report when the model is missing from the database and could be added
- Distinguishes "no Epson printer attached" from "a printer is attached, but it is not an Epson"
- Scans for a printer on launch instead of waiting for the refresh button, and drops the connection when it is unplugged
- Shows its own version in About, and can open a releases page once the fork publishes one — the app holds no network permission and never checks by itself
- Shows a compact status monitor UI inspired by `ez-reset`
- Includes an in-app protocol log for debugging USB / D4 traffic

## Current UI

Two pages, reached from the header.

**Status** — the printer's condition:

- `Ink Levels` panel with adaptive single-row gauges for 4 to 8 inks
- `Waste Levels` panel with dynamic counter rows, or an explanation when the model has no counter to reset
- Protocol log with a copy action

**Maintenance** — acting on the printer:

- `Nozzle Check`, then `Head Cleaning` (check before you clean: the diagnostic costs almost no ink)

The header carries circular icon buttons for maintenance and about, and a refresh button on the status card. Android back returns from Maintenance to Status, and asks for confirmation before exiting.

## Technical Notes

- Platform: Android 7.0+ (`minSdk 24`)
- Language: Kotlin
- UI: Jetpack Compose
- USB stack: `UsbManager`, `UsbDeviceConnection`, bulk endpoints, printer-class control requests
- Protocol: Epson `st` / `||` commands over D4 / IEEE 1284.4, plus USB ESC/P2 maintenance print jobs where needed
- Model database: bundled `devices.xml` from the upstream `ez-reset` project, extended by `reinkpy_models.xml`

### Which USB interface to use

A printer exposes more than one interface with bulk endpoints, and which one
carries D4 differs by model. An L365 answers on its printer-class interface and
ignores the vendor-specific one, which is the order the app would otherwise try
first.

Getting it wrong is not fatal, because the remaining candidates are tried, but
the failed attempt has to run into its read timeouts before the fallback — the
IEEE 1284.4 reply window plus the 8 s D4 header read. That is several seconds of
spinner on every launch, which is what the user sees as a slow read.

So the interface that worked is written to `SharedPreferences` and tried first
next time. It is keyed on vendor and product id, not on the device path, which
changes on every replug and would make the preference unreachable.

The first connect to a printer is still slow, because there is nothing to
remember yet. Every one after it is not.

## Fonts

The UI uses two typefaces from the [Google Fonts](https://github.com/google/fonts) repository, both
under the SIL Open Font License:

- **Space Grotesk** — display text (headings, the printer state)
- **Figtree** — body text
- **Monospace** — protocol log and technical values

They are bundled as **static instances, one file per weight**, generated from the upstream variable
fonts with [`fonttools`](https://github.com/fonttools/fonttools):

```sh
fonttools varLib.instancer "Figtree[wght].ttf"       wght=400 -o figtree_regular.ttf
fonttools varLib.instancer "Figtree[wght].ttf"       wght=500 -o figtree_medium.ttf
fonttools varLib.instancer "Figtree[wght].ttf"       wght=600 -o figtree_semibold.ttf
fonttools varLib.instancer "SpaceGrotesk[wght].ttf"  wght=500 -o space_grotesk_medium.ttf
fonttools varLib.instancer "SpaceGrotesk[wght].ttf"  wght=600 -o space_grotesk_semibold.ttf
```

The generated files were then verified to have the `fvar` table removed and `OS/2.usWeightClass` set
to the matching value, and their `name` table rewritten so each file reports its real weight instead
of inheriting the upstream family name.

### Do not swap these for the variable fonts

The upstream variable files default to `wght=300` and identify themselves as `"Figtree Light"` and
`"Space Grotesk Light"` with `OS/2.usWeightClass = 300`. Dropping them into `res/font/` as a
replacement means **every declared weight resolves to Light 300**: headings declared as SemiBold
render thin, and the whole scale loses its hierarchy.

This is a silent regression. It compiles, it runs, and there is no error to search for — the only
symptom is text that looks wrong, which is easy to dismiss as a matter of taste.

If you need a weight that is not bundled, generate another static instance with the command above
rather than substituting the variable file.

The license texts are kept alongside the rest of the project's notices in
`app/src/main/res/raw/ofl_figtree.txt` and `ofl_space_grotesk.txt`.

## Printer Data

`assets/devices.xml` is the model database. It has two parts: a `<records>`
section mapping model names to spec names, and a `<devices>` section holding the
specs themselves. `EpsonPrinterCatalog` joins the two by the `specs` attribute.

A model is kept in the catalog when the app can open a session with it and then
do something useful: it needs a control model, a keyword, and either waste
counters or cleaning jobs. Waste counters are **not** required. Models that
collect waste ink in a maintenance box carry no counter but do carry their
cleaning jobs, and head cleaning and the nozzle check still work on them.
Requiring counters is what once made those models answer "Unsupported Epson
model" instead of connecting.

### Models with a maintenance box

`assets/maintenance_boxes.xml` lists the models that use a maintenance box
rather than an internal pad. On those, the fill level lives on the box's own
chip, so the printer holds no counter for this app to reset.

This cannot be derived from `devices.xml`. Three candidate signals were measured
against a known classification and all three failed:

| Signal | Result |
|---|---|
| `class` attribute | identical (`epson.printer.dd4.standard`) across both groups |
| `EPxx` spec | mixed — EP10 is 56 without a box and 112 with one |
| a `<waste>` node | absent in 275 of the 276 affected models, splitting 159 / 116 |

So the distinction is carried as data, and the source is the
[reinkpy](https://codeberg.org/atufi/reinkpy) project (AGPL-3.0). reinkpy's
database records, per model, whether the counter lives in the printer
("Waste counter") or on the box's chip ("Platen pad counters"); the file holds
its complete platen-only classification, 313 models. Only models also present
in `devices.xml` will ever match a connected printer — the rest are kept so the
file stays a faithful, regenerable copy of the source rather than a one-off
intersection.

A connected model with no counter that is **not** on the list is not
mislabelled either: it falls back to the neutral wording that names both
possible causes. The list only ever makes the message more specific.

The list also answers one question the catalog otherwise cannot. When a
connected printer is **not** in `devices.xml`, resolution fails and the app used
to say only that the model was missing. It now asks the list first: if the model
is on it, adding the model would enable head cleaning but never a counter reset,
and the callout says so instead of promising an outcome the hardware cannot
deliver. That is why the report action below is not offered for those models —
there would be nothing for the report to change.

### The models reinkpy has and `devices.xml` does not

`assets/reinkpy_models.xml` is the other half. `devices.xml` comes from ez-reset
and covers 1080 printers; reinkpy covers 1588. They overlap on 1021, so neither
is a superset and dropping either one loses printers. The extension holds the 554
models only reinkpy has, kept in its own file so `devices.xml` stays a byte for
byte copy of its upstream and can still be diffed against it. A model that
`devices.xml` already knows keeps its entry, because that one also carries the
counter ceiling and the cleaning jobs.

These models can read and they can reset: reinkpy publishes the read key, the
write key, the pad addresses and the reset values. What they cannot do is show a
percentage, because reinkpy does not publish the counter ceiling — the app shows
the raw reading instead and says why, rather than printing 0% for a pad that may
well be full. They also carry no cleaning payloads, so the maintenance page falls
back to the generic ESC/P2 job it already uses for the models that lack one.

### Regenerating both files

```sh
node scripts/sync-model-data.mjs           # verify, then regenerate
node scripts/sync-model-data.mjs --check   # exit 1 if either is stale, for CI
```

The script reads the parsed mirror of reinkpy published by
[`yusnel-rojas/epson-reset`](https://github.com/yusnel-rojas/epson-reset)
(`data/reinkpy/database.json`) rather than parsing reinkpy's `epson.toml`
itself: parsing the TOML is upstream's job and it already does it. It rewrites a
file only when its list actually changes, so the prose above survives a run that
finds nothing new, and it keeps the committed assets when the upstream is
unreachable instead of failing the build on a network blip.

### The cross-check runs before anything is written

`devices.xml` and reinkpy are independent extractions of the same printers, and
where they overlap they agree **exactly**. That agreement is the only evidence
behind the reinkpy-only models, because for those there is nothing to compare
against — so the script re-runs the comparison first and refuses to write if the
two ever diverge. Writing first and warning after would ship a catalog built on
data that no longer matches the one source it can be checked against.

| `devices.xml` | reinkpy | agreement |
|---|---|---|
| `<factory>`, two little-endian bytes | `rkey`, a 16-bit integer | 100% |
| `<keyword>`, eight bytes | `wkey`, an eight-character string | 100% |
| `<reset>` addresses | `pad_groups[].addresses` | 100% |
| `<reset>` values | `pad_groups[].reset` | 100% |

### Two guards, from two failures

**XML comments may not contain a double hyphen** — a dash underline in a header
broke the parse once — so the script refuses to write a comment containing one.
And a literal NUL is not a legal XML character: one write key is `"ASAP\0\0\0\0"`,
which renders as trailing spaces in a terminal, and the first version of the
extension was written with those NULs in it. Keywords are therefore stored as
explicit bytes, and the script rejects any control character in its output.

Both files load through `runCatching`, so a file that does not parse comes back
as an empty list with nothing the user can see. The Gradle task
`verifyPrinterData` parses all three assets on every build, fails when a list is
empty or truncated, and checks that every extension model carries a readable
`rkey` and `keyword` — a model without them would resolve, open no session, and
report the printer as unsupported, which is the exact failure the file exists to
fix.

## Project Structure

- `app/src/main/java/com/padreset/epadreset/ui`
  UI, dialogs, status monitor, log panel
- `app/src/main/java/com/padreset/epadreset/service`
  Printer orchestration and command flow
- `app/src/main/java/com/padreset/epadreset/usb`
  Android USB Host transport
- `app/src/main/java/com/padreset/epadreset/protocol`
  Epson packet and D4 framing logic
- `app/src/main/java/com/padreset/epadreset/data`
  Printer model and status data structures
- `app/src/main/assets/devices.xml`
  Bundled Epson model database, from ez-reset
- `app/src/main/assets/maintenance_boxes.xml`
  Generated list of models that use a maintenance box, with provenance
- `app/src/main/assets/reinkpy_models.xml`
  Generated models that only reinkpy has
- `scripts/sync-model-data.mjs`
  Cross-checks the two sources, then regenerates both generated assets

## Build

Open the project in Android Studio and build the `app` module.

The unit tests run on the JVM; the UI tests need a device or an emulator:

```sh
./gradlew :app:testDebugUnitTest           # parsers, protocol and write rules, no device
./gradlew :app:connectedDebugAndroidTest   # renders the screens, needs one
```

The UI tests render the status page against states a desk cannot reach — a model
missing from the catalog, a finished write, a counter with no published ceiling —
because none of those appear without a printer on the other end of the cable.
They also caught two things no amount of compiling would have: that section
labels are upper-cased before they are drawn, and that the backup block had a
scrollable region inside the page's own scroll, which trapped a drag instead of
moving the page.

The write rules are tested the same way, with a fake printer standing in for the
hardware: see `WasteWriterTest`.

Key identifiers:

- Project name: `epad-reset`
- Application ID: `com.padreset.epadreset`
- Launcher label: `E-Pad Reset`

## Usage

1. Connect an Epson printer through USB OTG.
2. Grant USB permission when Android asks.
3. Open the app. It scans for a printer on launch and connects to the first one it
   finds; the refresh button is for a rescan afterwards.
4. Review ink and waste counter values.
5. Use `Reset Counters` only if you understand the maintenance implications.
6. Use `Head Cleaning` or `Nozzle Check` for supported maintenance operations.

Unplugging the printer drops the connection and clears the readings, so what is
on screen always belongs to a printer that is actually there.

## Warning

Resetting or writing waste ink counters changes internal printer maintenance values.

Head cleaning consumes ink and increases waste ink accumulation.

Waste counter reset does **not** replace the physical waste ink pads or maintenance tank. Use maintenance functions only if you understand the hardware implications and accept the risk.

## Status

This is a working port in progress, not a finished production tool.

Known realities:

- USB behavior can vary between Epson models
- Some models need different maintenance paths for status/reset versus cleaning/nozzle jobs
- Hardware validation is still essential before trusting resets on a new model

### The write path

Both gaps described here were on the most dangerous path in the app. They were
found by comparing against the other open reset tools, and both are now closed.

**A rejected write stops the run.** The service layer answers every write with a
short ASCII result — `:OK;` when it applied, `:NA;` or `:NG;` when it refused —
and the app reads it at the moment it happens. A refusal aborts at that address
and reports how many had already been written, instead of carrying on through the
rest of the plan and only noticing at the end.

Those markers come from a sibling implementation that talks to the printer over
the raw USB pipe rather than over D4, so the framing differs even though the
service payload should not. A reply the app does not recognise is therefore
logged and the run continues: `classifyWriteReply` answers `Unknown`, not a
failure, because stopping a write that may well have worked is the worse
outcome. One reset run on real hardware, with the protocol log visible, confirms
the shape here — and the `Unknown` branch is where that evidence would go.

**Every write is backed up first.** Before anything is written, the app reads
every address it is about to overwrite and records the bytes; a read that fails
stops the run before the first write, rather than leaving a partial backup that
looks like a way back. The backup is shown on the status page under the waste
counters as copyable text, and it survives failure as well as success — a run the
printer refused partway through has already overwritten part of what was there,
which is exactly when it matters.

What a successful run still does not do: restore a backup. The text is there to
be applied by someone who has decided to, not to be replayed automatically.

A reported success still means the counters read back at the value asked for. It
does not yet mean the printer confirmed each individual write — only that none of
them was refused.

Both rules are covered by `WasteWriterTest`, which drives them against a scripted
fake printer. The rules live in `WasteWriter` rather than in `PrinterService` for
exactly that reason: with the transport reduced to two functions they can be run
without one. The fake records every read and write in order, so the assertions are
about the sequence — that the backup ran before the first write, that a refusal
left the addresses after it untouched — and not only the end state, which a backup
taken too late would also produce.

What that does not settle: the replies are scripted, not observed. The tests prove
the app behaves correctly *given* an answer. Which answer a real printer gives is
still the open question, and the `Unknown` branch is where that lands.

## Upstream Basis

This port was reverse-engineered primarily from:

- `ez-reset` repository: <https://github.com/CiRIP/ez-reset>
- `upstream-ez-reset/src/ez_reset/d4.py`
- `upstream-ez-reset/src/ez_reset/printer.py`
- `upstream-ez-reset/src/ez_reset/status.py`
- `upstream-ez-reset/src/ez_reset/devices.py`
- `upstream-ez-reset/src/ez_reset/devices.xml`

Data sources:

- Model database (`assets/devices.xml`): derived from `ez-reset`
- Extra models (`assets/reinkpy_models.xml`): derived from the
  [reinkpy](https://codeberg.org/atufi/reinkpy) model database
- Maintenance-box classification (`assets/maintenance_boxes.xml`): derived from
  the same reinkpy database

## License Note

This repository is distributed under `GPL-3.0`.

It was previously offered under `GPL-2.0-or-later`. The move to `GPL-3.0` is
what makes the maintenance-box classification possible: the list in
`assets/maintenance_boxes.xml` is derived from the
[reinkpy](https://codeberg.org/atufi/reinkpy) model database, which is
AGPL-3.0, and section 13 of GPL-3.0 permits combining a covered work with a
work licensed under AGPL-3.0.

[`NOTICE`](NOTICE) states what this work is derived from, what was modified and
when, and which data and assets come from other projects. GPL-3.0 section 5 asks
a modified work to carry that; it lives in one file rather than in a header on
every source file, because a header added to untouched files would attribute
every one of their lines to the change that added it and erase the record the
notice exists to state.

The upstream `ez-reset` repository does not currently expose a clear top-level
project license in its repository metadata. The upstream licensing situation
should therefore still be confirmed with the original author or maintainer.
