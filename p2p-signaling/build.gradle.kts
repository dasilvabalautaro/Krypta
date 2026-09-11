import org.gradle.kotlin.dsl.support.serviceOf

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "chat.neto.krypta.p2p"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

/**
 * Los tests de este módulo corren en un **JDK 25**, no en el que Gradle elija por su cuenta.
 *
 * `JdkKem` prueba la parte post-cuántica con el ML-KEM-768 nativo del JDK (FIPS 203), que no
 * existe antes de la 24. Y sin esto Gradle escoge por autodetección un Temurin 21 para los tests
 * unitarios —aunque su propio lanzador sea un 25—, así que todo `KemTest` fallaba con
 * `NoSuchAlgorithmException: ML-KEM-768 KeyPairGenerator not available`, un error que parece
 * «este JDK no lo trae» cuando lo que pasa es que **no es el JDK que uno cree**.
 *
 * Solo afecta a la JVM que ejecuta los tests: la compilación sigue siendo compatible con Java 11,
 * como el resto de los módulos, y en el dispositivo ML-KEM no viene del JDK sino del puente Go
 * (Android no lo trae a ninguna API). Ver docs/DISENO-postcuantico.md §1.1.
 *
 * Detalle de implementación que costó un intento: el servicio se saca del **registro de
 * servicios** (`serviceOf`), no de las extensiones del proyecto. Un módulo de librería Android
 * bajo AGP 9 no aplica el plugin `java`, así que `extensions.getByType<JavaToolchainService>()`
 * falla con «Extension of type 'JavaToolchainService' does not exist. Currently registered
 * extension types: [ExtraPropertiesExtension]».
 */
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        serviceOf<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        },
    )
}

dependencies {
    implementation(project(":core"))
    implementation(project(":native-bridge"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
