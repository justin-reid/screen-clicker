plugins {
    jacoco
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Without this the APK is named after the Gradle module, i.e. app-debug.apk. AGP builds
// output names as "<archivesName>-<variant>.apk", so this applies to local builds too,
// not just the CI artifact.
base {
    archivesName.set("screen-clicker")
}

android {
    namespace = "com.screenclicker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.screenclicker"
        minSdk = 30 // AccessibilityService.takeScreenshot() needs API 30 (Android 11).
        targetSdk = 36
        // CI passes -PversionCode=<run number> and -PversionNameSuffix=-<short sha>, so every
        // published build is a strictly newer version and says which commit it is. Android
        // refuses an APK with a lower versionCode, and when an "Update" prompt quietly does
        // nothing there is otherwise no way to tell on the device whether the file being
        // installed is even the new build. Local builds stay at 1.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "0.1.0" + ((project.findProperty("versionNameSuffix") as String?) ?: "")
    }

    signingConfigs {
        getByName("debug") {
            // Point the debug config at an explicit file rather than trusting AGP to
            // find ~/.android/debug.keystore -- it resolves that through
            // ANDROID_USER_HOME / user.home, which does not agree with $HOME on a CI
            // runner. When the file is absent (a normal local checkout) AGP falls back
            // to its usual auto-generated keystore, so local builds are unaffected.
            val shared = rootProject.file("debug.keystore")
            if (shared.exists()) {
                storeFile = shared
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Deliberately off: this is a sideloaded personal app with no size budget, and
            // with shrinking off there are no keep rules to get wrong around
            // kotlinx.serialization or the accessibility service.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the same shared key as the debug build, so it installs straight
            // over a debug copy. This variant is not a distribution build — nothing here is
            // hardened, and it exists to be *measured* on the phone beside the debug one. A
            // debuggable APK runs app code without ART's optimizing compiler, which is the
            // first thing to rule out for the matcher's tight integer loops (see AGENTS.md,
            // "the scan's cost": ~5.1s on the device against ~165ms for the same geometry
            // on a laptop JVM). Left unsigned when the shared keystore is absent, which is
            // the normal local case — `assembleRelease` then yields *-unsigned.apk.
            if (rootProject.file("debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation(libs.junit4)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// JVM logic coverage: Android framework/UI services need a device, so this report focuses
// on the matcher, runner, and serializable model that are executable in local unit tests.
tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoDebugUnitTestReport") {
    dependsOn("testDebugUnitTest")
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("jacoco/testDebugUnitTest.exec")
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        include("com/screenclicker/engine/**")
        include("com/screenclicker/model/**")
        include("com/screenclicker/vision/**")
    })
    sourceDirectories.setFrom(files("src/main/java"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Fail CI if changes to the matcher/runner/models reduce tested source-line coverage.
tasks.register<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoDebugUnitTestCoverageVerification") {
    dependsOn("jacocoDebugUnitTestReport")
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("jacoco/testDebugUnitTest.exec")
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        include("com/screenclicker/engine/**")
        include("com/screenclicker/model/**")
        include("com/screenclicker/vision/**")
    })
    sourceDirectories.setFrom(files("src/main/java"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.98".toBigDecimal()
            }
        }
    }
}
