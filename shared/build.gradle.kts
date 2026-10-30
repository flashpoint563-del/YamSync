plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("plugin.serialization") version "2.4.10"
    id("com.android.library")
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core"))
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("io.ktor:ktor-client-core:3.0.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("com.google.zxing:core:3.5.3")
                implementation("com.google.zxing:javase:3.5.3")
            }
        }

        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        jvmMain {
            dependencies {
                implementation(libs.kotlinx.coroutinesSwing)
                // MP3 playback fallback
                implementation("javazoom:jlayer:1.0.1")
                // FLAC playback fallback
                implementation("org.jflac:jflac-codec:1.5.2")

                // JavaFX Media (AAC, M4A, MP3, WAV) for Windows, Linux, macOS
                val javafxVersion = "21.0.6"
                implementation("org.openjfx:javafx-base:$javafxVersion")
                implementation("org.openjfx:javafx-graphics:$javafxVersion")
                implementation("org.openjfx:javafx-media:$javafxVersion")
                for (platform in listOf("win", "linux", "mac")) {
                    implementation("org.openjfx:javafx-base:$javafxVersion:$platform")
                    implementation("org.openjfx:javafx-graphics:$javafxVersion:$platform")
                    implementation("org.openjfx:javafx-media:$javafxVersion:$platform")
                }
            }
        }

        androidMain {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.0")
                val media3Version = "1.5.0"
                implementation("androidx.media3:media3-exoplayer:$media3Version")
                implementation("androidx.media3:media3-common:$media3Version")
            }
        }
    }
}

android {
    namespace = "io.github.audiz.shared"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/androidMain/jniLibs")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
}
tasks.withType<Test> {
    systemProperty("java.util.prefs.userRoot", "${layout.buildDirectory.get().asFile.absolutePath}/test-prefs")
}

