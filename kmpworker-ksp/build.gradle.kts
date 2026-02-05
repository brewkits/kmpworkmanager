plugins {
    kotlin("jvm")
}

dependencies {
    // KSP API
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.20-1.0.14")

    // KotlinPoet for code generation
    implementation("com.squareup:kotlinpoet:1.14.2")
    implementation("com.squareup:kotlinpoet-ksp:1.14.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}
