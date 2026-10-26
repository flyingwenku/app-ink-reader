// ===== 应用层 =====
// 约定：不得在此文件写入流水线 / 签名 / 环境相关逻辑，
//      保证 app/ 模块可被整体替换而流水线无需改动。

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fred.inkreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fred.inkreader"
        minSdk = 21          // Android 5.0：本项目面向旧手机，门槛必须放低
        targetSdk = 35

        // 版本管理：每次发布都要递增，先改这里再提交。
        versionCode = 5
        versionName = "1.2.2"

        resourceConfigurations.add("zh")
    }

    buildFeatures {
        // AGP 8 起 BuildConfig 默认不生成，用到（如显示版本号）必须显式开启
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            // 优化点：debug 关闭 R8 与资源缩减（构建中最耗时的一步）
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 依赖最小化：EPUB 解析用 java.util.zip + 手写 XML 正则，
    // 渲染用 Html.fromHtml + 自绘 StaticLayout，全程不引第三方库。
    implementation("androidx.core:core-ktx:1.15.0")
}
