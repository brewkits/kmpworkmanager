import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

plugins {
    kotlin("jvm")
    id("maven-publish")
    id("signing")
}

group = "dev.brewkits"
version = (rootProject.findProperty("VERSION_NAME") as? String) ?: System.getenv("VERSION_NAME") ?: "0.0.0-SNAPSHOT"

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
    
    // Support Maven Central requirements
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            
            pom {
                name.set("KMP WorkManager KSP")
                description.set("KSP processor for KMP WorkManager to auto-generate worker factories.")
                url.set("https://github.com/brewkits/kmpworkmanager")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("brewkits")
                        name.set("Brewkits Team")
                        email.set("vietnguyentuan@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/brewkits/kmpworkmanager.git")
                    developerConnection.set("scm:git:ssh://github.com/brewkits/kmpworkmanager.git")
                    url.set("https://github.com/brewkits/kmpworkmanager")
                }
            }
        }
    }
    
    repositories {
        maven {
            name = "MavenCentralLocal"
            url = uri(rootProject.layout.buildDirectory.dir("maven-central-staging"))
        }
    }
}

signing {
    val signingKeyBase64 = project.findProperty("signing.key") as String?
    val signingPassword = project.findProperty("signing.password") as String? ?: ""
    isRequired = signingKeyBase64 != null
    if (signingKeyBase64 != null) {
        val signingKey = String(Base64.getDecoder().decode(signingKeyBase64))
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}
