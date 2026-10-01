package io.github.dbmldiagram.plugin.preview

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import java.io.StringReader
import java.nio.file.Files

object DiagramExporter {
    private const val MAX_PIXELS = 40_000_000L
    private const val MAX_DIMENSION = 16_384

    fun exportDdl(project: Project, source: VirtualFile, dialect: String, ddl: String) {
        val target = choose(project, source, "Export $dialect DDL", "sql") ?: return
        runCatching { Files.writeString(target, ddl) }.onFailure { showError(project, it) }
    }

    fun exportSvg(project: Project, source: VirtualFile, svg: String) {
        val target = choose(project, source, "Export DBML diagram as SVG", "svg") ?: return
        runCatching { Files.writeString(target, svg) }.onFailure { showError(project, it) }
    }

    fun exportPng(project: Project, source: VirtualFile, svg: String) {
        val dimensions = Regex("<svg[^>]*width=\"(\\d+)\"[^>]*height=\"(\\d+)\"").find(svg)?.destructured
        val width = dimensions?.component1()?.toIntOrNull() ?: 1024
        val height = dimensions?.component2()?.toIntOrNull() ?: 768
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || width.toLong() * height > MAX_PIXELS) {
            Messages.showErrorDialog(project, "Diagram is too large to export safely (${width}×${height}). Maximum is $MAX_DIMENSION per side and $MAX_PIXELS pixels.", "DBML Diagram")
            return
        }
        val target = choose(project, source, "Export DBML diagram as PNG", "png") ?: return
        runCatching {
            Files.newOutputStream(target).use { output ->
                PNGTranscoder().transcode(TranscoderInput(StringReader(svg)), TranscoderOutput(output))
            }
        }.onFailure { showError(project, it) }
    }

    private fun choose(project: Project, source: VirtualFile, title: String, extension: String) =
        FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor(title, "", extension), project)
            .save(source.parent, source.nameWithoutExtension + "." + extension)?.file?.toPath()

    private fun showError(project: Project, error: Throwable) =
        Messages.showErrorDialog(project, error.message ?: error.javaClass.simpleName, "DBML Diagram Export")
}
