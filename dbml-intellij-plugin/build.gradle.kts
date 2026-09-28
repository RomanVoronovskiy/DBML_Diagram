plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":dbml-core"))
    implementation("org.apache.xmlgraphics:batik-transcoder:1.17")
    implementation("org.apache.xmlgraphics:batik-codec:1.17")
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdeaCommunity("2024.1.7")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        pluginVerifier()
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        name = "DBML Diagram"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "261.*"
        }
        vendor {
            name = "DBML Diagram Contributors"
        }
        description = "Offline DBML editor preview with realtime SVG entity-relationship diagrams."
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    patchPluginXml {
        changeNotes = "Initial local MVP."
    }
}
