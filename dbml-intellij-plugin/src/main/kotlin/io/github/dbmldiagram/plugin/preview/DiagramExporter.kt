package io.github.dbmldiagram.plugin.preview

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files

object DiagramExporter {
    private val log = Logger.getInstance(DiagramExporter::class.java)

    fun exportDdl(project: Project, source: VirtualFile, dialect: String, ddl: String) {
        val target = choose(project, source, "Export $dialect DDL", "sql") ?: return
        runCatching { Files.writeString(target, ddl) }.onFailure { showError(project, it) }
    }

    fun exportSvg(project: Project, source: VirtualFile, svg: String) {
        val target = choose(project, source, "Export DBML diagram as SVG", "svg") ?: return
        runCatching { Files.writeString(target, svg) }.onFailure { showError(project, it) }
    }

    fun exportPng(project: Project, source: VirtualFile, svg: String) {
        val validation = runCatching { PngDiagramWriter.dimensions(svg) }
        if (validation.isFailure) {
            showError(project, validation.exceptionOrNull()!!)
            return
        }
        val target = choose(project, source, "Export DBML diagram as PNG", "png") ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Export DBML diagram as PNG", false) {
            override fun run(indicator: ProgressIndicator) {
                runCatching {
                    PngDiagramWriter.write(svg, target)
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
                }.onFailure { error ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) showError(project, error)
                    }
                }
            }
        })
    }

    private fun choose(project: Project, source: VirtualFile, title: String, extension: String) =
        FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor(title, "", extension), project)
            .save(source.parent, source.nameWithoutExtension + "." + extension)?.file?.toPath()

    private fun showError(project: Project, error: Throwable) {
        log.warn("DBML diagram export failed", error)
        val explanation = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }
            .take(5).map { cause -> "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}" }
            .distinct().joinToString("\n")
        Messages.showErrorDialog(project, explanation, "DBML Diagram Export")
    }
}
