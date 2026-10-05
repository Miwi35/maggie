import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.google.services) apply false
}

// Only apply google-services plugin when google-services.json exists
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.maggie.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maggie.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    if (keystorePropertiesFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile", ""))
                storePassword = keystoreProperties.getProperty("storePassword", "")
                keyAlias = keystoreProperties.getProperty("keyAlias", "")
                keyPassword = keystoreProperties.getProperty("keyPassword", "")
            }
        }
    }

    flavorDimensions += "environment"

    // The e2e stack publishes one ephemeral host port and nothing may hard-code
    // it, so the APK is built against a fixed port on the device's own loopback
    // and `e2e/mobile/run.sh` bridges it with `adb reverse`. That keeps the
    // build independent of the stack: the port changes between runs, the APK
    // does not, and CI can compile it while the eleven containers come up.
    // Override with -PE2E_API_BASE_URL for a stack reached some other way.
    val e2eBaseUrl = project.findProperty("E2E_API_BASE_URL") ?: "http://localhost:8099"

    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            // One scheme per build: dev, prod and e2e sit side by side on a phone,
            // and a shared one would open Android's app chooser on every link.
            buildConfigField("String", "DEEP_LINK_SCHEME", "\"maggie-dev\"")
            manifestPlaceholders["deepLinkScheme"] = "maggie-dev"
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2\"")
            buildConfigField("String", "MERCURE_URL", "\"http://10.0.2.2/.well-known/mercure\"")
            buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${project.findProperty("GOOGLE_CLIENT_ID") ?: ""}\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "DEEP_LINK_SCHEME", "\"maggie\"")
            manifestPlaceholders["deepLinkScheme"] = "maggie"
            buildConfigField("String", "API_BASE_URL", "\"https://maggieai.fr\"")
            buildConfigField("String", "MERCURE_URL", "\"https://maggieai.fr/.well-known/mercure\"")
            buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${project.findProperty("GOOGLE_CLIENT_ID") ?: ""}\"")
        }
        // The Maestro journeys' flavor (MAG-98). Its own applicationId, so it
        // installs beside the dev build on the owner's phone, and no
        // GOOGLE_CLIENT_ID: this flavor cannot reach Google at all.
        create("e2e") {
            dimension = "environment"
            applicationIdSuffix = ".e2e"
            buildConfigField("String", "DEEP_LINK_SCHEME", "\"maggie-e2e\"")
            manifestPlaceholders["deepLinkScheme"] = "maggie-e2e"
            buildConfigField("String", "API_BASE_URL", "\"$e2eBaseUrl\"")
            buildConfigField("String", "MERCURE_URL", "\"$e2eBaseUrl/.well-known/mercure\"")
            buildConfigField("String", "GOOGLE_CLIENT_ID", "\"\"")
            // Read by src/e2e/E2eSignIn.kt only, so neither constant exists in
            // the dev or prod BuildConfig — referencing one there is a compile
            // error rather than a door nobody noticed.
            buildConfigField("String", "E2E_LOGIN_TOKEN", "\"${project.findProperty("E2E_LOGIN_TOKEN") ?: "e2e-login-token"}\"")
            buildConfigField("String", "E2E_LOGIN_EMAIL", "\"${project.findProperty("E2E_LOGIN_EMAIL") ?: "e2e@maggie.local"}\"")
        }
    }

    sourceSets {
        // dev and prod sign in with Google; e2e has its own door in src/e2e/.
        // A shared source set rather than the same one-line binding copied into
        // two flavor folders — see SignInStrategy in src/main/.
        getByName("dev") { java.srcDir("src/google/java") }
        getByName("prod") { java.srcDir("src/google/java") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Compose on the JVM (MAG-242). Robolectric reads the variant's merged
    // manifest and its resources out of the unit-test classpath, which it only
    // finds when the resources are packaged for unit tests — without this every
    // `createComposeRule` test fails on a missing `test_config.properties`.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Line coverage of the unit tests (MAG-105), read by scripts/coverage/. Generated
// code is left out: nobody writes a test for it and it would only dilute the figure.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "*.BuildConfig",
                    "*_Impl",
                    "*_Impl\$*",
                    "*ComposableSingletons*",
                )
            }
        }
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)

    // Biometric
    implementation(libs.biometric)

    // Ktor (HTTP + SSE)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.serialization.json)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Credentials (Google Sign-In)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)

    // DataStore
    implementation(libs.datastore.preferences)

    // Calendar
    implementation(libs.calendar.compose)

    // RRULE
    implementation(libs.lib.recur)

    // Reorderable
    implementation(libs.reorderable)

    // Koin (DI)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // AndroidX
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.splashscreen)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.ktor.client.mock)

    // Screen tests on the JVM (MAG-242): what used to need an emulator to read a
    // screen — the list a screen draws, the order it draws it in, a loading and an
    // error state, navigation — runs here in seconds instead.
    // `agent-os/standards/mobile/screen-tests.md` says what belongs here
    // and what stays in Maestro.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
