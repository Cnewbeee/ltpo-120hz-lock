// 顶层构建脚本。
// 这里用 buildscript + classpath 的写法（而不是 plugins {} 版本目录），
// 好处是离线构建时只需要本地 Gradle 缓存里已有的 AGP 构件，不必再去解析插件标记。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.3.0")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
