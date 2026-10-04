package io.github.dbmldiagram.plugin.preview

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dbmldiagram.core.layout.DiagramPoint
import io.github.dbmldiagram.core.layout.ManualRelationRoute
import io.github.dbmldiagram.core.layout.TableSide

class DiagramPositionStoreTest : BasePlatformTestCase() {
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
