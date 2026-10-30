plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val configuredApiBase = providers.gradleProperty("apiBase").orNull
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val debugApiBase = configuredApiBase ?: "http://10.0.2.2:8080/api/v1"
// release 固定 https 占位，避免默认 http 在 ApiConfig 初始化时崩溃；显式 https 覆盖仍生效。
val releaseApiBase = configuredApiBase
    ?.takeIf { it.startsWith("https://") }
    ?: "https://api.whaletodo.top:8443/api/v1"
android {
    namespace = "io.whaletodo.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.whaletodo.app"
        manifestPlaceholders["appLabel"] = "WhaleToDo"
        manifestPlaceholders["usesCleartextTraffic"] = "true"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.5.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", buildConfigString(debugApiBase))
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            buildConfigField("String", "API_BASE_URL", buildConfigString(releaseApiBase))
        }
        create("publicPreview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview.1"
            manifestPlaceholders["appLabel"] = "WhaleToDo 预览"
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            buildConfigField("String", "API_BASE_URL", buildConfigString(releaseApiBase))
            matchingFallbacks += listOf("release")
        }
        create("verification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".verification"
            manifestPlaceholders["appLabel"] = "WhaleToDo 验证"
            matchingFallbacks += listOf("debug")
        }
        create("paymentDemo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".paymentdemo"
            manifestPlaceholders["appLabel"] = "WhaleToDo 模拟支付"
            buildConfigField("String", "API_BASE_URL", buildConfigString(configuredApiBase ?: "http://10.0.2.2:18080/api/v1"))
            matchingFallbacks += listOf("debug")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").orNull?.also { require(it in listOf("verification", "paymentDemo")) } ?: "verification"
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation(project(":network"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    testImplementation("junit:junit:4.13.2")
    // Room's migration test bundles need matching core/json on the target and test classpaths.
    add("verificationImplementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    androidTestImplementation("androidx.room:room-testing:2.8.3")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// MigrationTestHelper must package the schema exported by this same variant, not a stale file.
tasks.matching { it.name == "mergeVerificationAndroidTestAssets" }.configureEach {
    dependsOn("kspVerificationKotlin")
}
