package io.github.dbmldiagram.plugin.preview

import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.xml.parsers.SAXParserFactory

/** Checks the shipped JARs, not Gradle's flattened classpath (which hides split JAXP classes). */
class PackagedPluginPngTest {
    @Test fun packagedLibrariesUseHostXmlClassesAndRasterizePng() {
        val path = System.getProperty("dbml.plugin.zip")
        assumeNotNull(path) // Run explicitly with :dbml-intellij-plugin:testPackagedPlugin.
        val directory = Files.createTempDirectory("dbml-packaged-png-")
        val jars = mutableListOf<java.nio.file.Path>()
        val cacheJarResources = URLConnection.getDefaultUseCaches("jar")
        // Batik reads resource bundles via jar URLs, whose global cache otherwise
        // retains open handles beyond URLClassLoader.close() on Windows.
        URLConnection.setDefaultUseCaches("jar", false)
        try {
            ZipFile(path).use { zip ->
                zip.entries().asSequence().filter { it.name.matches(Regex("[^/]+/lib/[^/]+\\.jar")) }.forEach { entry ->
                    val jar = directory.resolve(entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { Files.copy(it, jar) }
                    jars.add(jar)
                    ZipFile(jar.toFile()).use { library ->
                        assertNull("${jar.fileName} duplicates JDK JAXP classes", library.getEntry("javax/xml/parsers/SAXParserFactory.class"))
                        assertNull("${jar.fileName} duplicates JDK DOM classes", library.getEntry("org/w3c/dom/Node.class"))
                        assertNull("${jar.fileName} duplicates JDK SAX classes", library.getEntry("org/xml/sax/XMLReader.class"))
                    }
                }
            }
            assertTrue("Distribution must contain plugin libraries", jars.isNotEmpty())
            val host = javaClass.classLoader
            // Preload the host's XML provider before the child plugin resolves XML APIs.
            val hostFactory = SAXParserFactory.newInstance()
            PluginLibrariesLoader(jars.map { it.toUri().toURL() }.toTypedArray(), host).use { loader ->
                assertSame(SAXParserFactory::class.java, loader.loadClass("javax.xml.parsers.SAXParserFactory"))
                assertTrue(loader.loadClass("javax.xml.parsers.SAXParserFactory").isInstance(hostFactory))
                assertSame(loader, loader.loadClass("org.apache.batik.dom.util.SAXDocumentFactory").classLoader)
                val schema = TolerantDbmlParser().parse("""
                    Table users { id uuid [pk] }
                    Table orders {
                      id uuid [pk]
                      user_id uuid
                    }
                    Ref: orders.user_id > users.id
                """.trimIndent()).schema!!
                val layout = LayeredDiagramLayoutEngine().layout(schema)
                val svg = SvgDiagramRenderer().render(schema, layout)
                val writer = loader.loadClass("io.github.dbmldiagram.plugin.preview.PngDiagramWriter")
                assertSame(loader, writer.classLoader)
                val bytes = writer.getMethod("encode", String::class.java).invoke(writer.getField("INSTANCE").get(null), svg) as ByteArray
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                assertNotNull("The packaged plugin must produce a decodable PNG", image)
                assertEquals(layout.width.toInt(), image.width)
                assertEquals(layout.height.toInt(), image.height)
            }
        } finally {
            try {
                jars.forEach { Files.deleteIfExists(it) }
                Files.deleteIfExists(directory)
            } finally {
                URLConnection.setDefaultUseCaches("jar", cacheJarResources)
            }
        }
    }

    private class PluginLibrariesLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            // IntelliJ supplies Kotlin; plugin-specific libraries are otherwise child-first.
            val loaded = findLoadedClass(name) ?: if (name.startsWith("java.") || name.startsWith("kotlin.")) {
                super.loadClass(name, false)
            } else {
                try { findClass(name) } catch (_: ClassNotFoundException) { super.loadClass(name, false) }
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}
