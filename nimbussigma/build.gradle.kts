plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "space.nimbusgo.sigma"
    compileSdk = 35

    defaultConfig {
        // androidx.javascriptengine, Sigma's sandboxed runtime, needs Android 8.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        // Live tests against a running cloud: -Pandroid.testInstrumentationRunnerArguments.cloud=…
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Readable by apps on Kotlin 1.9 and later (React Native apps among them):
        // a library compiled for a newer Kotlin breaks their builds.
        languageVersion = "1.9"
        apiVersion = "1.9"
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    // Declare the stdlib that matches languageVersion, not the compiler's own.
    coreLibrariesVersion = "1.9.24"
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.javascriptengine:javascriptengine:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.CodeSyncr"
                artifactId = "sigma-android"
                version = project.findProperty("version")?.toString()?.takeIf { it != "unspecified" } ?: "1.0.2"
            }
        }
    }
}
