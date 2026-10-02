plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.padreset.epadreset"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.padreset.epadreset"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")

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
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
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
val verifyPrinterData by tasks.registering {
    group = "verification"
    description = "Parses the bundled printer assets and checks they are not empty."
    val assetsDir = layout.projectDirectory.dir("src/main/assets")
    inputs.files(
        assetsDir.file("devices.xml"),
        assetsDir.file("maintenance_boxes.xml"),
        assetsDir.file("reinkpy_models.xml"),
    )
    doLast {
        fun parse(file: java.io.File): org.w3c.dom.Document =
            javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
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
