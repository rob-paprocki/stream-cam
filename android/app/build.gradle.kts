import com.android.build.api.variant.HostTestBuilder
import com.mikepenz.aboutlibraries.plugin.StrictMode

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

// The app's version is the one line in the VERSION file at the top of the repository, such as 1.2.3. The
// release tooling sets it. versionCode is worked out from it as MAJOR * 1,000,000 + MINOR * 1,000 + PATCH,
// so every later version gets a bigger code. The build stops if the file holds anything else.
val versionFile = rootProject.layout.projectDirectory.file("../VERSION")
val appVersion: String = providers.fileContents(versionFile).asText.orNull?.trim()
    ?: throw GradleException("${versionFile.asFile.path} is missing. It holds the app's version, such as 1.2.3.")
val appVersionCode: Int = run {
    val parts = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersion)?.groupValues?.drop(1)?.map { it.toIntOrNull() }
    val (major, minor, patch) = parts ?: listOf(null, null, null)
    if (major == null || minor == null || patch == null || major > 2099 || minor > 999 || patch > 999) {
        throw GradleException(
            "VERSION must hold one version number MAJOR.MINOR.PATCH, with MAJOR at most 2099 and MINOR and " +
                "PATCH at most 999, not \"$appVersion\"",
        )
    }
    major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "org.example.projectname"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.example.projectname"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // The open-source build. It contains no proprietary code (spec LIC-5).
        create("foss") {
            dimension = "distribution"
            isDefault = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            // See the androidComponents block below.
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // F-Droid rejects Google's dependency-metadata signing block, and it breaks reproducible builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// AGP makes a Compose Preview task for every variant, and the task can only be set up when the variant has
// unit tests that include the app's resources. AGP 9 leaves unit tests out of release builds, so without
// this and isIncludeAndroidResources above, `gradlew tasks --all` fails. CI still runs the debug tests only.
androidComponents {
    beforeVariants { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

// Licence gate (spec LIC-2) and the licence list for the About screen (LIC-4). The AboutLibraries plugin
// runs as part of every build of every variant, and the build stops when a library's licence isn't on
// the allowed list or no licence is found for it. Test-only libraries aren't collected.
aboutLibraries {
    collect {
        // Hand-written entries, for libraries with no Maven metadata (native code) or with incomplete
        // metadata. The folder is at the top of the repository.
        configPath = rootProject.layout.projectDirectory.dir("../config/aboutlibraries")
    }
    export {
        // The generation time would make two builds of the same commit differ.
        includeMetaData = false
        excludeFields.add("generated")
    }
    license {
        strictMode = StrictMode.FAIL
        // Permissive licences only (SPDX IDs). A library under anything else needs the owner's approval
        // first (LIC-3), and copyleft such as GPL or AGPL is never allowed (LIC-2).
        allowedLicenses.addAll("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC")
    }
    library {
        requireLicense = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.rootencoder.library) {
        // WHIP (WebRTC) output isn't used, and it would pull in Bouncy Castle.
        exclude(group = "com.github.pedroSG94.RootEncoder", module = "whip")
    }
    testImplementation(libs.junit)
}

// Optional extra product flavours are applied from this file when it is present.
// Without it, the build has the foss flavour alone.
val extraFlavours = file("extra-flavours.gradle.kts")
if (extraFlavours.exists()) {
    apply(from = extraFlavours)
}
