plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Versioned in the root build file, where it sits next to the Kotlin
    // version it has to match exactly.
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing, read from ~/.gradle/gradle.properties rather than from
// anything in this repository. The keystore is a long-lived secret: losing it
// means never shipping an update that upgrades in place under the same
// identity, and committing it would be worse.
//
// All four deliberately fall back to null. A machine without them still builds
// a release -- unsigned, and named so -- instead of failing, which keeps the
// project buildable by anyone who clones it.
//
// The four property names still say `quill`, and stay that way on purpose.
// They are looked up in ~/.gradle/gradle.properties, which is outside this
// repository and holds the keystore password in cleartext. Renaming them here
// without editing that file is silent: the lookups return null, `canSignRelease`
// goes false, and the release build succeeds -- unsigned. A rename that can
// only fail quietly is not worth the tidiness.
val quillKeystoreFile = providers.gradleProperty("quillKeystoreFile").orNull
val quillKeystorePassword = providers.gradleProperty("quillKeystorePassword").orNull
val quillKeyAlias = providers.gradleProperty("quillKeyAlias").orNull
val quillKeyPassword = providers.gradleProperty("quillKeyPassword").orNull
val canSignRelease = listOf(
    quillKeystoreFile, quillKeystorePassword, quillKeyAlias, quillKeyPassword,
).all { it != null } && file(quillKeystoreFile!!).exists()

android {
    namespace = "io.github.v_inn.foxloop"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.v_inn.foxloop"
        minSdk = 26
        targetSdk = 36
        // Two numbers with very different lifetimes, and only one of them is
        // for people.
        //
        // `versionCode` is Play's ordering key. It can never go down, not even
        // across a deleted release: the store refuses an upload whose code is
        // not above every code it has already seen, forever, and there is no
        // way to reclaim a burnt one. So it is a plain counter, incremented by
        // one per uploaded build -- deliberately not derived from the version
        // name, a date, or a commit count, all of which can move backwards or
        // collide when a release is rebuilt.
        //
        // `versionName` is the string a person reads, and `release-apk.sh`
        // requires the tag it is publishing to match it exactly -- that check
        // is the only thing keeping a `v0.2` release from carrying a build that
        // still calls itself 0.1.
        //
        // 2/"0.2.0": the first release to carry the FoxLoop name, a launcher
        // icon and a project page.
        //
        // `versionName` gained a third component here. It had been "0.1" while
        // the only tag in the repository was `v0.1.0`, which nothing noticed
        // because nothing had been published through `release-apk.sh` yet --
        // and that script compares the tag against this string exactly, so the
        // two spellings had to converge or the first real publish would have
        // been refused. They converge on the tag's shape, which also matches
        // the daemon's own 0.2.0 in Cargo.toml.
        versionCode = 2
        versionName = "0.2.0"
    }

    // Two products from one tree: FoxLoop, the drawing display, and FoxLoop
    // Console, a terminal for a headless machine over the same AOA cable.
    //
    // Flavors and not a branch, for one concrete reason: the console's web
    // dashboards need INTERNET, and the display's release manifest deliberately
    // asks for nothing. A flavor gives each its own manifest while the
    // transport code stays one copy in src/main.
    //
    // Two application IDs, so both install side by side. Android routes each
    // accessory to the app whose accessory_filter.xml matches the model string
    // the daemon sent, so the two never compete for a cable.
    //
    // `display` keeps the applicationId FoxLoop has always shipped under. That
    // is not a default to tidy: an update only installs over an existing copy
    // with the same ID *and* signing key.
    flavorDimensions += "product"
    productFlavors {
        create("display") {
            dimension = "product"
            manifestPlaceholders["appLabel"] = "FoxLoop"
        }
        create("console") {
            dimension = "product"
            applicationIdSuffix = ".console"
            manifestPlaceholders["appLabel"] = "FoxLoop Console"
            // The SSH stack needs R8 rules the display has no use for; see the
            // file. Applies to minified (release) builds only.
            proguardFiles("proguard-console.pro")
        }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(quillKeystoreFile!!)
                storePassword = quillKeystorePassword
                keyAlias = quillKeyAlias
                keyPassword = quillKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Null when the key isn't configured; the build then produces
            // app-<flavor>-release-unsigned.apk, which is the honest outcome.
            signingConfig = signingConfigs.findByName("release")
            // Compose ships its own R8 rules, so nothing has to be authored
            // here -- but it has to actually run, or the Compose runtime is
            // carried whole. Turned on with this change, which is also the
            // first time R8 has ever run on this codebase.
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG: the adb-forward transport is a development
        // path and must not be reachable in a release build. See
        // MainActivity's connection loop.
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Packaging exclusions for the console only, applied per variant so the
// display's APK keeps every file it has always shipped.
androidComponents {
    onVariants { variant ->
        if (variant.flavorName != "console") return@onVariants
        // Termux's terminal-emulator carries libtermux.so, which only its
        // TerminalSession uses -- to fork a local shell. The console never
        // touches that class (its shell is at the other end of an SSH channel),
        // so the library is four ABIs of dead weight.
        variant.packaging.jniLibs.excludes.add("**/libtermux.so")
        // OSGi bundle metadata that BouncyCastle's three jars and jspecify each
        // carry at the same path, which fails the merge. Meaningless on Android.
        variant.packaging.resources.excludes.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

// Was `android { kotlinOptions { jvmTarget = "17" } }`, which Kotlin 2.2
// deprecates and 2.3 removes. Must stay 17 to match compileOptions above, or
// AGP fails the build on inconsistent JVM-target compatibility -- the JDK doing
// the building (21 here) is a separate thing from the bytecode level produced.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")

    // Compose, for the settings screen only -- MainActivity, GearButton and
    // CursorOverlay stay plain Views and never load a Compose class, so the
    // decode path is untouched. Compose loads at the moment the video surface
    // is being torn down anyway.
    //
    // `foundation`, deliberately not `material3`: this design replaces every
    // Material default it would have supplied (the switch, the ground colour,
    // the type ramp, the elevation model), so ~1.4 MB of defaults would only
    // have been fought at every control. See ui/FoxLoopTheme.kt for the handful
    // of things built in its place.
    //
    // 2025.06.01 is Compose 1.8.3, the runtime generation contemporaneous with
    // the Kotlin 2.2 compiler above. That pairing is the reason for the pin:
    // there is no published minimum runtime version for a given Compose
    // compiler, and a mismatch does not fail the build -- it throws
    // IncompatibleComposeRuntimeVersionException at runtime, on the one screen
    // that uses Compose. Move the BOM and the Kotlin version together.
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // The first tests this project has had on the Android side. Plain JUnit on
    // the JVM, deliberately: everything covered is pure arithmetic over Ints and
    // data classes, so it needs no device, no emulator and no Robolectric. The
    // logic worth testing was made reachable without Android precisely so this
    // stayed true -- see PanelGeometry.
    testImplementation("junit:junit:4.13.2")

    // FoxLoop Console only. Neither reaches the display's APK.
    //
    // sshj speaks SSH straight over the accessory streams (`connectVia`), so the
    // daemon on the other end stays a dumb pipe to sshd and the terminal needs no
    // socket at all. 0.41.1 rather than the newest 0.41.2, which was days old
    // when this was written; it brings BouncyCastle 1.84, registered over
    // Android's stripped-down "BC" provider in ConsoleApp.
    "consoleImplementation"("com.hierynomus:sshj:0.41.1")
    // Already in sshj's runtime dependencies, but runtime-scoped, so not on the
    // compile classpath that ConsoleApp needs it on. Pinned to the version sshj
    // 0.41.1 itself asks for; move the two together.
    "consoleImplementation"("org.bouncycastle:bcprov-jdk18on:1.84")
    // Termux's terminal-view (and through it terminal-emulator) -- Apache 2.0,
    // unlike the rest of termux-app, which is GPLv3. Only these two modules,
    // never termux-shared, which is outside that exception. The emulator's
    // constructor has changed across releases; ConsoleTerminalView is written
    // against this tag.
    "consoleImplementation"("com.github.termux.termux-app:terminal-view:v0.118.3")

    // androidx.appcompat was declared but never imported -- the only androidx
    // imports in the app are WindowCompat/WindowInsets* from core-ktx. Dropped
    // with this change, which claws back part of what Compose adds.
}
