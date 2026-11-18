plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.zhouzhang"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.zhouzhang"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // sqlcipher-android 会带进 kotlin-stdlib 2.1.x，而本项目用 Kotlin 1.9.24，
    // 编译器读不了 2.1 的元数据。锁回同一版本，避免传递依赖把编译器顶掉。
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.24")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
            // 2.4.0 用 Kotlin 2.1 编译，1.9 编译器读不了
            force("androidx.sqlite:sqlite:2.3.0")
            force("androidx.sqlite:sqlite-framework:2.3.0")
        }
    }

    testOptions {
        unitTests.all {
            // 本机验证加密账单用，密码不写进源码
            it.systemProperty("zz.zip.password", (project.findProperty("zipPassword") as String?) ?: "")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 唯一一个"不得不加"的第三方库：Java 标准库解不了加密 zip
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    // 数据库加密（AES-256）。密钥由 Android Keystore 保护
    // 4.18+ 要求 compileSdk 36/37，本项目用 34，故锁在 4.17.0
    implementation("net.zetetic:sqlcipher-android:4.17.0")
    // SQLCipher 的 SQLiteDatabase 实现自 SupportSQLiteDatabase，编译期需要它
    implementation("androidx.sqlite:sqlite:2.3.0")

    // 应用锁：BiometricPrompt 要求宿主是 FragmentActivity
    implementation("androidx.fragment:fragment:1.6.2")
    implementation("androidx.biometric:biometric:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("xmlpull:xmlpull:1.1.3.1")
}
