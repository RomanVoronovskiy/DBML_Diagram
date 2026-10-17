plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // IDEA 2024.1 supplies Kotlin 1.9.22. Do not ship a second, newer stdlib.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}
