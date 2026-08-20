plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Module Kotlin pur : aucune dépendance à Android, donc testable en quelques
 * secondes sur la JVM. Toute la géométrie, le routage et l'écriture GPX vivent ici.
 */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
