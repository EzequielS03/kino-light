plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/** Lee una clave del archivo .env de la raíz del repo (para no hardcodear credenciales). */
fun readEnv(key: String, default: String = ""): String {
    val f = rootProject.file(".env")
    if (!f.exists()) return default
    return f.readLines()
        .firstOrNull { it.trim().startsWith("$key=") }
        ?.substringAfter("=")?.trim()?.trim('"')?.trim('\'') ?: default
}

android {
    namespace = "com.arkiv.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arkiv.player"
        minSdk = 26
        targetSdk = 35
        // Codigo del candado de la seccion 18+. Sale del .env (gitignoreado) igual que la llave
        // de firma: no entra al repo. Si falta, queda vacio -- y `CandadoDeAdultos` NO abre con
        // codigo vacio, asi que un build sin .env simplemente no ofrece la seccion en vez de
        // dejarla abierta.
        buildConfigField("String", "ADULT_CODE", "\"${readEnv("ARKIV_ADULT_CODE")}\"")
        versionCode = 42
        versionName = "0.9.11"
        // Task 8 (Paso 3): acá vivía `ARKIV_API_KEY`, la última credencial de build que quedaba
        // en el APK -- una constante compilada, igual para todos los aparatos, que cualquiera que
        // abriera el APK podía extraer. Salió del todo: la app se autentica con la credencial POR
        // DISPOSITIVO que ya emitía el alta (sesión de persona + aparato), revocable de a una.
        // Ver `docs/INVENTARIO_DE_LLAVES.md`.
        ndk {
            // Solo ABIs de dispositivos reales (celular arm64, Fire Stick armeabi-v7a).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Firma de release desde .env (mismo mecanismo que las credenciales: el archivo está gitignoreado,
    // así que la llave y su clave nunca entran al repo). Si no está configurada, el bloque no se crea
    // y `assembleRelease` sale sin firmar — es a propósito: mejor que fallar en silencio firmando con debug.
    val keystorePath = readEnv("RELEASE_KEYSTORE_PATH")
    val hayFirma = keystorePath.isNotBlank() && file(keystorePath).exists()
    if (hayFirma) {
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
            // R8 apagado a propósito: libtorrent4j y libVLC llaman por JNI a clases/campos que el
            // shrinker no ve referenciados y borraría. El APK pesa más, pero funciona.
            isMinifyEnabled = false
            if (hayFirma) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
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

ksp {
    // Room genera Kotlin en vez de Java: esquiva el bug de javac en JDK 17.0.13+/21.0.5+
    // ("insert(Iterable) and insert(T) inherited with the same signature") que rompe la
    // compilación del código generado en el variant de unit test.
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    // Splash del sistema: evita el frame negro entre el lanzamiento y el primer frame de Compose.
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
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Compose for TV (Android TV / Fire TV)
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-database:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

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
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // QR: generar (TV) y escanear (celu)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Reproductor VLC (libVLC): decodifica por software lo que ExoPlayer no maneja
    // (.avi/XviD, Dolby Vision P7, TrueHD/DTS-HD). Trae libs nativas arm64 + armeabi-v7a.
    implementation("org.videolan.android:libvlc-all:3.6.0")

    // Torrents (libtorrent nativo) — arm64 (celular) + arm (Fire Stick)
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-31")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Parsing HTML declarativo para proveedores de torrents on-device (capa estilo Burst).
    implementation("org.jsoup:jsoup:1.17.2")

    testImplementation("junit:junit:4.13.2")
    // org.json real para unit tests JVM: el de Android (android.jar) es un stub que lanza en runtime,
    // así que cualquier test que parsee JSON fallaría sin esto.
    testImplementation("org.json:json:20240303")
    // Dispatchers.setMain + runTest/StandardTestDispatcher: sin esto, cualquier ViewModel real
    // (viewModelScope = Dispatchers.Main.immediate) revienta en un test JVM puro ("Module with the
    // Main dispatcher had failed to initialize"). Testing-only: no viaja en el APK, mismo trato que
    // mockwebserver un poco más abajo.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Servidor HTTP falso para tests de HttpFetcher (cookie cacheada, challenge/reintento) sin red real.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // SQLite de verdad para probar el DDL que Room no valida (los triggers de `updatedAt`): son SQL
    // puro, así que ejecutarlos es la única forma honesta de saber si sellan lo que tienen que sellar.
    testImplementation("org.xerial:sqlite-jdbc:3.45.3.0")
}
