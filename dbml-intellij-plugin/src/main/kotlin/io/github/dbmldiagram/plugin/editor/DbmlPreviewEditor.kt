package io.github.dbmldiagram.plugin.editor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import io.github.dbmldiagram.core.ddl.DdlDialect
import io.github.dbmldiagram.core.layout.DiagramPoint
import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.layout.ManualRelationRoute
import io.github.dbmldiagram.core.layout.TableSide
import io.github.dbmldiagram.core.model.DbmlParseError
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import io.github.dbmldiagram.plugin.preview.DebouncedRenderScheduler
import io.github.dbmldiagram.plugin.preview.DiagramExporter
import io.github.dbmldiagram.plugin.preview.DiagramPositionStore
import io.github.dbmldiagram.plugin.preview.PreviewHtml
import io.github.dbmldiagram.plugin.preview.PreviewViewState
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel

class DbmlPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val changes = PropertyChangeSupport(this)
    private val panel = JPanel(BorderLayout())
    private val browser = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val positionQuery = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val fallback = JEditorPane("text/html", "JCEF is unavailable in this IDE runtime.").apply { isEditable = false }
    private val document: Document = requireNotNull(FileDocumentManager.getInstance().getDocument(file)) { "No document for ${file.path}" }
    private val positionStore = DiagramPositionStore(project, file)
    private val manualPositions = ConcurrentHashMap(positionStore.load())
    private val manualRoutes = ConcurrentHashMap(positionStore.loadRoutes())
    private val layoutEngine = LayeredDiagramLayoutEngine(manualPositions, manualRoutes)
    private var displayedSvg: String? = null
    private val exportButtons = mutableListOf<JButton>()
    private var pendingViewState: PreviewViewState? = null
    private val ddlDialect = JComboBox(DdlDialect.values()).apply {
        selectedItem = DdlDialect.POSTGRESQL
        toolTipText = "SQL dialect used by DDL export"
        isFocusable = false
    }
    private var ddlDialectInitialized = false
    private var disposed = false
    private val scheduler = DebouncedRenderScheduler(TolerantDbmlParser(), layoutEngine, SvgDiagramRenderer(), ::showResult)
    private val listener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            exportButtons.forEach { it.isEnabled = false }
            scheduler.schedule(event.document.immutableCharSequence.toString())
        }
    }

    init {
        positionQuery?.addHandler { payload ->
            acceptPreviewChange(payload)
            null
        }
        panel.add(createToolbar(), BorderLayout.NORTH)
        panel.add(browser?.component ?: fallback, BorderLayout.CENTER)
        document.addDocumentListener(listener, this)
        scheduler.renderNow(document.immutableCharSequence.toString())
    }

    private fun createToolbar() = JPanel().apply {
        layout = ResponsiveToolbarLayout()
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) { revalidate() }
        })
        border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        add(button("Fit") { execute("fit()") })
        add(button("−") { execute("zoom(.8)") })
        add(button("100%") { execute("actual()") })
        add(button("+") { execute("zoom(1.25)") })
        add(button("Reset layout") {
            manualPositions.clear()
            manualRoutes.clear()
            positionStore.clear()
            positionStore.clearRoutes()
            pendingViewState = null
            scheduler.renderNow(document.immutableCharSequence.toString())
        }.apply { toolTipText = "Discard saved table positions and relationship routes" })
        add(button("Refresh") { scheduler.renderNow(document.immutableCharSequence.toString()) })
        add(JLabel("DDL dialect:"))
        add(ddlDialect)
        add(exportButton("Export DDL") { schema, _ ->
            val dialect = ddlDialect.selectedItem as DdlDialect
            DiagramExporter.exportDdl(project, file, dialect.displayName, dialect.generator().generate(schema))
        }.apply { toolTipText = "Export DDL using the selected SQL dialect" })
        add(exportButton("Export SVG") { _, svg -> DiagramExporter.exportSvg(project, file, svg) })
        add(exportButton("Export PNG") { _, svg -> DiagramExporter.exportPng(project, file, svg) })
        if (browser == null) add(JLabel("JCEF unavailable"))
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply {
        isFocusable = false
        addActionListener { action() }
    }

    private fun exportButton(text: String, action: (DbmlSchema, String) -> Unit) = button(text) {
        // Validate the current document, not a possibly stale debounced preview.
        val result = TolerantDbmlParser().parse(document.immutableCharSequence.toString())
        val schema = result.schema
        if (result.hasErrors || schema == null) {
            Messages.showErrorDialog(project, result.errors.joinToString("\n") { "Line ${it.line}: ${it.message}" }, "Fix DBML errors before exporting")
        } else {
            action(schema, SvgDiagramRenderer().render(schema, layoutEngine.layout(schema)))
        }
    }.apply {
        isEnabled = false
        exportButtons += this
    }

    private fun showResult(svg: String?, schema: DbmlSchema?, errors: List<DbmlParseError>) {
        exportButtons.forEach { it.isEnabled = svg != null && errors.none { error -> error.severity == io.github.dbmldiagram.core.model.DbmlParseSeverity.ERROR } }
        if (svg != null) displayedSvg = svg
        if (schema != null) {
            if (!ddlDialectInitialized) {
                val databaseType = schema.project?.properties?.entries
                    ?.firstOrNull { it.key.equals("database_type", true) }
                    ?.value
                DdlDialect.fromDatabaseType(databaseType)?.let {
                    ddlDialect.selectedItem = it
                }
                ddlDialectInitialized = true
            }
        }
        val message = errors.joinToString("\n") { "DBML ${it.severity.name.lowercase()}: line ${it.line}: ${it.message}" }.ifEmpty { null }
        val viewState = pendingViewState.also { pendingViewState = null }
        val html = PreviewHtml.page(
            displayedSvg,
            message,
            JBColor.isBright().not(),
            positionQuery?.inject("payload"),
            viewState,
        )
        if (browser != null) browser.loadHTML(html) else fallback.text = "<html><body><b>${message ?: "JCEF is unavailable."}</b><p>The standard code editor remains usable.</p></body></html>"
    }

    private fun acceptPreviewChange(payload: String) {
        val parts = payload.split('\t')
        when (parts.firstOrNull()) {
            "T" -> acceptTablePosition(parts)
            "R" -> acceptRelationRoute(parts)
        }
    }

    private fun acceptTablePosition(parts: List<String>) {
        if (parts.size != 7) return
        val table = decode(parts[1]) ?: return
        val values = parts.drop(2).map { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return }
        val point = DiagramPoint(values[0].coerceAtLeast(48.0), values[1].coerceAtLeast(48.0))
        val view = PreviewViewState(values[2].coerceIn(0.1, 4.0), values[3], values[4])
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            manualPositions[table] = point
            positionStore.save(manualPositions)
            pendingViewState = view
            scheduler.renderNow(document.immutableCharSequence.toString())
        }
    }

    private fun acceptRelationRoute(parts: List<String>) {
        if (parts.size != 9) return
        val relation = decode(parts[1]) ?: return
        val fromSide = runCatching { TableSide.valueOf(parts[2].uppercase()) }.getOrNull() ?: return
        val toSide = runCatching { TableSide.valueOf(parts[3].uppercase()) }.getOrNull() ?: return
        val values = parts.drop(4).map { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return }
        val route = ManualRelationRoute(
            fromSide,
            toSide,
            DiagramPoint(values[0].coerceAtLeast(20.0), values[1].coerceAtLeast(20.0)),
        )
        val view = PreviewViewState(values[2].coerceIn(0.1, 4.0), values[3], values[4], relation)
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            manualRoutes[relation] = route
            positionStore.saveRoutes(manualRoutes)
            pendingViewState = view
            scheduler.renderNow(document.immutableCharSequence.toString())
        }
    }

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()

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
        positionQuery?.dispose()
        browser?.dispose()
    }
}
