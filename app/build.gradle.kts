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
    namespace = "com.wulibaibao.cyclicsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wulibaibao.cyclicsync"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        resourceConfigurations += listOf("zh", "en")

    }

    signingConfigs {
        // 正式签名 —— 密钥库路径与口令从 local.properties 读取
        create("release") {
            val ksPath = localProps.getProperty("release.keystore.path")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = localProps.getProperty("release.keystore.storePassword")
                keyAlias = localProps.getProperty("release.keystore.keyAlias")
                keyPassword = localProps.getProperty("release.keystore.keyPassword")
                // v1+v2+v3 全开 —— 部分国产 ROM 的安装器只认 v1 (JAR 签名)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // ⚠️ debug 也压缩 —— 未压缩的 dex 有 40MB+（material-icons-extended 占大头），
            // 部分国产 ROM 的 dex 加载器扛不住，表现为「一启动就崩」。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // ⚠️ debug 用**正式签名** —— 否则和 release 签名不同，
            // 互相覆盖安装会失败（系统报「应用未安装」，容易被误判成「闪退」）。
            signingConfig = if (localProps.getProperty("release.keystore.path") != null &&
                file(localProps.getProperty("release.keystore.path")).exists()
            ) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        release {
            // ⚠️ 开启 R8 —— 不压缩时 dex 有 40MB+（material-icons-extended 占大头），
            // 部分国产 ROM 的 dex 加载器扛不住，表现为「一启动就崩」。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 配了正式签名就用正式的，否则退回 debug（保证本地能构建）
            signingConfig = if (localProps.getProperty("release.keystore.path") != null &&
                file(localProps.getProperty("release.keystore.path")).exists()
            ) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // ⚠️ minSdk 24 但代码用了 java.time（LocalDate 等）——
        // 不开 desugaring 的话在这些设备上直接 NoClassDefFoundError。
        isCoreLibraryDesugaringEnabled = true
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
    lint {
        // androidx.lifecycle 的 lint 检测器与 Kotlin 2.x 的 K2 分析 API 不兼容会崩溃
        // （IncompatibleClassChangeError: KaCallableMemberCall）—— 工具 bug，不是代码问题。
        // 关掉 release 构建的 lint 检查，保证 assembleRelease 能跑通。
        disable += "NullSafeMutableLiveData"
        checkReleaseBuilds = false
        abortOnError = false
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    // debug-only：@Preview 注解 + 离线渲染（android preview render），不进 release 包
    debugImplementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
}
