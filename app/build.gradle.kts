// 注意：本模块通过 buildscript classpath + apply(plugin=...) 应用 AGP，
// 所以这里必须用 configure<ApplicationExtension> 强类型配置，
// 拿不到 Kotlin DSL 的类型安全访问器（那个只有 plugins {} 块才会生成）。
apply(plugin = "com.android.application")

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.ltpo120hz"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ltpo120hz"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
        viewBinding = false
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
