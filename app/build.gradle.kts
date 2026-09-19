plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起，Compose 编译器由官方插件提供（无需 composeOptions/kotlinCompilerExtensionVersion）
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xixi.music"
    // 项目无 native 库，无需 NDK；buildTools 由 AGP 8.7.2 默认提供，避免 CI 版本不匹配
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xixi.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // 只保留中文资源，删除多余 values-xx（体积优化）
        resourceConfigurations += listOf("zh", "zh-rCN")
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all"
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "API_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 个人自用：release 直接复用 debug 签名，避免管理 keystore
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "API_LOGGING", "false")
        }
    }

    packaging {
        jniLibs {
            // 16KB 页大小兼容 + 不压缩 so（无 native 库，仅为符合 Android 15 规范）
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "kotlin/**",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "**/*.proto"
            )
        }
        dex {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    androidResources {
        // 不生成 LocaleConfig（语言过滤由上面的 resourceConfigurations 统一负责）
        generateLocaleConfig = false
    }

    bundle {
        language { enableSplit = false }
        density { enableSplit = false }
        abi { enableSplit = false }
    }
}

dependencies {
    // ---- 基础 ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // collectAsStateWithLifecycle：Compose 侧生命周期安全地收集 StateFlow
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ---- Compose（BOM 统一版本）----
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 仅 debug 引入 tooling，release 包不包含
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Media3 / ExoPlayer ----
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")

    // ---- 网络 ----
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    Implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ---- 协程 ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- 图片（封面）----
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ---- 本地存储（Cookie 明文）----
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---- 单元测试（不参与 APK 打包）----
    testImplementation("junit:junit:4.13.2")
}
