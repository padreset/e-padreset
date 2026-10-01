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
- Runs model-dependent head cleaning commands over Epson service protocol where supported
- Falls back to USB ESC/P2 maintenance jobs for head cleaning on models that reject service cleaning
- Prints nozzle check patterns over USB
- Connects to models that have no resettable counter — maintenance-box printers — so head cleaning and the nozzle check still work, and explains why there is nothing to reset
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
- Model database: bundled `devices.xml` derived from the upstream `ez-reset` project

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

`assets/maintenance_boxes.xml` lists the models known to use a maintenance box
rather than an internal pad. On those, the fill level lives on the box's own
chip, so the printer holds no counter for this app to reset.

This cannot be derived from `devices.xml`. Three candidate signals were measured
against a known classification and all three failed:

| Signal | Result |
|---|---|
| `class` attribute | identical (`epson.printer.dd4.standard`) across both groups |
| `EPxx` spec | mixed — EP10 is 56 without a box and 112 with one |
| a `<waste>` node | absent in 275 of the 276 affected models, splitting 159 / 116 |

So the distinction is carried as data. Each entry records its source, and only
Epson's own pages are used: a claim taken from a retailer listing is only as
good as that retailer. To add a model, add a `<model>` line under the right
`<box>` and keep the `source` attribute accurate.

The list is a seed, not a complete one. A model missing from it is not
mislabelled — it falls back to the neutral wording that names both possible
causes, and adding entries only ever makes the message more specific.

One trap worth knowing: **XML comments may not contain a double hyphen.** A dash
underline in the file header broke the parse once, and because the loader is
wrapped in `runCatching` the list came back empty with nothing the user could
see. The header of the file says so as well.

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
  Bundled Epson model database
- `app/src/main/assets/maintenance_boxes.xml`
  Curated list of models that use a maintenance box, with provenance

## Build

Open the project in Android Studio and build the `app` module.

Key identifiers:

- Project name: `epad-reset`
- Application ID: `com.padreset.epadreset`
- Launcher label: `E-Pad Reset`

## Usage

1. Connect an Epson printer through USB OTG.
2. Grant USB permission when Android asks.
3. Open the app.
4. Tap the refresh button to read printer status.
5. Review ink and waste counter values.
6. Use `Reset Counters` only if you understand the maintenance implications.
7. Use `Head Cleaning` or `Nozzle Check` for supported maintenance operations.

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

## Upstream Basis

This port was reverse-engineered primarily from:

- `ez-reset` repository: <https://github.com/CiRIP/ez-reset>
- `upstream-ez-reset/src/ez_reset/d4.py`
- `upstream-ez-reset/src/ez_reset/printer.py`
- `upstream-ez-reset/src/ez_reset/status.py`
- `upstream-ez-reset/src/ez_reset/devices.py`
- `upstream-ez-reset/src/ez_reset/devices.xml`

## License Note

This repository is currently distributed under `GPL-2.0-or-later` as a conservative compatibility choice.

However, the upstream `ez-reset` repository does not currently expose a clear top-level project license in its repository metadata. The upstream licensing situation should therefore still be confirmed with the original author or maintainer.
