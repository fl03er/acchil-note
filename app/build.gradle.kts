import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 서명 키는 저장소에 넣지 않는다. -PkeystoreProps=<keystore.properties 경로> 로 넘기면 그 키로,
// 없으면 디버그 키로 서명해 누구나 release APK를 빌드할 수 있게 한다.
val keystoreProps = (findProperty("keystoreProps") as String?)?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}

android {
    namespace = "ai.acchil.note"
    compileSdk = 35
    // 표준 AAOS Car API(차속 PERF_VEHICLE_SPEED)를 같이 확인하기 위해 사용
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.acchil.note"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
    }

    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation("ai.pleos.playground:Vehicle:2.3.2")
}
