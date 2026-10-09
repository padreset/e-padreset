# Catalog load probe — Pixel 4a

Date: 2026-10-08. Device: Pixel 4a (`sunfish`), Android 13 / API 33. Measurements used the bundled 1,449-spec catalog and Android's own XML parser. A USB Epson L365 (serial `VHEK034809`) was attached for the original run, but the catalog probe sent no printer commands. The app was separately verified connected and reading the L365's status and counters.

## What was measured

A temporary Android instrumentation probe invalidated the in-process `EpsonPrinterCatalog.cachedSpecs` field between five loads in one instrumentation process, ran GC before each measurement, timed `EpsonPrinterCatalog.load(context)`, sampled Java heap use at 1 ms intervals, sampled process PSS before/after each load, and measured retained heap after GC. It then timed 30 cached calls. This isolates XML/catalog initialization, not the app's full launcher cold-start. The first iteration has a much smaller baseline heap because process/startup state differs, so the five timings are indicative rather than a controlled performance benchmark.

| Iteration | Catalog parse (ms) | Sampled Java heap peak (MiB) | PSS delta (MiB) |
|---:|---:|---:|---:|
| 1 | 7,728 | 42.1 | 82.9 |
| 2 | 3,268 | 43.2 | 57.2 |
| 3 | 3,543 | 43.1 | 57.0 |
| 4 | 2,345 | 42.4 | 53.9 |
| 5 | 2,057 | 39.4 | 47.8 |

- Median cold parse in this five-run sample: **3.27 s**; range: **2.06–7.73 s**.
- Maximum sampled heap peak: **43.2 MiB**. Median post-GC retained heap: **6.2 MiB**.
- Median PSS delta: **57.0 MiB**. PSS includes more than Java heap and the test process/Android runtime, so treat it as process-level context, not catalog-only memory.
- Cached `load()` call: median **0.0015 ms**, p95 **0.0033 ms** (30 calls). It returns the existing cached list; it does not parse again.
- The cold parser is not run during a plain app launch with no printer. It is first invoked when a connection reaches catalog resolution. The opening screen cost is therefore not equal to the cold parse duration.

Raw measurements: [instrumentation log](../app/build/outputs/androidTest-results/connected/debug/Pixel%204a%20-%2013/logcat-com.padreset.epadreset.data.CatalogPerformanceProbeTest-measureColdAndCachedCatalogLoad.txt) and [JUnit XML result](../app/build/outputs/androidTest-results/connected/debug/TEST-Pixel%204a%20-%2013.xml). The generated JUnit XML records one test with zero failures/errors, but Gradle returned nonzero for `connectedDebugAndroidTest` and wrote task exit code `1`; the cause of this discrepancy was not established, so this is not a clean Gradle task pass.

## Additional Pixel 4a device rerun

A second probe was run directly with Android's instrumentation runner on the Pixel 4a. It cleared `cachedSpecs` between five loads, but kept the same process alive, so these are cache-cold / warmed-parser timings (not fresh-process cold starts): **1,705, 1,273, 1,186, 1,158, 1,280 ms**, median **1,273 ms**, range **1,158–1,705 ms**. The sampled peak Java heap was **42.4 MiB**; retained heap after GC was **3.3 MiB median**. PSS deltas were noisy (**2.3–24.1 MiB**, median **3.7 MiB**) and remain process-level samples, not catalog-only memory. The 30 cached calls measured **0.00068 ms median / 0.00115 ms p95**.

Separately, five `am force-stop` + `am start -W` cold activity launches, with the L365 connected and USB permission already granted, took **1,497, 1,535, 1,518, 1,495, 1,527 ms** (`TotalTime`); median **1,518 ms**, range **1,495–1,535 ms**. This is the ActivityManager displayed-start measurement and not a complete printer-connect latency. Afterward, the app UI showed `Conectada por USB OTG`, `L365 Series`, serial `VHEK034809`, and the main waste counter at **31.26%**.

The standalone `am instrument` runner completed the full on-device suite with **28 tests, 0 failures** (`OK (28 tests)`); that run included the one temporary performance probe. The four catalog-specific tests were also run directly and returned `OK (4 tests)`. At that time Gradle's `connectedDebugAndroidTest` could not be called a pass: supplying Gradle's documented `--serial` hit an `UnsupportedOperationException` in AGP's `DeviceProviderInstrumentTestTask.getFilteredDevices()` (`ImmutableCollection.remove`). Direct instrumentation was used to run and verify tests on the intended phone without that Gradle device-provider path.

## Follow-up: DOM vs pull-parser traversal and Gradle device selection

After the desktop session restarted, the Pixel 4a was no longer connected. The reachable devices were a Pixel 9a (Android 17 / API 37) and an Android 15 emulator. The prior Pixel 4a wireless ADB endpoint refused connection. Consequently, results below are explicitly from the **Pixel 9a**, not a Pixel 4a retest. The Epson L365 was unavailable and not rechecked.

A temporary Android instrumentation comparison processed the same in-memory bytes of `devices.xml`, `reinkpy_models.xml`, and `maintenance_boxes.xml` using Android's DOM document builder and `XmlPullParser`. It asserted that every XML element-name count matched for all three assets. Five warmed passes alternated implementation order and measured XML traversal only; they did not create `PrinterSpec` objects.

An initial version of the probe on the Pixel 9a produced DOM median **191 ms** (182, 222, 182, 210, 191; peak sampled Java heap **39.1 MiB**) and pull-parser median **110 ms** (123, 107, 113, 90, 110; peak **13.8 MiB**). The cleaned-up benchmark was then rerun with the final test code on the Android 15 emulator:

| XML traversal (Android 15 emulator) | Five passes (ms) | Median | Range | Highest sampled Java heap |
|---|---:|---:|---:|---:|
| DOM tree | 1,096, 1,101, 783, 611, 660 | **783 ms** | 611–1,101 ms | 33.2 MiB |
| `XmlPullParser` | 392, 619, 407, 365, 411 | **407 ms** | 365–619 ms | 14.5 MiB |

For the emulator run, pull traversal's median was about **1.92x faster** with roughly **56% lower sampled peak Java heap**. Do not compare these timing values across the Pixel 9a and emulator as a controlled measurement. Neither probe is a drop-in production parser comparison: both count XML elements only and do not build model objects, counters, reset mappings or cleaning payloads. They use already-resident bytes and a coarse 1 ms heap sampler. Treat these as exploratory evidence, not as an app startup/connect speedup or a reason by themselves to change production parsing. A proper comparison must build complete catalog specs with a streaming parser and assert field-for-field semantic equality against the current DOM output on the same device.

### Gradle test task

This checkout's device selection is repeatable by selecting exactly one connected target with `ANDROID_SERIAL`:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --rerun --console=plain
```

Run after adding the parser comparison test: **BUILD SUCCESSFUL**, with **28 tests, 0 failures** on the Android 15 emulator. The equivalent full-suite run against the Pixel 9a selected the phone but had **2 failing existing clipboard-related UI tests** in `ModelSupportDialogTest` (clipboard returned `null`); 26 other tests passed. Thus the emulator run is a clean connected Gradle pass; the Pixel 9a full suite is not. An attempted `--tests` selector failed at task argument parsing because this Android connected-device task does not support that JVM test selector; that attempt ran no tests.

The parser comparison also passed by direct `am instrument` on the Pixel 9a (**1 test, 0 failures**) with the initial probe version, and the finalized test passed within the full emulator suite. The debug app and test APK installed successfully on that phone. However, this device is shared and another app returned to the foreground, so E-Pad Reset was not left visibly open at the end of the follow-up. The Pixel 4a and Epson are currently unavailable; their earlier connection and counter readings are historical, not revalidated now. No printer command, EEPROM write, reset, cleaning cycle, or test print was sent during this follow-up.

## Complete streaming parser: production path and same-device comparison

The production catalog now uses `XmlPullParser` to read `devices.xml`, `reinkpy_models.xml`, and `maintenance_boxes.xml`; it constructs the same `PrinterSpec` objects and caches the merged catalog as before. The Android instrumentation comparison parses the same bundled bytes through both the retained DOM reference path and the new streaming path, then compares the ordered 1,449-model catalog field by field: model name, control bytes, keyword, counter names/addresses/maximums/masks/kinds, reset map, cleaning names/types/payloads, and maintenance-box classification. All fields matched.

The latest complete run used the Android 15 emulator (not a physical Pixel): five warmed full-catalog parses per implementation, alternating parser order and including asset XML traversal, spec extraction, object construction, extension merge and deduplication. These are debug-build measurements with a 1 ms Java-heap sampler; treat them as indicative, not user-perceived launch/connect benchmarks.

| Full catalog parse (Android 15 emulator) | Five passes (ms) | Median | Range | Highest sampled Java heap |
|---|---:|---:|---:|---:|
| DOM reference | 2,247, 2,090, 2,132, 2,260, 2,153 | **2,153 ms** | 2,090–2,260 ms | 48.3 MiB |
| `XmlPullParser` production | 1,112, 1,002, 1,018, 1,203, 1,000 | **1,018 ms** | 1,000–1,203 ms | 26.3 MiB |

In this run the complete stream parser's median was about **2.12× faster** and sampled peak Java heap was about **46% lower** than DOM. The instrumentation suite passed **29 tests, 0 failures**, including the complete semantic comparison, on that emulator. Unit tests, debug Kotlin/test compilation, and detekt also passed.

This does not update the earlier Pixel 4a measurements: that phone and attached Epson L365 (`VHEK034809`) are not connected in the current session. A Pixel 9a was listed earlier during the work, but the final ADB check showed only the Android 15 emulator connected. The emulator test run did not communicate with a printer. Consequently the app was not installed/launched onto the Pixel 4a, and its current UI connection to the L365 could not be verified or left open. The historical Pixel 4a status above remains historical only.

Raw latest comparison output: [instrumentation log](../app/build/outputs/androidTest-results/connected/debug/wb_pixel%28AVD%29%20-%2015/logcat-com.padreset.epadreset.data.CatalogParserComparisonTest-domAndStreamingCatalogsMatchAndStreamingPerformanceIsMeasured.txt) and [JUnit XML result](../app/build/outputs/androidTest-results/connected/debug/TEST-wb_pixel%28AVD%29%20-%2015.xml).

## Complete streaming parser rerun on Pixel 4a (Android 13)

The Pixel 4a (`sunfish`, Android 13 / API 33) and Epson L365 were reconnected on 2026-10-08. Android's USB host service identified the attached device as **EPSON L365 Series**, USB vendor/product `1208:2258`, serial `VHEK034809`; the app already had USB permission. The finalized `CatalogParserComparisonTest` was run directly with `am instrument` on the Pixel 4a after installing the current debug app and test APK. Result: **1 test, 0 failures**. It parsed the same bundled asset bytes via the DOM reference and production streaming paths, confirmed field-for-field equality for all **1,449 models**, then built the full merged catalog in five warmed alternating measurements.

| Full catalog parse (Pixel 4a, Android 13) | Five passes (ms) | Median | Range | Highest sampled Java heap |
|---|---:|---:|---:|---:|
| DOM reference | 1,094, 1,061, 1,072, 1,044, 1,034 | **1,061 ms** | 1,034–1,094 ms | 52.5 MiB |
| `XmlPullParser` production | 573, 506, 522, 512, 526 | **522 ms** | 506–573 ms | 31.1 MiB |

On this Pixel 4a run, streaming was about **2.03× faster by median**, with approximately **41% lower sampled peak Java heap**. The benchmark is a debug-build parser measurement over in-memory asset bytes with a 1 ms sampler—not a cold app launch or printer handshake benchmark.

After the instrumentation run, the app was opened on the Pixel 4a and its screen showed **Conectada por USB OTG**, **L365 Series**, serial **VHEK034809**, printer state **En reposo**, and the waste counter at **31.26%**. It was left open on that connected status screen. No reset, cleaning cycle, test print, or other printer command was issued; connecting and reading status/counters were the only printer interactions.

The instrumentation suite run here was the focused catalog comparison only (**1 test**), not the complete 29-test suite. The previous Android 15 emulator results remain useful as separate device measurements but are not substituted for this Pixel 4a rerun. The installed package reports version **1.0.0**.

## Probe status and safety

`CatalogParserComparisonTest.kt` compares implementations using only the app's bundled XML assets; it does not communicate with a printer. The production app now uses the streaming implementation. No EEPROM writes, resets, cleaning cycles, or test prints were performed during the streaming comparison. The app was subsequently launched and left open after its ordinary USB connection and status/counter read on the L365.

Raw rerun log (device `sunfish`, available via `adb logcat -s CatalogParserComparison:I`): the equivalence line reported `models=1449`; DOM measured `median=1061 ms` and `sampledJavaHeapPeak=52.5MiB`; streaming measured `median=522 ms` and `sampledJavaHeapPeak=31.1MiB`.
