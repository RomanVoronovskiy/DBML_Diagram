package io.github.dbmldiagram.plugin.database

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import io.github.dbmldiagram.core.database.SchemaComparison
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.*
import javax.swing.table.AbstractTableModel

internal class MigrationSelection(private val comparison: SchemaComparison) : AbstractTableModel() {
    private val selected=comparison.differences.filter { it.sql!=null && !it.requiresReview }.map { it.id }.toMutableSet()
    override fun getRowCount()=comparison.differences.size
    override fun getColumnCount()=4
    override fun getColumnName(column: Int)=listOf("Include","Status","Object","Difference")[column]
    override fun getColumnClass(column: Int): Class<*> = if(column==0) java.lang.Boolean::class.java else String::class.java
    override fun isCellEditable(row: Int,column: Int)=column==0 && comparison.differences[row].sql!=null
    override fun getValueAt(row: Int,column: Int): Any {
        val change=comparison.differences[row]
        return when(column) { 0 -> change.id in selected; 1 -> change.kind.name + if(change.requiresReview) " / REVIEW" else if(change.sql==null) " / MANUAL" else "";
            2 -> change.objectName; else -> change.message }
    }
    override fun setValueAt(value: Any?,row: Int,column: Int) {
        if(!isCellEditable(row,column)) return
        val change=comparison.differences[row]
        if(value==true) selected+=change.id else selected-=change.id
        fireTableRowsUpdated(row,row)
    }
    fun sql() = comparison.migration(selected.toSet())
}

internal class DatabaseDifferencesDialog(private val project: Project,private val file: VirtualFile,
    private val sourceText: String,session: DatabaseComparisonSession,private val comparison: SchemaComparison,
    private val sessionIsCurrent: () -> Boolean) : DialogWrapper(project) {
    private val model=MigrationSelection(comparison)
    private val sql=JTextArea().apply { isEditable=false;lineWrap=false;tabSize=2 }
    private val label="${comparison.differences.size} differences — ${session.label}; snapshot ${java.time.Instant.ofEpochMilli(session.snapshot.capturedAtMillis)}"
    init {
        title="PostgreSQL schema differences"
        setOKButtonText("Save migration SQL…")
        init()
        model.addTableModelListener { updateSql() }
        updateSql()
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0,8)).apply {
        border=JBUI.Borders.empty(4)
        preferredSize=Dimension(1000,650)
        add(JLabel(label).apply { putClientProperty("html.disable",true) },BorderLayout.NORTH)
        val table=JTable(model).apply {
            autoResizeMode=JTable.AUTO_RESIZE_LAST_COLUMN
            columnModel.getColumn(0).preferredWidth=55
            columnModel.getColumn(1).preferredWidth=150
            columnModel.getColumn(2).preferredWidth=220
            columnModel.getColumn(3).preferredWidth=550
            setDefaultRenderer(String::class.java,object : javax.swing.table.DefaultTableCellRenderer() {
                init { putClientProperty("html.disable",true) }
            })
        }
        val split=JSplitPane(JSplitPane.VERTICAL_SPLIT,JScrollPane(table),JScrollPane(sql)).apply { resizeWeight=.55 }
        add(split,BorderLayout.CENTER)
        add(JLabel("Prerequisite tables, columns and keys are included automatically. Review the complete SQL below. Compare again after applying it."),BorderLayout.SOUTH)
    }
    private fun updateSql() {
        val result=runCatching { model.sql() }
        sql.text=result.getOrElse { it.message ?: "Select changes to generate a migration." }
        sql.caretPosition=0
        isOKActionEnabled=result.isSuccess
    }
    override fun doOKAction() {
        val document=FileDocumentManager.getInstance().getDocument(file)
        if(document?.immutableCharSequence.toString()!=sourceText || !sessionIsCurrent()) {
            Messages.showErrorDialog(project,"DBML or the comparison session changed. Reopen the differences before exporting.","Refresh comparison");return
        }
        val generated=runCatching { model.sql() }.getOrElse { Messages.showErrorDialog(project,it.message.orEmpty(),"Migration selection");return }
        val target=FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save PostgreSQL migration","","sql"),project)
            .save(file.parent,file.nameWithoutExtension+"-migration.sql")?.file?.toPath() ?: return
        if(!target.fileName.toString().endsWith(".sql",true)) { Messages.showErrorDialog(project,"Choose a .sql destination.","Migration export");return }
        val existing=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        val openDocument=existing?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        if(openDocument!=null && FileDocumentManager.getInstance().isDocumentUnsaved(openDocument)) {
            Messages.showErrorDialog(project,"Destination has unsaved editor changes. Save it first or choose another file.","Migration export");return
        }
        try {
            val temporary=Files.createTempFile(target.parent,".dbml-migration-",".sql")
            try { Files.writeString(temporary,generated);Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING) }
            finally { Files.deleteIfExists(temporary) }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.let {
                it.refresh(false,false)
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(it,true)
            }
            super.doOKAction()
        } catch(error: Exception) { Messages.showErrorDialog(project,error.message.orEmpty(),"Cannot save migration") }
    }
}
