plugins {
    id("com.android.application")
}

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val portalUrl = providers.gradleProperty("BLOFY_BASE_URL")
    .orElse(providers.environmentVariable("BLOFY_BASE_URL"))
    .orElse("https://blofy-player-2026-production.up.railway.app")

android {
    namespace = "tv.blofy.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.blofy.player"
        minSdk = 23
        targetSdk = 36
        // NEXT keeps the same package and signer, but uses a higher code than the v340 line.
        versionCode = 1001000
        versionName = "2026.08-NEXT.0"
        buildConfigField("String", "BLOFY_BASE_URL", quoted(portalUrl.get().trimEnd('/')))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".nextdebug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    val media3 = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-cronet:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("com.google.android.gms:play-services-cronet:18.0.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.core:core:1.17.0")
    implementation("com.google.zxing:core:3.5.4")

    // Container/codec fallback. Media3 remains the primary engine.
    implementation("org.videolan.android:libvlc-all:3.7.5")

    // CI builds the Media3 FFmpeg extension into this location. It is deliberately
    // ignored by Git so binary decoder artifacts are never treated as source.
    val ffmpeg = file("libs/media3-decoder-ffmpeg-release.aar")
    if (ffmpeg.exists()) implementation(files(ffmpeg))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
}
