package io.github.dbmldiagram.plugin.preview

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dbmldiagram.core.layout.DiagramPoint
import io.github.dbmldiagram.core.layout.ManualRelationRoute
import io.github.dbmldiagram.core.layout.TableSide
import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DiagramPositionStoreTest : BasePlatformTestCase() {
    fun testNoteDragSurvivesReloadWithoutChangingDbml() {
        val source = "Note reminder { 'Review schema' }\nTable reminder { id int }"
        val file = myFixture.configureByText("notes.dbml", source).virtualFile
        val store = DiagramPositionStore(project, file)
        try {
            val update = PreviewChangeDecoder.decode("N\tnote%3Areminder\t-800\t-400\t0.8\t300\t200\t1\t0")!!
            assertTrue(update.positions.isEmpty())
            store.saveNotes(update.notes)
            val schema = TolerantDbmlParser().parse(source).schema!!
            val layout = LayeredDiagramLayoutEngine(manualNotes = DiagramPositionStore(project, file).loadNotes()).layout(schema)
            assertEquals(-800.0, layout.notes.single().x)
            assertEquals(-400.0, layout.notes.single().y)
            assertTrue(layout.nodes.single().x >= 0)
            assertEquals(source, myFixture.editor.document.text)
            assertNull(PreviewChangeDecoder.decode("N\tnote\tNaN\t1\t1\t0\t0\t1\t0"))
        } finally { store.clearNotes() }
    }
    fun testGroupBatchPersistsAndRestoresForExportWithoutEditingDbml() {
        val source = "Table users { id uuid [pk] }\nTable orders { user_id uuid [ref: > users.id] }\nTableGroup Accounts { users orders }"
        val file = myFixture.configureByText("group-batch.dbml", source).virtualFile
        val store = DiagramPositionStore(project, file)
        val schema = TolerantDbmlParser().parse(source).schema!!
        try {
            val json = """{"tables":[{"id":"users","x":-900,"y":-470},{"id":"orders","x":-350,"y":-220}],"routes":[{"id":"${schema.references.single().routeId()}","fromSide":"left","toSide":"right","x":-480,"y":-350}],"view":{"scale":0.7,"x":850,"y":600,"grid":true,"snap":false}}"""
            val update = PreviewChangeDecoder.decode("B\t" + URLEncoder.encode(json, StandardCharsets.UTF_8.name()))!!
            store.save(update.positions); store.saveRoutes(update.routes); store.saveView(update.view)
            val restored = DiagramPositionStore(project, file)
            val layout = LayeredDiagramLayoutEngine(restored.load(), restored.loadRoutes()).layout(schema)
            assertEquals(-900.0, layout.nodes.single { it.tableId == "users" }.x)
            assertEquals(-350.0, layout.nodes.single { it.tableId == "orders" }.x)
            assertEquals(2, layout.groups.single().tableIds.size)
            assertTrue(layout.originX < -900 && layout.originY < -470)
            assertTrue(SvgDiagramRenderer().render(schema, layout).contains("data-table-group=\"Accounts\""))
            assertEquals(update.view, restored.loadView())
            assertEquals(source, myFixture.editor.document.text)
        } finally { store.clear(); store.clearRoutes(); store.clearView() }
    }

    fun testNegativeCoordinatesAndCameraSurviveStoreReload() {
        val file = myFixture.configureByText("free-canvas.dbml", "Table users { id int }").virtualFile
        val store = DiagramPositionStore(project, file)
        try {
            val positions = mapOf("users" to DiagramPoint(-1200.0, -640.0))
            val routes = mapOf("r" to ManualRelationRoute(TableSide.LEFT, TableSide.TOP, DiagramPoint(-1500.0, -900.0)))
            val view = PreviewViewState(.35, 800.0, -120.0, grid = false, snap = true)
            store.save(positions); store.saveRoutes(routes); store.saveView(view)
            val reloaded = DiagramPositionStore(project, file)
            assertEquals(positions, reloaded.load())
            assertEquals(routes, reloaded.loadRoutes())
            assertEquals(view, reloaded.loadView())
            reloaded.clearView(); assertNull(reloaded.loadView())
        } finally { store.clear(); store.clearRoutes(); store.clearView() }
    }

    fun testAttachmentSidesSurviveStoreReload() {
        val file = myFixture.configureByText("routes.dbml", "Table users { id uuid [pk] }").virtualFile
        val store = DiagramPositionStore(project, file)
        try {
            val routes = mapOf("orders.user_id|users.id" to ManualRelationRoute(TableSide.TOP, TableSide.BOTTOM, DiagramPoint(700.0, 300.0)))
            store.saveRoutes(routes)
            assertEquals(routes, DiagramPositionStore(project, file).loadRoutes())
            store.saveRoutes(routes.mapValues { (_, route) -> route.copy(fromSide = TableSide.BOTTOM, toSide = TableSide.TOP) })
            assertEquals(TableSide.BOTTOM, DiagramPositionStore(project, file).loadRoutes().values.single().fromSide)
            assertEquals(TableSide.TOP, DiagramPositionStore(project, file).loadRoutes().values.single().toSide)
        } finally { store.clearRoutes() }
    }
}
