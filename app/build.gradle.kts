import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 从 local.properties 读取第三方平台签名密钥（该文件不进版本库）
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}

val igpHmacKey = localProps.getProperty("igp.hmac.key") ?: "REPLACE_ME_IGP_HMAC_KEY"
val onelapSignKey = localProps.getProperty("onelap.sign.key") ?: "REPLACE_ME_ONELAP_SIGN_KEY"

// 生成 Kotlin 源文件而不是用 BuildConfig ——
// BuildConfig 是 Java 产物，Kotlin 编译器在当前 AGP/KGP 组合下看不到它。
val generatedKeysDir = layout.buildDirectory.dir("generated/source/keys")
val generateKeysTask = tasks.register("generatePlatformKeys") {
    val outDir = generatedKeysDir
    val igp = igpHmacKey
    val onelap = onelapSignKey
    inputs.property("igp", igp)
    inputs.property("onelap", onelap)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/wulibaibao/cyclicsync/platform")
        dir.mkdirs()
        dir.resolve("PlatformKeysGenerated.kt").writeText(
            """
            package com.wulibaibao.cyclicsync.platform

            // 由 Gradle 从 local.properties 生成 —— 不要手改，也不要提交
            internal object PlatformKeysGenerated {
                const val IGP_HMAC_KEY = "${igp.replace("\\", "\\\\").replace("\"", "\\\"")}"
                const val ONELAP_SIGN_KEY = "${onelap.replace("\\", "\\\\").replace("\"", "\\\"")}"
            }
            """.trimIndent()
        )
    }
}

android {
    namespace = "com.wulibaibao.cyclingsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wulibaibao.cyclingsync"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("zh", "en")

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir(generatedKeysDir)
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 生成密钥源文件后再编译
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateKeysTask)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
}
