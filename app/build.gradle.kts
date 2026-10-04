import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Credenciales del keystore de release, en keystore.properties (NO versionado, ver
// .gitignore). Si el archivo no existe o le faltan valores, el release sigue firmando
// con el keystore de depuración (smoke-tests locales); para publicar en Play hace falta
// rellenarlo con el keystore de producción.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "chat.neto.krypta"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    // Fija la misma versión de NDK con la que build-aar.sh compila libgojni.so.
    // Sin esto, AGP 9.2.1 usa su NDK por defecto (28.2.13676358), que no está
    // instalado aquí, y stripReleaseDebugSymbols/extractReleaseNativeDebugMetadata
    // fallan en silencio para algunas ABIs.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "chat.neto.krypta"
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 activo: -34% de APK y quita código muerto. Las clases del puente Go
            // (JNI por nombre) se preservan en proguard-rules.pro.
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Con keystore.properties relleno, firma con el keystore de producción
            // (necesario para subir a Play). Si no, cae al keystore de depuración, que
            // solo sirve para smoke-tests locales.
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
            // libgojni.so (AAR de gomobile en :native-bridge) no pasa por el build nativo
            // de AGP, así que sin esto Play no recibe símbolos para depurar fallos
            // nativos. El .so embebido en el APK/AAB sigue yendo stripped igualmente;
            // esto solo añade el paquete de símbolos aparte que consume Play Console.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // APK reducido por ABI: `./gradlew :app:assembleDebug -PslimAbi` genera solo arm64-v8a
    // (~60 MB en vez de ~193 MB con las 4 ABIs), fácil de compartir para pruebas en 2 móviles.
    splits {
        abi {
            isEnable = project.hasProperty("slimAbi")
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":p2p-signaling"))
    implementation(project(":native-bridge"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // QR para verificación de identidad (generar + escanear)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
