import groovy.json.JsonSlurper
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

// The maker's single source of truth. config.json is read here as well as in
// build.rs so the Android label, the scanner intent action and the kiosk flags
// all come from the one file a maker edits.
val shellConfig = JsonSlurper().parse(file("../../../../config.json")) as Map<*, *>
val scannerConfig = shellConfig["scanner"] as Map<*, *>

android {
    compileSdk = 37
    namespace = "com.powerapps.handheld"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        manifestPlaceholders["appLabel"] = shellConfig["appName"] as String
        manifestPlaceholders["scannerIntentAction"] = scannerConfig["intentAction"] as String
        applicationId = "com.powerapps.handheld"
        minSdk = 24
        targetSdk = 37
        versionCode = tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()
        versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")
        buildConfigField(
            "String",
            "SCANNER_INTENT_EXTRA_KEY",
            "\"${scannerConfig["intentExtraKey"]}\""
        )
        buildConfigField("boolean", "KEEP_SCREEN_ON", "${shellConfig["keepScreenOn"]}")
        buildConfigField("boolean", "INTERCEPT_BACK_BUTTON", "${shellConfig["interceptBackButton"]}")
    }
    signingConfigs {
        // A public development key. These APKs are sideloaded internally and never
        // published, so the key is committed on purpose: every build from every
        // machine installs over the previous one instead of colliding on update.
        create("dev") {
            storeFile = file("dev-release.keystore")
            storePassword = "powerapps"
            keyAlias = "powerapps"
            keyPassword = "powerapps"
        }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {
                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("dev")
            optimization {
               enable = true
            }
            proguardFiles(
                *fileTree(".") {
                  include("**/*.pro")
                  exclude("build/**")
                }.files.toTypedArray()
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

rust {
    rootDirRel = "../../../"
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

apply(from = file("tauri.build.gradle.kts"))
