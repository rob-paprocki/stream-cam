// Plain Kotlin (JVM) module: app logic with no Android dependency, so its unit tests run fast on any machine.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The profile tests read the schema and example files in shared/ at the top of the repository. Declaring
// the folder as an input makes the tests run again when those files change.
val sharedDir = rootProject.layout.projectDirectory.dir("../shared")
tasks.test {
    inputs.dir(sharedDir).withPropertyName("sharedProfileFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    // A path relative to this module (the tests' working folder), so the build cache stays relocatable.
    systemProperty("project.sharedDir", "../../shared")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
