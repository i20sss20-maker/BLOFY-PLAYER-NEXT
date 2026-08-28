import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
}

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val portalUrl = providers.gradleProperty("BLOFY_BASE_URL")
    .orElse(providers.environmentVariable("BLOFY_BASE_URL"))
    .orElse("https://blofy-player-2026-production.up.railway.app")

// Empty by default: builds without an explicitly injected production trust
// anchor reject every remote policy and keep compiled-safe defaults.
val remoteConfigKeyId = providers.gradleProperty("BLOFY_REMOTE_CONFIG_KEY_ID")
    .orElse(providers.environmentVariable("BLOFY_REMOTE_CONFIG_KEY_ID"))
    .orElse("")
val remoteConfigPublicKey = providers.gradleProperty("BLOFY_REMOTE_CONFIG_PUBLIC_KEY_SPKI")
    .orElse(providers.environmentVariable("BLOFY_REMOTE_CONFIG_PUBLIC_KEY_SPKI"))
    .orElse("")
val configuredRemoteConfigKeyId = remoteConfigKeyId.get().trim()
    .takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) }
    .orEmpty()
val configuredRemoteConfigPublicKey = remoteConfigPublicKey.get()
    .replace(Regex("\\s+"), "")
    .takeIf { it.matches(Regex("[A-Za-z0-9_+/=-]*")) }
    .orEmpty()

val ffmpegLockFile = rootProject.file("config/media3-ffmpeg.properties")
val ffmpegLock = Properties().apply {
    ffmpegLockFile.inputStream().use(::load)
}
val ffmpegAar = rootProject.file(
    ffmpegLock.getProperty("AAR_PATH")
        ?: error("AAR_PATH is missing from ${ffmpegLockFile.path}")
)

android {
    namespace = "tv.blofy.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.blofy.player"
        minSdk = 23
        targetSdk = 36
        // NEXT keeps the same package and signer, but uses a higher code than the v340 line.
        versionCode = 1001001
        versionName = "2026.08-NEXT.1"
        buildConfigField("String", "BLOFY_BASE_URL", quoted(portalUrl.get().trimEnd('/')))
        buildConfigField("String", "BLOFY_REMOTE_CONFIG_KEY_ID", quoted(configuredRemoteConfigKeyId))
        buildConfigField("String", "BLOFY_REMOTE_CONFIG_PUBLIC_KEY_SPKI", quoted(configuredRemoteConfigPublicKey))
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
        isCoreLibraryDesugaringEnabled = true
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

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

    // Built by scripts/build-media3-ffmpeg.sh. Debug remains usable without the
    // binary, while preReleaseBuild below makes the extension mandatory for release.
    if (ffmpegAar.exists()) implementation(files(ffmpegAar))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
}

val verifyMedia3FfmpegAar = tasks.register("verifyMedia3FfmpegAar") {
    group = "verification"
    description = "Rejects a missing, stale, or structurally incomplete Media3 FFmpeg AAR."
    doLast {
        if (!ffmpegAar.isFile || ffmpegAar.length() == 0L) {
            throw GradleException(
                "Pinned Media3 FFmpeg AAR is missing: ${ffmpegAar.path}. " +
                    "Run scripts/build-media3-ffmpeg.sh first."
            )
        }
        ZipFile(ffmpegAar).use { archive ->
            val required = listOf(
                "classes.jar",
                "jni/armeabi-v7a/libffmpegJNI.so",
                "jni/arm64-v8a/libffmpegJNI.so",
                "META-INF/blofy-media3-ffmpeg.properties",
            )
            required.forEach { entry ->
                if (archive.getEntry(entry) == null) {
                    throw GradleException("Media3 FFmpeg AAR is missing $entry")
                }
            }

            val receipt = archive.getInputStream(
                archive.getEntry("META-INF/blofy-media3-ffmpeg.properties")
            ).use { it.readBytes() }
            if (!receipt.contentEquals(ffmpegLockFile.readBytes())) {
                throw GradleException("Media3 FFmpeg AAR does not match the pinned build inputs.")
            }

            var rendererFound = false
            ZipInputStream(archive.getInputStream(archive.getEntry("classes.jar"))).use { classes ->
                while (true) {
                    val entry = classes.nextEntry ?: break
                    if (entry.name == "androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.class") {
                        rendererFound = true
                        break
                    }
                }
            }
            if (!rendererFound) {
                throw GradleException("FfmpegAudioRenderer is missing from the decoder AAR.")
            }
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyMedia3FfmpegAar)
}

tasks.register("verifyReleaseFfmpegPackaging") {
    group = "verification"
    description = "Checks FFmpeg presence, license notices, and ARM-only JNI in the release APK."
    dependsOn("assembleRelease")
    doLast {
        val apks = fileTree(layout.buildDirectory.dir("outputs/apk/release")) {
            include("*.apk")
        }.files.toList()
        if (apks.size != 1) {
            throw GradleException("Expected exactly one release APK, found ${apks.size}.")
        }
        ZipFile(apks.single()).use { apk ->
            val entries = apk.entries().asSequence().map { it.name }.toSet()
            val required = setOf(
                "lib/armeabi-v7a/libffmpegJNI.so",
                "lib/arm64-v8a/libffmpegJNI.so",
                "assets/licenses/ffmpeg/NOTICE.txt",
                "assets/licenses/ffmpeg/COPYING.LGPLv2.1",
            )
            val missing = required - entries
            if (missing.isNotEmpty()) {
                throw GradleException("Release APK is missing: ${missing.sorted().joinToString()}")
            }
            val nativeAbis = entries
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.substringAfter("lib/").substringBefore('/') }
                .toSet()
            val unexpected = nativeAbis - setOf("armeabi-v7a", "arm64-v8a")
            if (unexpected.isNotEmpty()) {
                throw GradleException(
                    "Release APK contains non-ARM native libraries: ${unexpected.sorted().joinToString()}"
                )
            }
        }
    }
}
