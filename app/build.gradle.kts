plugins {
    id("com.android.application")
    // No `org.jetbrains.kotlin.android`: AGP 9 ships built-in Kotlin support
    // and the plugin is no longer applied there — asking for it fails the build
    // rather than degrading quietly. The Compose compiler plugin is still
    // required, because AGP's built-in Kotlin does not carry one.
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
}

import org.gradle.api.tasks.testing.Test

/**
 * Release signing, read from the machine rather than from the repository.
 *
 * The values live in `~/.gradle/gradle.properties`, never in the project: a
 * keystore password committed here would be a password published. The keystore
 * itself is created once with:
 *
 *     keytool -genkeypair -v -keystore epadreset-release.jks -alias epadreset \
 *         -keyalg RSA -keysize 4096 -validity 10000
 *
 * and these four properties are added to `~/.gradle/gradle.properties`:
 *
 *     epadreset.storeFile=/absolute/path/to/epadreset-release.jks
 *     epadreset.storePassword=...
 *     epadreset.keyAlias=epadreset
 *     epadreset.keyPassword=...
 *
 * The CN chosen in `keytool` is the signer identity that appears on every APK,
 * so it should be something stable and recognisable.
 *
 * Losing the keystore cannot be undone. Android requires every update to be
 * signed with the same key, so without it the app can never be updated for
 * anyone who already installed it — not by the author, not by anyone. Back it
 * up twice, somewhere other than the machine that builds, and keep the password
 * and alias stored separately from the file.
 *
 * When the properties are absent the release build simply produces an unsigned
 * APK. That is deliberate: a fresh clone, and CI, have to be able to build
 * without holding the shipping key.
 */
val releaseStoreFile: String? = providers.gradleProperty("epadreset.storeFile").orNull
val releaseStorePassword: String? = providers.gradleProperty("epadreset.storePassword").orNull
val releaseKeyAlias: String? = providers.gradleProperty("epadreset.keyAlias").orNull
val releaseKeyPassword: String? = providers.gradleProperty("epadreset.keyPassword").orNull
val hasReleaseSigning: Boolean = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.padreset.epadreset"
    // Compile against 37 because the current AndroidX line requires it: core-ktx
    // 1.19, lifecycle 2.11 and the 2026.09 Compose BOM all declare
    // minCompileSdk=37, and checkDebugAarMetadata fails the build without it.
    // compileSdk is a compile-time choice only — it changes nothing at runtime
    // on older devices. targetSdk below stays at 36: that is the version whose
    // behaviour changes apply on device, the one Play requires, and the one
    // this app has actually been tested against.
    compileSdk = 37

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.padreset.epadreset"
        minSdk = 24
        // Google Play has required targeting API 36 since 31 August 2026 for
        // every new app and every update. On 35 this app simply could not be
        // published, or could not be updated once published.
        targetSdk = 36
        // The first public release. Nothing had shipped under this fork, so the
        // number was free to choose, and 1.0.0 is what it should say: 0.y.z
        // promises that anything may change, which is not what a published app
        // is claiming. The upstream it forked from sat at 0.1.x, and inheriting
        // that would have suggested a continuity and a stage that are not real.
        //
        // versionCode reads back as the version: major*10000 + minor*100 +
        // patch. Play requires it to increase with every upload, forever, so it
        // is worth a scheme that stays legible. This leaves 99 minor and 99
        // patch releases per major and still lands far below the 2,100,000,000
        // ceiling.
        versionCode = 10000
        versionName = "1.0.0"

        // Needed for the instrumented tests. Without it the default runner is
        // the framework's, which does not know about AndroidX Test and reports
        // no tests rather than failing.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // R8 and resource shrinking are most of the APK. Without them the
            // release shipped the whole of material-icons-extended - about
            // 6,400 icons in three styles, of which this app uses five - plus
            // every library resource pulled in along the way.
            //
            // The default rules cover what this app needs: it holds no
            // reflection, and the libraries that do (Compose, AndroidX) ship
            // consumer rules of their own. proguard-rules.pro stays empty on
            // purpose rather than by omission.
            //
            // Shrinking does not touch assets/, where the printer databases
            // live and are opened by name at runtime. It does remove resources
            // nothing references, which is why the font licences in res/raw
            // carry a keep rule of their own.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Left unset when the keystore properties are missing, so the task
            // produces an unsigned APK rather than failing.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No `kotlin { compilerOptions { jvmTarget } }` here. AGP 9 removed
    // `android.kotlinOptions`, and the replacement is a *top-level* `kotlin { }`
    // block — not one nested in `android { }`, which would only compile because
    // Kotlin's lambda resolution falls through to the outer extension. It is also
    // unnecessary: with built-in Kotlin, `jvmTarget` defaults to
    // `compileOptions.targetCompatibility` set just above, which is why the
    // compiled classes are Java 17 without anything stated here.

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG, which gates the counter target field. The
        // version string is read from the package manager instead; this one
        // needs to be a compile-time constant so the field is unreachable in a
        // release build rather than merely hidden at runtime.
        buildConfig = true
    }

    // No composeOptions block: since Kotlin 2.0 the Compose compiler ships as
    // the org.jetbrains.kotlin.plugin.compose plugin above, versioned with
    // Kotlin itself rather than pinned separately.

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.android.material:material:1.14.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

/**
 * Fails the build when the bundled printer data is unreadable or empty.
 *
 * Why this exists: both assets are loaded through `runCatching`, so a file that
 * does not parse degrades to an empty list and the app carries on. A malformed
 * maintenance_boxes.xml therefore does not crash and does not log anything the
 * user can see — it just quietly stops recognising maintenance-box models, and
 * every one of them goes back to answering with the hedged two-cause wording.
 * The same failure mode cost a debugging session once already, which is why the
 * header of that file warns about the double hyphen.
 *
 * Parsing here moves the failure to build time, where it is loud, and gives the
 * model lists a floor so a truncated file cannot ship either.
 */
val verifyPrinterData = tasks.register("verifyPrinterData") {
    group = "verification"
    description = "Parses the bundled printer assets and checks they are not empty."
    val assetsDir = layout.projectDirectory.dir("src/main/assets")
    inputs.files(
        assetsDir.file("devices.xml"),
        assetsDir.file("maintenance_boxes.xml"),
        assetsDir.file("reinkpy_models.xml"),
    )
    doLast {
        // Plain settings matching PrinterModels.assetFactory: the Gradle JDK
        // parser would accept XXE-hardening features that Android's built-in
        // parser rejects on device, so hardening here would pass the build and
        // still break every connect. See the KDoc there.
        fun parse(file: java.io.File): org.w3c.dom.Document =
            javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply {
                    isNamespaceAware = false
                }
                .newDocumentBuilder()
                .parse(file)

        val boxes = assetsDir.file("maintenance_boxes.xml").asFile
        val boxCount = parse(boxes).getElementsByTagName("model").length
        check(boxCount >= 100) {
            "maintenance_boxes.xml lists $boxCount models; expected at least 100. " +
                "Regenerate it with: node scripts/sync-model-data.mjs"
        }

        val devices = assetsDir.file("devices.xml").asFile
        val printerCount = parse(devices).getElementsByTagName("printer").length
        check(printerCount >= 500) {
            "devices.xml lists $printerCount printers; expected at least 500."
        }

        // The extension carries models with nothing to cross-check them against,
        // so the shape check matters more here than anywhere: a model without a
        // readable key would resolve, open no session, and report the printer as
        // unsupported — the exact failure this file exists to fix.
        val extra = parse(assetsDir.file("reinkpy_models.xml").asFile)
        val extraModels = extra.getElementsByTagName("model")
        check(extraModels.length >= 100) {
            "reinkpy_models.xml lists ${extraModels.length} models; expected at least 100. " +
                "Regenerate it with: node scripts/sync-model-data.mjs"
        }
        var incomplete = 0
        for (i in 0 until extraModels.length) {
            val model = extraModels.item(i) as? org.w3c.dom.Element ?: continue
            val rkey = model.getAttribute("rkey").toIntOrNull()
            val keyword = model.getAttribute("keyword")
            if (rkey == null || keyword.isBlank()) incomplete++
        }
        check(incomplete == 0) {
            "reinkpy_models.xml has $incomplete models without a usable rkey or keyword"
        }

        logger.lifecycle(
            "printer data ok: $printerCount printers, $boxCount maintenance-box models, " +
                "${extraModels.length} reinkpy-only models",
        )
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyPrinterData) }

//
// ESC/P2 job bytes for the escapy cross-check. See Escp2JobDumper and
// scripts/validate-escp2-with-escapy.py: the .bin files must come from the
// builders, never from pinned copies, or the check passes while the app
// drifts. Runs after the unit tests so a red suite stops it first.
//
val escp2Out = layout.buildDirectory.dir("escp2")
val dumpEscp2Jobs by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Writes every remote-mode maintenance job variant to build/escp2 for the escapy cross-check."
    dependsOn("testDebugUnitTest")
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    classpath(unitTest.map { it.classpath })
    mainClass.set("com.padreset.epadreset.protocol.Escp2JobDumper")
    args(escp2Out.get().asFile.absolutePath)
    outputs.dir(escp2Out)
}

//
// Static analysis. See the note in the root build script for why it is here.
//
// Only the JVM sources are analysed, and test sources are analysed too: a
// finding in a test is a finding about a contract nobody checks. Android
// resources, manifests and the XML asset databases are lint's business and are
// left out on purpose, because detekt reading a 2.7 MB asset would be slow and
// would find nothing.
//
detekt {
    // The default config is ~1500 lines of thresholds this project has no
    // opinion about. buildUponDefaultConfig keeps detekt's own defaults and
    // applies only what detekt.yml changes, so a detekt upgrade brings new
    // rules with their recommended thresholds instead of silently doing nothing.
    buildUponDefaultConfig = true

    source.setFrom(
        files(
            "src/main/java",
            "src/test/java",
            "src/androidTest/java",
        ),
    )

    // Set explicitly rather than left to detekt's lookup. The default search
    // path does not include this file's location, and a config that silently
    // does not load is worse than none: it reports detekt's thresholds as if
    // they were the project's, so a threshold change appears to do nothing.
    config.setFrom(files("detekt.yml"))

    // No baseline. The analysis reached zero findings by fixing what it found
    // or by switching a rule off with the reason written in detekt.yml, so
    // there is nothing to tolerate and no file to check in. A baseline added
    // later is a decision to accept debt, and it belongs in a commit that says
    // so rather than arriving as a side effect of somebody running
    // `--auto-baseline` to make a red build green.
    //
    // The failure threshold is build.maxIssues in detekt.yml, not a property
    // here: it is a detekt setting, and detekt's own default for it is 0, so
    // every finding already fails. ignoreFailures is the Gradle-side switch
    // that would let a broken analysis pass, and it stays off.
}

