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
        versionCode = 13
        versionName = "1.3.3"
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

configurations.all {
    resolutionStrategy {
        // ⚠️ 锁死 Compose —— kizitonwose/Calendar 2.5.2 是按 Compose 1.6.1 编译的。
        //
        // Compose 1.7+ 把 `SnapPositionInLayout` 改名成 `SnapPosition`，
        // 而 kizitonwose 用的 `PagerSnapLayoutInfoProvider` 仍引用旧名。
        // 一旦被顶到 1.7+，R8 会把旧名当没引用的类裁掉，
        // 运行时 `NoClassDefFoundError` ⇒ App 一打开就崩。
        //
        // 这里对全部 Compose 模块强制 1.6.1，不给任何传递依赖顶上去的机会。
        force(
            "androidx.compose.ui:ui:1.6.1",
            "androidx.compose.ui:ui-graphics:1.6.1",
            "androidx.compose.ui:ui-text:1.6.1",
            "androidx.compose.ui:ui-unit:1.6.1",
            "androidx.compose.ui:ui-geometry:1.6.1",
            "androidx.compose.ui:ui-util:1.6.1",
            "androidx.compose.ui:ui-tooling:1.6.1",
            "androidx.compose.ui:ui-tooling-preview:1.6.1",
            "androidx.compose.foundation:foundation:1.6.1",
            "androidx.compose.foundation:foundation-layout:1.6.1",
            "androidx.compose.runtime:runtime:1.6.1",
            "androidx.compose.runtime:runtime-saveable:1.6.1",
            "androidx.compose.animation:animation:1.6.1",
            "androidx.compose.animation:animation-core:1.6.1",
            "androidx.compose.material:material:1.6.1",
            "androidx.compose.material:material-icons-core:1.6.1",
            "androidx.compose.material:material-icons-extended:1.6.1",
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.material3:material3:1.2.1")
    // 日期选择器 —— 可定制月历
    // 2.5.2 是最后一个用 kotlin-stdlib 1.9.x 的版本（2.6+ 要 Kotlin 2.0/2.1）
    implementation("com.kizitonwose.calendar:compose:2.5.2")

    // ⚠️⚠️ Compose 版本必须锁死，别让 Gradle 自由解析 ⚠️⚠️
    //
    // kizitonwose/Calendar 2.5.2 是针对 Compose **1.6.1** 编译的，它用了
    // `PagerSnapLayoutInfoProvider` / `LazyListSnapLayoutInfoProvider`，
    // 这两个类在 Compose 1.6 里引用 `SnapPositionInLayout`。
    //
    // Compose **1.7 起这个类被改名成 `SnapPosition`**，旧名消失。
    // 如果让 Gradle 把 foundation 顶到 1.7+（项目原本声明 ui 1.7.6，
    // 会被解析到 1.8.2），R8 收缩时会把 `SnapPositionInLayout`
    // 当成「没引用的类」裁掉，但 `SnapFlingBehavior` 还在引用它 ——
    // 运行时直接 `NoClassDefFoundError`，App 一打开就崩。
    //
    // 所以 foundation / ui / runtime 全部锁回 1.6.1。
    implementation("androidx.compose.ui:ui:1.6.1")
    implementation("androidx.compose.foundation:foundation:1.6.1")
    implementation("androidx.compose.runtime:runtime:1.6.1")
    implementation("androidx.compose.animation:animation:1.6.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.1")
    // debug-only：@Preview 注解 + 离线渲染（android preview render），不进 release 包
    debugImplementation("androidx.compose.ui:ui-tooling-preview:1.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
}
