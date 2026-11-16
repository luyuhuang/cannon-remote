import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 签名信息放在根目录 keystore.properties（不入库）；
// 文件不存在时 release 包退化为未签名。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// —— 版本号自动从 git 标签推导，无需手动维护 ——
//   versionName = 标签名去掉 v 前缀（HEAD 不在标签上时自动附加 -N-g<hash> 后缀）
//   versionCode = 主*1000000 + 次*1000 + 修订（随语义化版本严格递增，跨环境稳定）
// CI 的 tag push 构建直接读 GITHUB_REF_NAME；本地用 git describe；都没有时回退 1.0.0。
val gitDescribe: String? = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--match=v*")
        isIgnoreExitValue = true
    }.standardOutput.asText.orNull?.trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

val versionTag: String =
    if (System.getenv("GITHUB_ACTIONS") == "true" &&
        System.getenv("GITHUB_REF_NAME")?.startsWith("v") == true
    ) {
        System.getenv("GITHUB_REF_NAME")!!
    } else {
        gitDescribe ?: "v1.0.0"
    }
val derivedVersionName: String = versionTag.removePrefix("v")
val versionBase: List<Int> =
    derivedVersionName.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
val derivedVersionCode: Int = (versionBase.getOrNull(0) ?: 0) * 1000000 +
    (versionBase.getOrNull(1) ?: 0) * 1000 +
    (versionBase.getOrNull(2) ?: 0)

android {
    namespace = "com.cannonremote.app"
    compileSdk = 35

    println("CanonRemote 版本：tag=$versionTag versionName=$derivedVersionName versionCode=$derivedVersionCode")

    defaultConfig {
        applicationId = "com.cannonremote.app"
        minSdk = 26
        targetSdk = 35
        versionCode = derivedVersionCode
        versionName = derivedVersionName
    }

    buildToolsVersion = "35.0.0"

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
