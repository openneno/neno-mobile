// AGP 8.13 自带的 R8 不认识 Kotlin 2.4 的元数据, 使用更新的 R8
buildscript {
    dependencies {
        classpath("com.android.tools:r8:9.5.29")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
