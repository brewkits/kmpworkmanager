import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

dependencies {
    // KSP API
    implementation(libs.symbol.processing.api)

    // KotlinPoet for code generation
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.jetbrains.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlin.compile.testing)
    testImplementation(libs.kotlin.compile.testing.ksp)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}
