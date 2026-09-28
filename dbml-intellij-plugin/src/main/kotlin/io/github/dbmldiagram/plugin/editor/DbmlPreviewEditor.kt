package io.github.dbmldiagram.plugin.editor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.model.DbmlParseError
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import io.github.dbmldiagram.plugin.preview.DebouncedRenderScheduler
import io.github.dbmldiagram.plugin.preview.DiagramExporter
import io.github.dbmldiagram.plugin.preview.PreviewHtml
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel

class DbmlPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val changes = PropertyChangeSupport(this)
    private val panel = JPanel(BorderLayout())
    private val browser = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val fallback = JEditorPane("text/html", "JCEF is unavailable in this IDE runtime.").apply { isEditable = false }
    private val document: Document = requireNotNull(FileDocumentManager.getInstance().getDocument(file)) { "No document for ${file.path}" }
    private var lastValidSvg: String? = null
    private var disposed = false
    private val scheduler = DebouncedRenderScheduler(TolerantDbmlParser(), LayeredDiagramLayoutEngine(), SvgDiagramRenderer(), ::showResult)
    private val listener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            scheduler.schedule(event.document.immutableCharSequence.toString())
        }
    }

    init {
        panel.add(createToolbar(), BorderLayout.NORTH)
        panel.add(browser?.component ?: fallback, BorderLayout.CENTER)
        document.addDocumentListener(listener, this)
        scheduler.renderNow(document.immutableCharSequence.toString())
    }

    private fun createToolbar() = JPanel().apply {
        layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3))
        border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        add(button("Fit") { execute("fit()") })
        add(button("−") { execute("zoom(.8)") })
        add(button("100%") { execute("actual()") })
        add(button("+") { execute("zoom(1.25)") })
        add(button("Refresh") { scheduler.renderNow(document.immutableCharSequence.toString()) })
        add(button("Export SVG") { lastValidSvg?.let { DiagramExporter.exportSvg(project, file, it) } })
        add(button("Export PNG") { lastValidSvg?.let { DiagramExporter.exportPng(project, file, it) } })
        if (browser == null) add(JLabel("JCEF unavailable"))
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply {
        isFocusable = false
        addActionListener { action() }
    }

    private fun showResult(svg: String?, errors: List<DbmlParseError>) {
        if (svg != null) lastValidSvg = svg
        val message = errors.firstOrNull()?.let { "DBML ${it.severity.name.lowercase()}: line ${it.line}: ${it.message}" }
        val html = PreviewHtml.page(lastValidSvg, message, JBColor.isBright().not())
        if (browser != null) browser.loadHTML(html) else fallback.text = "<html><body><b>${message ?: "JCEF is unavailable."}</b><p>The standard code editor remains usable.</p></body></html>"
    }

    private fun execute(script: String) {
        val cef = browser?.cefBrowser ?: return
        cef.executeJavaScript(script, cef.url, 0)
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent? = browser?.component ?: fallback
    override fun getName() = "DBML Diagram"
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changes.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changes.removePropertyChangeListener(listener)

    override fun dispose() {
        if (disposed) return
        disposed = true
        scheduler.dispose()
        browser?.dispose()
    }
}
