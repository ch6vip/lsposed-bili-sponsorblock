import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// 发布签名材料
//
// 密钥**不入库**，两个来源（按优先级）：
//   1) 仓库根目录的 keystore.properties —— 本机构建用，已 gitignore
//   2) 环境变量 —— CI 用，由 GitHub Secrets 注入
//        KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
// 两者都取不到时不报错，只是产出的 release 包未签名 ——
// 这样任何人都能直接跑 assembleRelease（只是不能分发）。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun signingSecret(key: String, vararg envNames: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: envNames.firstNotNullOfOrNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }

val releaseStorePath = signingSecret("storeFile", "KEYSTORE_FILE")
val releaseStorePassword = signingSecret("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signingSecret("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signingSecret("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning = releaseStorePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.ctf.bilisb"
    compileSdk = 35

    defaultConfig {
        // 反域名必须是「自己拥有的域名」，否则 modules.lsposed.org 不予收录。
        // 没有自己的域名时按官方要求用 io.github.<用户名> 前缀。
        // 注意：namespace 与 Kotlin 包名仍是 com.ctf.bilisb（那是类名，不是应用身份），
        // 只有 applicationId 决定安装身份 / 数据目录 / 模块在仓库里的条目名。
        applicationId = "io.github.ch6vip.bilisb"
        minSdk = 23
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk 23 的设备只认 V1 签名，必须显式打开
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 不启用 R8：模块靠反射与动态代理对接宿主的混淆类名，
            // 混淆自己收益极低、踩坑成本很高，这里刻意保持可读。
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
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
    compileOnly("io.github.libxposed:api:101.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Robolectric：让「需要真 android.* 类型」的单测真正执行（此前被 Assume 跳过）：
    //   - Bundle 往返（SettingsCodec 的 snapshotToBundle/FromBundle）
    //   - 面板本体（SponsorBlockPlayerSheet 的 Dialog/View 行为）
    // SDK 版本按 deviceProfile 需要下载对应 android-all jar（首次跑测试走网络）。
    testImplementation("org.robolectric:robolectric:4.14.1")
}
