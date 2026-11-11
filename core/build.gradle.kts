import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.vitalink.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        val props = Properties()
        val file = rootProject.file("supabase.properties")
        if (file.exists()) file.inputStream().use { props.load(it) }
        fun field(name: String): String = "\"" + props.getProperty(name).orEmpty().replace("\"", "\\\"") + "\""
        buildConfigField("String", "SUPABASE_URL", field("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_KEY", field("SUPABASE_KEY"))
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    api("io.github.jan-tennert.supabase:postgrest-kt")
    api("io.github.jan-tennert.supabase:realtime-kt")
    api("io.github.jan-tennert.supabase:auth-kt")
    api("io.github.jan-tennert.supabase:functions-kt")
    api("io.ktor:ktor-client-okhttp:3.1.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
