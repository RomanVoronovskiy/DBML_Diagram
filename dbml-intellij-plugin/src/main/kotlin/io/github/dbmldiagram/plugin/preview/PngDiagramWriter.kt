package io.github.dbmldiagram.plugin.preview

import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.batik.bridge.UserAgent
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.ceil

/** Rasterization without IDE or file chooser dependencies, also used by export regression tests. */
internal object PngDiagramWriter {
    private const val MAX_PIXELS = 40_000_000L
    private const val MAX_DIMENSION = 16_384

    fun dimensions(svg: String): Pair<Int, Int> {
        val root = Regex("<svg\\b[^>]*>").find(svg)?.value ?: error("Missing SVG root")
        fun dimension(name: String): Int {
            val value = Regex("\\b$name\\s*=\\s*[\"']([0-9]+(?:\\.[0-9]+)?)(?:px)?[\"']")
                .find(root)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: error("Missing or invalid SVG $name")
            require(value > 0 && value <= MAX_DIMENSION) { "Diagram $name must be between 1 and $MAX_DIMENSION pixels" }
            return ceil(value).toInt()
        }
        val width = dimension("width")
        val height = dimension("height")
        require(width.toLong() * height <= MAX_PIXELS) { "Diagram is too large to export safely (${width}×${height}); maximum is $MAX_PIXELS pixels" }
        return width to height
    }

    fun encode(svg: String): ByteArray = ByteArrayOutputStream().use { output ->
        val (width, height) = dimensions(svg)
        // Batik implements SVG 1.1 CSS and silently treats var(...) paints as black.
        // Resolve only generated stylesheet declarations, leaving schema text untouched.
        val compatibleSvg = Regex("<style>([\\s\\S]*?)</style>").replace(svg) { style ->
            val css = Regex("var\\(\\s*--[\\w-]+\\s*,\\s*([^()]+)\\)")
                .replace(style.groupValues[1]) { it.groupValues[1].trim() }
                .replace("stroke:transparent", "stroke:none")
                .replace(Regex("cursor:[^;}]+;?"), "")
            val compatibleCss = Regex("\\.cardinality\\{([^}]+)}").replace(css) { rule ->
                ".cardinality{" + rule.groupValues[1]
                    .replace(Regex("(?:^|;)(?:stroke|stroke-width|paint-order):[^;]+"), "") + "}"
            }
            "<style>$compatibleCss</style>"
        }
        val transcoder = object : PNGTranscoder() {
            override fun createUserAgent(): UserAgent = object : SVGAbstractTranscoderUserAgent() {
                override fun displayError(error: Exception) {
                    throw IllegalStateException("SVG rasterization failed: ${error.message}", error)
                }
                override fun displayError(message: String) {
                    throw IllegalStateException("SVG rasterization failed: $message")
                }
            }
        }
        transcoder.apply {
            addTranscodingHint(PNGTranscoder.KEY_WIDTH, width.toFloat())
            addTranscodingHint(PNGTranscoder.KEY_HEIGHT, height.toFloat())
            addTranscodingHint(PNGTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
        }.transcode(TranscoderInput(StringReader(compatibleSvg)).apply { uri = "file:///dbml-diagram.svg" }, TranscoderOutput(output))
        output.toByteArray()
    }

    /** Do not truncate an existing image if rasterization or writing fails. */
    fun write(svg: String, target: Path) {
        val bytes = encode(svg)
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, ".dbml-export-", ".png")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
