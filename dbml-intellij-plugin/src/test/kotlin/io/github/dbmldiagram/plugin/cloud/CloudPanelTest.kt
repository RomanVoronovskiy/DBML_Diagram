package io.github.dbmldiagram.plugin.cloud

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.output.XMLOutputter
import java.awt.Component
import java.awt.Container
import javax.swing.JTable
import javax.swing.JTextField

class CloudPanelTest : BasePlatformTestCase() {
    private fun children(root: Component): List<Component> = listOf(root) + if (root is Container) root.components.flatMap(::children) else emptyList()
    fun testListSearchIsLiteralAndNonEditable() {
        val panel = DbdiagramPanel(project)
        try {
            panel.display(listOf(CloudDiagram("6ab985d75869425612aa7738", "Orders [legacy]"), CloudDiagram("6ab985d75869425612aa7739", "Finance")))
            val table = children(panel).filterIsInstance<JTable>().single()
            val search = children(panel).filterIsInstance<JTextField>().single()
            assertEquals(2, table.rowCount)
            assertFalse(table.isCellEditable(0, 0))
            search.text = "[legacy]"
            assertEquals(1, table.rowCount)
            assertEquals("Orders [legacy]", table.getValueAt(0, 0))
            search.text = "aa7739"
            assertEquals(1, table.rowCount)
            assertEquals("Finance", table.getValueAt(0, 0))
            search.text = "FINANCE"
            assertEquals(1, table.rowCount)
            search.text = ""
            assertEquals(2, table.rowCount)
        } finally { panel.dispose() }
    }
    fun testBindingsPersistOnlyMetadataAndBaselineHash() {
        val bindings = CloudBindings()
        val diagram = CloudDiagram("6ab985d75869425612aa7738", "Project DB", "private schema must not be serialized")
        bindings.bind("file:///schema.dbml", diagram, "scope", CloudData.fingerprint(diagram.content!!))
        bindings.bind("file:///schema.dbml", diagram.copy(name = "Renamed"), "scope2", CloudData.fingerprint("updated"))
        assertEquals(1, bindings.state.bindings.size)
        val restored = CloudBindings().apply { loadState(bindings.state) }
        assertEquals("Renamed", restored.find("file:///schema.dbml")!!.diagramName)
        assertEquals(CloudData.fingerprint("updated"), restored.find("file:///schema.dbml")!!.baselineHash)
        val xml = XMLOutputter().outputString(XmlSerializer.serialize(bindings.state))
        assertFalse(xml.contains("private schema"))
        assertFalse(xml.contains("token", true))
    }
    fun testConnectionChangesInvalidateOldBindingScopesWithoutStoringToken() {
        val settings = CloudSettings()
        settings.loadState(ConnectionState().apply { mode = CloudMode.API.name })
        val original = settings.snapshot().scope()
        assertEquals(original, settings.snapshot().scope())
        settings.invalidateBindings()
        assertFalse(original == settings.snapshot().scope())
        val xml = XMLOutputter().outputString(XmlSerializer.serialize(settings.state))
        assertFalse(xml.contains("token", true))
    }
}
