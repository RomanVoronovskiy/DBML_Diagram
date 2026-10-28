package io.github.dbmldiagram.plugin.importer

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.*
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.plugin.cloud.CloudSettings
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

internal class SqlImportState {
    var node: String = ""
    var entry: String = ""
    var dialect: String = SqlImportDialect.POSTGRESQL.name
}

@Service(Service.Level.APP)
@State(name = "DbmlDiagramSqlImport", storages = [Storage("dbml-diagram-sql-import.xml")])
internal class SqlImportSettings : PersistentStateComponent<SqlImportState> {
    private var data = SqlImportState()
    override fun getState() = data
    override fun loadState(state: SqlImportState) { data = state }
}

private class SqlImportDialog(project: Project, source: VirtualFile) : DialogWrapper(project) {
    private val settings = service<SqlImportSettings>()
    private val detected = SqlImportPaths.detect(CloudSettings.get().snapshot().nodePath)
    val dialect = JComboBox(SqlImportDialect.entries.toTypedArray()).apply {
        selectedItem = runCatching { SqlImportDialect.valueOf(settings.state.dialect) }.getOrDefault(SqlImportDialect.POSTGRESQL)
    }
    private val node = JTextField(settings.state.node.ifBlank { detected.node }, 55)
    private val entry = JTextField(settings.state.entry.ifBlank { detected.entry }, 55)
    private val sourceLabel = source.name
    init { title = "Import SQL to DBML"; setOKButtonText("Convert"); init() }
    override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 1, 4, 4)).apply {
        add(JLabel("SQL file: $sourceLabel")); add(JLabel("SQL dialect")); add(dialect)
        add(JLabel("Node.js executable")); add(node)
        add(JLabel("Official @dbml/cli entry: node_modules/@dbml/cli/bin/sql2dbml.js")); add(entry)
        add(JLabel("Install once: npm install -g @dbml/cli (Node.js 18+; 24 LTS recommended)"))
        add(JLabel("Converts locally. No database connection or dbdiagram login is needed."))
        add(JLabel("After conversion, choose a .dbml file. The source SQL stays unchanged."))
    }
    override fun doValidate(): ValidationInfo? {
        if (!runCatching { Files.isRegularFile(Path.of(node.text.trim())) }.getOrDefault(false)) return ValidationInfo("Select the installed Node.js executable.", node)
        if (!runCatching { Files.isRegularFile(Path.of(entry.text.trim())) && entry.text.trim().endsWith(".js") }.getOrDefault(false)) return ValidationInfo("Install @dbml/cli and select bin/sql2dbml.js.", entry)
        return null
    }
    fun paths(): SqlImportPaths {
        settings.loadState(SqlImportState().also { it.node = node.text.trim(); it.entry = entry.text.trim(); it.dialect = (dialect.selectedItem as SqlImportDialect).name })
        return SqlImportPaths(node.text.trim(), entry.text.trim())
    }
}

class SqlImportAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        importSql(project, event.getData(CommonDataKeys.VIRTUAL_FILE))
    }

    companion object {
        fun importSql(project: Project, initial: VirtualFile? = null) {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("sql").withTitle("Choose SQL DDL to convert to DBML")
            val source = FileChooser.chooseFile(descriptor, project, initial) ?: return
            if (source.length > SqlImportService.MAX_BYTES) {
                Messages.showErrorDialog(project, "SQL exceeds the 8 MiB import limit.", "Import SQL to DBML"); return
            }
            val dialog = SqlImportDialog(project, source)
            if (!dialog.showAndGet()) return
            val paths = dialog.paths()
            val dialect = dialog.dialect.selectedItem as SqlImportDialect
            // Include unsaved edits from an already open SQL editor.
            val sql = FileDocumentManager.getInstance().getDocument(source)?.immutableCharSequence?.toString()
                ?: source.contentsToByteArray().toString(source.charset)
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Convert SQL to DBML", true) {
                private var converted: String? = null
                private var failure: String? = null
                private var diagnostics = ""
                override fun run(indicator: ProgressIndicator) {
                    try {
                        converted = SqlImportService.convert(sql, paths, dialect, indicator::checkCanceled)
                        indicator.checkCanceled()
                        diagnostics = TolerantDbmlParser().parse(converted!!).errors.take(15)
                            .joinToString("\n") { "${it.severity}: line ${it.line}: ${it.message}" }
                    } catch (canceled: ProcessCanceledException) { throw canceled }
                    catch (error: Exception) { failure = error.message ?: error.javaClass.simpleName }
                }
                override fun onSuccess() {
                    if (project.isDisposed) return
                    failure?.let { Messages.showErrorDialog(project, it, "Import SQL to DBML"); return }
                    val content = converted ?: return
                    if (diagnostics.isNotEmpty()) {
                        if (Messages.showYesNoDialog(project, "The converter produced DBML with local preview diagnostics:\n$diagnostics\n\nSave the DBML for review?", "Review SQL import", Messages.getWarningIcon()) != Messages.YES) return
                    }
                    val target = FileChooserFactory.getInstance().createSaveFileDialog(
                        FileSaverDescriptor("Save converted DBML", "", "dbml"), project)
                        .save(source.parent, source.nameWithoutExtension + ".dbml")?.file?.toPath() ?: return
                    if (!target.fileName.toString().endsWith(".dbml", true) || target.toAbsolutePath().normalize() == source.toNioPath().toAbsolutePath().normalize()) {
                        Messages.showErrorDialog(project, "Choose a separate .dbml file.", "Import SQL to DBML"); return
                    }
                    try {
                        val parent = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.parent)
                            ?: error("Target directory is unavailable.")
                        val name = target.fileName.toString()
                        var output: VirtualFile? = null
                        WriteCommandAction.runWriteCommandAction(project, "Import SQL to DBML", null, Runnable {
                            val file = parent.findChild(name) ?: parent.createChildData(this, name)
                            val document = FileDocumentManager.getInstance().getDocument(file) ?: error("Cannot open DBML document.")
                            document.setText(content)
                            FileDocumentManager.getInstance().saveDocument(document)
                            output = file
                        })
                        output?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                    } catch (error: Exception) {
                        Messages.showErrorDialog(project, error.message.orEmpty(), "Cannot save converted DBML")
                    }
                }
            })
        }
    }
}
