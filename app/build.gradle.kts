import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.glut.schedule"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glut.schedule"
        minSdk = 26
        targetSdk = 36
        versionCode = 108
        versionName = "0.14.10"

    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use(::load)
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(
                keystoreProperties.getProperty("storeFile")
                    ?: error("Missing storeFile in keystore.properties")
            )
            storePassword = keystoreProperties.getProperty("storePassword")
                ?: error("Missing storePassword in keystore.properties")
            keyAlias = keystoreProperties.getProperty("keyAlias")
                ?: error("Missing keyAlias in keystore.properties")
            keyPassword = keystoreProperties.getProperty("keyPassword")
                ?: error("Missing keyPassword in keystore.properties")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                (this as BaseVariantOutputImpl).outputFileName = "glutShedule_$versionName.apk"
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    ksp("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// ── Publishing ──
// Test builds: use .\gradlew.bat assembleDebug or assembleRelease (no publishing)
// Release:     use .\gradlew.bat publishUpdate (builds + GitHub Release + CF Pages)
tasks.register("publishUpdate") {
    group = "publishing"
    description = "Build release APK, create GitHub Release, and sync to Cloudflare Pages update host"

    dependsOn("assembleRelease")

    doLast {
        val versionCode = android.defaultConfig.versionCode
        val versionName = android.defaultConfig.versionName
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apkFile = apkDir.listFiles()?.find { it.name.endsWith(".apk") }
            ?: error("Release APK not found in $apkDir")
        val rootDir = file("${rootProject.projectDir}")
        val updateDir = file("$rootDir/../app-update-host")
        val apkName = apkFile.name

        // ── 1. Create GitHub Release ──
        try {
            exec {
                workingDir = rootDir
                commandLine("gh", "release", "create", "v$versionName", apkFile.absolutePath,
                    "--title", "v$versionName",
                    "--notes", "Release v$versionName")
            }
            println("Created GitHub Release: v$versionName")
        } catch (e: Exception) {
            println("WARNING: GitHub Release creation failed: ${e.message}")
            println("CF Pages sync will still proceed.")
        }

        // ── 2. Sync to Cloudflare Pages ──
        if (!updateDir.exists()) {
            throw GradleException("app-update-host directory not found at ${updateDir.absolutePath}. " +
                "Clone it: git clone https://github.com/hzhkdh/app-update-host.git ${updateDir.absolutePath}")
        }

        apkFile.copyTo(File(updateDir, apkName), overwrite = true)
        println("Copied $apkName to app-update-host/")

        val updateJson = File(updateDir, "update.json")
        val json = groovy.json.JsonOutput.toJson(mapOf(
            "versionCode" to versionCode,
            "versionName" to versionName,
            "downloadUrl" to "https://update.999314.xyz/$apkName",
            "updateDesc" to "",
            "forceUpdate" to false
        ))
        updateJson.writeText(groovy.json.JsonOutput.prettyPrint(json))
        println("Updated update.json")

        exec {
            workingDir = updateDir
            commandLine("git", "-C", updateDir.absolutePath, "add", "-A")
        }
        exec {
            workingDir = updateDir
            commandLine("git", "-C", updateDir.absolutePath, "commit", "-m", "v$versionName")
        }
        exec {
            workingDir = updateDir
            commandLine("git", "-C", updateDir.absolutePath, "push", "origin", "main")
        }
        println("Pushed app-update-host → Cloudflare Pages deploying (~30s)")
        println("✅ v$versionName published: GitHub Release + CF Pages")
    }
}
