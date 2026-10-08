package io.github.dbmldiagram.plugin.cloud

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import io.github.dbmldiagram.plugin.editor.ResponsiveToolbarLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class DbdiagramToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DbdiagramPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "My Diagrams", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

internal class DbdiagramPanel(private val project: Project) : JPanel(BorderLayout(4, 4)), Disposable {
    private val controller = CloudController.get(project)
    private val model = object : DefaultTableModel(arrayOf("Name", "Access", "Modified", "Created", "ID"), 0) { override fun isCellEditable(row: Int, col: Int) = false }
    private val table = JTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setDefaultRenderer(String::class.java, DefaultTableCellRenderer().apply { putClientProperty("html.disable", true) })
        setDefaultRenderer(Any::class.java, DefaultTableCellRenderer().apply { putClientProperty("html.disable", true) })
    }
    private val sorter = TableRowSorter(model)
    private val search = JTextField(24)
    private val status = JLabel("Connect or refresh to load diagrams. Nothing is pushed automatically.")
    private val buttons = mutableListOf<JButton>()
    private var diagrams: List<CloudDiagram> = emptyList()
    private val unsubscribe: () -> Unit
    init {
        table.rowSorter = sorter
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        top.add(responsiveRow().apply {
            add(button("Connection…") { controller.settings() })
            add(button("Login / Connect") { controller.login(::display) })
            add(button("Refresh") { controller.refresh(::display) })
            add(button("Disconnect") { controller.logout() })
        })
        top.add(JPanel(BorderLayout(4, 0)).apply { add(JLabel("Search diagrams:"), BorderLayout.WEST); add(search, BorderLayout.CENTER) })
        add(top, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(responsiveRow().apply {
                add(button("Pull to file…") { selected()?.let(controller::importDiagram) })
                add(button("Link to current DBML") {
                    val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull { CloudController.isDbml(it) }
                    if (file == null) Messages.showInfoMessage(project, "Open a .dbml file in the editor first.", "Link DBML")
                    else selected()?.let { controller.bind(file, it) }
                })
                add(button("Open on web") { selected()?.let { BrowserUtil.browse(it.url) } })
            }, BorderLayout.NORTH)
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        search.document.addDocumentListener(object : DocumentListener {
            private fun update() { sorter.rowFilter = if (search.text.isBlank()) null else RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(search.text), 0, 4) }
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
        })
        unsubscribe = controller.listen { busy, text -> buttons.forEach { it.isEnabled = !busy }; status.text = text }
    }
    private fun responsiveRow() = JPanel(ResponsiveToolbarLayout()).apply {
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) { revalidate() }
        })
    }
    private fun button(label: String, action: () -> Unit) = JButton(label).apply { addActionListener { action() }; buttons += this }
    private fun selected(): CloudDiagram? {
        if (table.selectedRow < 0) { status.text = "Select a diagram first."; return null }
        return diagrams.getOrNull(table.convertRowIndexToModel(table.selectedRow))
    }
    internal fun display(items: List<CloudDiagram>) {
        diagrams = items
        model.rowCount = 0
        items.forEach { model.addRow(arrayOf(it.name, it.status.ifBlank { "—" }, it.updatedAt.ifBlank { "—" }, it.createdAt.ifBlank { "—" }, it.id)) }
    }
    override fun dispose() { unsubscribe() }
}
