import kotlin.math.min
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    id("android-module-dependencies")
    id("test-module-dependencies")
    jacoco
}

android {
    namespace = "app.aaps.core.ui"
    defaultConfig {
        minSdk = min(Versions.minSdk, Versions.wearMinSdk)
    }

    buildFeatures {
        compose = true
    }
}

// Robolectric runs tests in its own classloader sandbox and rewrites bytecode, so the default
// JaCoCo on-the-fly agent records no coverage for the Compose classes exercised by those tests.
// includeNoLocationClasses lets JaCoCo account for the Robolectric-loaded classes.
tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

dependencies {
    api(libs.androidx.appcompat)

    api(libs.com.google.android.material)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.runtime.compose)

    api(libs.com.google.dagger.android)
    api(libs.com.google.dagger.android.support)

    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:data"))
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Compose UI tests on the JVM via Robolectric.
    // createComposeRule() is a JUnit4 TestRule and RobolectricTestRunner is a JUnit4 runner, so these
    // tests run JUnit4-style; the vintage engine bridges them onto the JUnit Platform alongside the
    // existing Jupiter tests (useJUnitPlatform() comes from test-module-dependencies).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.org.robolectric)
    testRuntimeOnly(libs.org.junit.vintage.engine)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
