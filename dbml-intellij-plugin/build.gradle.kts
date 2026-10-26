import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.io.File

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        // Inherit IDE interface defaults instead of generating legacy DefaultImpls forwarders to internal APIs.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
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
    // Standalone packaged-ZIP tests do not get the IDE bootstrap classloader.
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")

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
            untilBuild = "262.*"
        }
        vendor {
            name = "DBML Diagram Contributors"
        }
        description = file("marketplace/description.html").readText()
    }
    pluginVerification {
        ides {
            val localIdes = providers.gradleProperty("verifyLocalIde").orNull
            if (localIdes != null) {
                // Explicit local verification must not download the remote matrix first.
                localIdes.split(File.pathSeparator).filter { it.isNotBlank() }.forEach { path -> local(file(path)) }
            } else {
                create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
                create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")
                create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
            }
        }
    }
}

tasks {
    patchPluginXml {
        changeNotes = """
            <h3>2.1.0 — Free SVG canvas</h3>
            <ul>
              <li>Move tables and relationship controls freely in all directions, including negative world coordinates.</li>
              <li>Pan over objects with Space + drag or the middle mouse button; scroll to pan and Ctrl/Cmd + scroll to zoom around the cursor.</li>
              <li>Drag a TableGroup header to move its member tables together, preserving internal route controls in one layout update.</li>
              <li>Toggle the world grid and optional 24-unit snapping; retain camera, grid, snap, table positions, and routes per file.</li>
              <li>Fit and SVG/PNG export use actual content bounds, without clipping negative positions or exporting the empty space back to zero.</li>
              <li>Batch live obstacle rerouting per animation frame and reuse obstacle geometry within each routing pass.</li>
            </ul>
            <h3>2.0.0 — Colored TableGroup frames</h3>
            <ul>
              <li>Render named TableGroup frames with custom colors or a stable default palette.</li>
              <li>Arrange groups as separate auto-layout blocks and resize frames live when member tables move.</li>
              <li>Keep labels, colors, and group bounds in standalone SVG and PNG exports, including restored table positions.</li>
              <li>Support group notes, quoted/schema-qualified members, aliases, and source-located membership warnings.</li>
              <li>Include a public, synthetic showcase for documenting and demonstrating the feature; preserve offline editing, CHECK constraints, composite keys, and optional dbdiagram.io sync.</li>
            </ul>
            <h3>1.0.3 — Schema processing improvements and CHECK constraints</h3>
            <ul>
              <li>Support named CHECK constraint blocks and inline checks; preserve expressions in SQL exports.</li>
              <li>Handle composite foreign keys across parsing, validation, diagram previews, and DDL export.</li>
              <li>Show CHECK counts in table headers and mark every column of a composite FK.</li>
              <li>Improve handling of complex schemas while retaining the parser memory-safety fixes.</li>
            </ul>
            <h3>1.0.2</h3>
            <ul>
              <li>Fix Java heap exhaustion when parsing TableGroup, sticky Note, or unexpected closing braces.</li>
              <li>Safely skip unsupported visualization blocks without losing subsequent tables and references.</li>
              <li>Handle multiline notes and quoted punctuation correctly, preserving diagnostic locations.</li>
            </ul>
            <h3>1.0.1</h3>
            <ul>
              <li>Support IntelliJ Platform build 262 while retaining compatibility with 241 and later.</li>
              <li>Update Apache Batik and the Gradle/Kotlin build toolchain.</li>
              <li>Reduce obsolete preview work and harden PNG resource handling.</li>
            </ul>
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
    systemProperty("dbml.groups.example", rootProject.file("examples/table-groups.dbml").absolutePath)
}

tasks.register<Test>("testPackagedPlugin") {
    description = "Rasterize PNG through an isolated classloader using libraries from the distributable ZIP."
    dependsOn(tasks.buildPlugin, tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*PackagedPluginPngTest") }
    doFirst { systemProperty("dbml.plugin.zip", tasks.buildPlugin.get().archiveFile.get().asFile.absolutePath) }
}
