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

configurations.configureEach {
    // The JDK/IDE owns JAXP and core DOM classes. Bundling their old copies causes
    // SAXParserFactory ClassCastException in IntelliJ's plugin classloader.
    // Keep xml-apis-ext: Batik still needs its SVG-specific DOM interfaces.
    exclude(group = "xml-apis", module = "xml-apis")
}

dependencies {
    implementation(project(":dbml-core"))
    implementation("org.apache.xmlgraphics:batik-transcoder:1.19")
    implementation("org.apache.xmlgraphics:batik-codec:1.19")
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
        description = file("marketplace/description.html").readText()
    }
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")
            providers.gradleProperty("verifyLocalIde").orNull?.let { local(file(it)) }
        }
    }
}

tasks {
    patchPluginXml {
        changeNotes = """
            <h3>1.0.0</h3>
            <ul>
              <li>Connect to dbdiagram.io via official CLI browser login or a securely stored workspace API token.</li>
              <li>Browse and search diagrams inside the IDE; download a diagram or link it to an existing DBML file.</li>
              <li>Pull and push DBML from the editor toolbar, with overwrite confirmation and remote-change checks.</li>
              <li>Keep website visualization settings intact and preserve local preview/export features offline.</li>
            </ul>
            <h3>0.3.0</h3>
            <ul>
              <li>Reroute relationship lines around tables during dragging and in SVG/PNG export.</li>
              <li>Choose attachment sides by dragging endpoints or clicking table-side anchors.</li>
              <li>Keep all export buttons visible by wrapping the toolbar in narrow preview panels.</li>
              <li>Validate references with editor and diagram errors; fix packaged PNG XML dependencies.</li>
            </ul>
            <h3>0.2.2</h3>
            <ul>
              <li>Anchor relationship lines to the actual referenced and foreign-key column rows, including after dragging tables.</li>
              <li>Fix PNG export in installed IDEs by removing conflicting bundled JAXP classes.</li>
            </ul>
            <h3>0.2.1</h3>
            <ul>
              <li>Validate relationship cardinality, unique keys, endpoints, and compatible column types.</li>
              <li>Underline invalid references in the editor and highlight them in red on the diagram.</li>
              <li>Block exports until errors in the current document are fixed.</li>
              <li>Fix PNG rasterization of theme colors and relationship hit areas; export in the background without corrupting existing files on failure.</li>
            </ul>
            <h3>0.2.0</h3>
            <ul>
              <li>Generate PostgreSQL, MySQL, or Oracle DDL from the current DBML schema.</li>
              <li>Show PK, FK, UNIQ, and NOT_NULL constraints between column names and types.</li>
              <li>Show directed FK-to-reference arrows with N:1 and 1:1 cardinality labels.</li>
              <li>Drag tables into a custom layout that is retained for SVG and PNG export.</li>
              <li>Route relationships horizontally, vertically, or freely and attach them to any of four table sides.</li>
              <li>Preserve reference actions, composite primary keys, and SQL defaults.</li>
            </ul>
        """.trimIndent()
    }
}

tasks.test {
    systemProperty("dbml.preview.fixture", layout.buildDirectory.file("reports/preview-fixture.html").get().asFile.absolutePath)
}

tasks.register<Test>("testPackagedPlugin") {
    description = "Rasterize PNG through an isolated classloader using libraries from the distributable ZIP."
    dependsOn(tasks.buildPlugin, tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*PackagedPluginPngTest") }
    doFirst { systemProperty("dbml.plugin.zip", tasks.buildPlugin.get().archiveFile.get().asFile.absolutePath) }
}
