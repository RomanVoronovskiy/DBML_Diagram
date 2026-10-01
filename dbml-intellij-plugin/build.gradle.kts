import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

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
        description = "Offline DBML editor with realtime SVG entity-relationship diagrams and PostgreSQL DDL export."
    }
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")
        }
    }
}

tasks {
    patchPluginXml {
        changeNotes = """
            <h3>0.2.0</h3>
            <ul>
              <li>Generate PostgreSQL DDL from the current DBML schema.</li>
              <li>Show PK, FK, UNIQ, and NOT_NULL constraints directly on diagram columns.</li>
              <li>Preserve reference actions, composite primary keys, and SQL defaults.</li>
            </ul>
        """.trimIndent()
    }
}
