plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kotlinsyd.forte"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.kotlinsyd.forte"
        minSdk = 26
        targetSdk = 35
        versionCode = 50
        versionName = "1.0.23"

        // === SENTRY DSN (Forte's own project) ==========================
        // The DSN of Forte's own Sentry project is read from a private
        // file outside this repo —
        // ../forte-private/.sentry_dsn — so the key never lands in the
        // committed source. Missing file = empty DSN = crash reporting is
        // a safe no-op (see CrashReports.kt). Forte reports to its own
        // project only — no other project's DSN belongs here.
        val sentryDsn = rootProject.file("../forte-private/.sentry_dsn")
            .takeIf { it.isFile }?.readText()?.trim().orEmpty()
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
    }

    signingConfigs {
        create("house") {
            storeFile = file("/home/hatch/workspace/.tools/pulsestream-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("house")
        }
        release {
            signingConfig = signingConfigs.getByName("house")
            isMinifyEnabled = false
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.version",
                "META-INF/versions/**"
            )
        }
    }
    lint {
        abortOnError = false
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { t ->
                // Robolectric fetches its android-all runtime through
                // the same egress proxy Gradle uses. gradle.properties
                // systemProps reach the daemon; forward them so the
                // forked test JVM can resolve it too (the test class
                // installs the matching Authenticator).
                for (k in listOf(
                    "http.proxyHost", "http.proxyPort", "http.proxyUser",
                    "http.proxyPassword", "https.proxyHost",
                    "https.proxyPort", "https.proxyUser",
                    "https.proxyPassword",
                )) {
                    System.getProperty(k)?.let { v -> t.systemProperty(k, v) }
                }
                // Robolectric's android-all runtime is served from a
                // local file repository (populated by curl): the
                // egress proxy's per-session credential can die
                // mid-run, and a local repo cannot.
                t.systemProperty("robolectric.dependency.repo.id", "local")
                t.systemProperty(
                    "robolectric.dependency.repo.url",
                    "file:///home/hatch/workspace/forte/robolectric-repo/",
                )
                t.maxHeapSize = "1536m"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation("io.sentry:sentry-android:8.14.0")
    // Local parse checks for the account-identity chain
    // (YtAccountParseTest). org.json on the JVM: the android.jar
    // stub's org.json throws in local unit tests, the real jar
    // shadows it.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Pull-path UI tests (Robolectric + Compose): the Home pull
    // gesture is exercised against the real refresh container and
    // hero composable, not a re-implementation.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.ui.test.junit4)
    // ui-test-manifest declares ComponentActivity; it must merge
    // into the DEBUG variant's manifest, which is the manifest
    // the Robolectric compose tests launch against (the release
    // packaged manifest declares only MainActivity).
    debugImplementation(libs.androidx.ui.test.manifest)
}
