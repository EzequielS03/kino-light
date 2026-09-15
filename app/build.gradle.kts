plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/** Reads a key from the repo root's .env file (to avoid hardcoding credentials). */
fun readEnv(key: String, default: String = ""): String {
    val f = rootProject.file(".env")
    if (!f.exists()) return default
    return f.readLines()
        .firstOrNull { it.trim().startsWith("$key=") }
        ?.substringAfter("=")?.trim()?.trim('"')?.trim('\'') ?: default
}

/**
 * Fixed XOR mask for embedded native constants (see "String obfuscation of the embedded
 * constants" in docs/superpowers/specs/2026-09-15-split-credential-activation-design.md).
 * Hardcoded here AND in native_credentials.cpp's identical `kObfuscationMask` -- not a secret:
 * whoever disassembles the compiled .so finds this same constant there too, since the native
 * module needs it to de-obfuscate at runtime. Its only job is defeating a naive `strings`/grep
 * scan of the compiled binary, which it does regardless of whether this file (public, in git)
 * also shows it.
 */
val nativeObfuscationMask = byteArrayOf(0x5A, 0x3C, 0x91.toByte(), 0x0F, 0x7E, 0x22, 0xC8.toByte(), 0x64)

fun xorMask(bytes: ByteArray): ByteArray =
    ByteArray(bytes.size) { i -> (bytes[i].toInt() xor nativeObfuscationMask[i % nativeObfuscationMask.size].toInt()).toByte() }

/**
 * Same rule as `CredentialSplit.split` (app/src/main/java/com/arkiv/player/data/credentials/CredentialSplit.kt)
 * -- duplicated here because this Gradle script has no `buildSrc` to share code with the app
 * module. If the split rule ever changes, update both.
 */
fun nativeHalf(value: String): String {
    val sb = StringBuilder()
    for (i in value.indices) if (i % 2 != 0) sb.append(value[i])
    return sb.toString()
}

android {
    namespace = "com.arkiv.player"
    compileSdk = 35
    // r28+: the linker defaults to 16 KB-aligned LOAD segments, which newer Android devices
    // require. r26 didn't, and left our own native module (libcredentials.so) unaligned.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.arkiv.player.light" // own id: full Arkiv and Arkiv Light coexist on the same device
        minSdk = 26
        targetSdk = 35
        // `ADULT_CODE` used to live here, the 18+ lock's code baked in from .env at build time. Not
        // anymore: the person picks the code in Settings and it starts at a public default (see
        // `CandadoDeAdultos`). An APK distributed with a code only the person who built it knew left
        // the section locked for everyone else.
        // Cast receiver to launch on the TV. Empty falls back to Google's Default Media Receiver,
        // which cannot play the MPEG-TS Magis serves -- see `receiver/index.html`. It lives in the
        // .env because it is registered per developer account in the Cast Developer Console, so a
        // checkout without one still builds and still casts what the default receiver can handle.
        buildConfigField("String", "CAST_RECEIVER_ID", "\"${readEnv("CAST_RECEIVER_ID")}\"")
        // Frozen on purpose: CI always overrides both via .env for a real release build (see
        // .github/workflows/release.yml). These are only what a plain local `assembleDebug` gets.
        versionCode = readEnv("VERSION_CODE").toIntOrNull() ?: 48
        versionName = readEnv("VERSION_NAME").ifBlank { "0.9.17" }
        // Task 8 (Step 3): `ARKIV_API_KEY` used to live here, the last build-time credential still
        // left in the APK -- a compiled-in constant, the same for every device, that anyone who
        // opened the APK could extract. Gone entirely: the app now authenticates with the PER-DEVICE
        // credential already issued at sign-up (person session + device), revocable one at a time.
        // See `docs/INVENTARIO_DE_LLAVES.md`.
        ndk {
            // Only real-device ABIs (phone arm64, Fire Stick armeabi-v7a).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    // Release signing from .env (same mechanism as the credentials: the file is gitignored, so the
    // keystore and its password never enter the repo). If it isn't configured, the block isn't
    // created and `assembleRelease` comes out unsigned -- on purpose: better than silently falling
    // back to signing with the debug key.
    val keystorePath = readEnv("RELEASE_KEYSTORE_PATH")
    val hasSigningConfig = keystorePath.isNotBlank() && file(keystorePath).exists()
    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = readEnv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = readEnv("RELEASE_KEY_ALIAS")
                keyPassword = readEnv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // R8 is ON. It was off for years because libVLC reached classes and fields through JNI
            // that the shrinker could not see referenced and would strip; libVLC is deleted, so the
            // reason went with it. Measured 2026-09-12, same build type both ways: the release APK
            // goes 20,151,333 -> 7,404,256 bytes with R8, and it builds with no extra keep rules.
            // NOT exercised on a device yet: installing a release build means uninstalling the debug
            // one, which wipes app data, so that check waits for a device that can afford it.
            isMinifyEnabled = true
            if (hasSigningConfig) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val generateNativeSecretsHeader = tasks.register("generateNativeSecretsHeader") {
    doLast {
        val cppDir = file("src/main/cpp")
        cppDir.mkdirs()

        fun maskedLiteral(value: String): String =
            xorMask(value.toByteArray(Charsets.UTF_8)).joinToString(", ") { "0x%02x".format(it) }

        fun field(name: String, value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return "static const unsigned char $name[] = { ${maskedLiteral(value)} };\n" +
                "static const int ${name}_LEN = ${bytes.size};\n"
        }

        val content = buildString {
            appendLine("// GENERATED -- do not edit by hand, never committed (see .gitignore).")
            appendLine("// Regenerated by the generateNativeSecretsHeader Gradle task before every native build.")
            appendLine("// Every array here is XOR-masked against native_credentials.cpp's kObfuscationMask --")
            appendLine("// none of these bytes are the real value until de-obfuscated at resolve time.")
            appendLine("#pragma once")
            append(field("GEN_BLOB_KEY", readEnv("CREDENTIALS_BLOB_KEY")))
            append(field("GEN_NATIVE_3DES", nativeHalf(readEnv("IPTV_3DES_KEY"))))
            append(field("GEN_NATIVE_HOSTS", nativeHalf(readEnv("IPTV_HOSTS"))))
            append(field("GEN_NATIVE_APP_ID", nativeHalf(readEnv("IPTV_APP_ID"))))
            append(field("GEN_NATIVE_APK_VERSION", nativeHalf(readEnv("IPTV_APK_VERSION"))))
            append(field("GEN_NATIVE_TMDB", nativeHalf(readEnv("API_KEY"))))
        }
        file("src/main/cpp/generated_secrets.h").writeText(content)
    }
}

tasks.matching { it.name.contains("CMake") }.configureEach {
    dependsOn(generateNativeSecretsHeader)
}

ksp {
    // Room generates Kotlin instead of Java: sidesteps the javac bug on JDK 17.0.13+/21.0.5+
    // ("insert(Iterable) and insert(T) inherited with the same signature") that breaks compilation
    // of the generated code in the unit test variant.
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    // System splash: avoids the black frame between launch and Compose's first frame.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Forced past the version material3 pulls in transitively (1.0.1, whose native library
    // isn't 16 KB-aligned): a direct declaration wins over a transitive one at the same Gradle
    // conflict-resolution level, with no need to bump the whole Compose BOM for it.
    implementation("androidx.graphics:graphics-path:1.1.0")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Compose for TV (Android TV / Fire TV)
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    // HLS: needed by LiveExoPlayer (Magis' live channel, via LiveHlsProxy) -- was missing and
    // caused a runtime ClassNotFoundException (DefaultMediaSourceFactory looks up
    // HlsMediaSource$Factory by reflection, the compiler can't detect it).
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    // Transmux MPEG-TS -> MP4 for cast. Transformer copies the compressed samples when the format
    // already fits (no re-encode, so no quality loss and little CPU), which is what turns a
    // container the Cast receiver refuses into one it indexes properly. Chosen over ffmpeg-kit,
    // which was retired in January 2025 and would have re-added a large native blob right after
    // libVLC was removed from this branch.
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-muxer:1.5.1")

    // Chromecast
    implementation("androidx.media3:media3-cast:1.5.1")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Networking (JSON parsed with bundled org.json)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests: Android's own (android.jar) is a stub that throws at
    // runtime, so any test that parses JSON would fail without this.
    testImplementation("org.json:json:20240303")
    // Dispatchers.setMain + runTest/StandardTestDispatcher: without this, any real ViewModel
    // (viewModelScope = Dispatchers.Main.immediate) blows up in a pure JVM test ("Module with the
    // Main dispatcher had failed to initialize"). Testing-only: doesn't ship in the APK, same deal
    // as mockwebserver a bit further down.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Fake HTTP server for HttpFetcher tests (cached cookie, challenge/retry) with no real network.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real SQLite to test the DDL Room doesn't validate (the `updatedAt` triggers): they're plain
    // SQL, so running them is the only honest way to know whether they seal what they must seal.
    testImplementation("org.xerial:sqlite-jdbc:3.45.3.0")
}
