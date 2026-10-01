package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import kotlin.test.Test
import kotlin.test.assertEquals

class LayeredDiagramLayoutEngineTest {
    @Test
    fun `routes a one to many reference between attached table sides`() {
        val schema = TolerantDbmlParser().parse(
            """
            Table users {
              id uuid [pk]
            }
            Table orders {
              id uuid [pk]
              user_id uuid [not null]
            }
            Ref: users.id < orders.user_id
            """.trimIndent(),
        ).schema!!

        val layout = LayeredDiagramLayoutEngine().layout(schema)
        val users = layout.nodes.single { it.tableId == "users" }
        val orders = layout.nodes.single { it.tableId == "orders" }
        val edge = layout.edges.single()

        assertEquals(TableSide.LEFT, edge.fromSide)
        assertEquals(TableSide.RIGHT, edge.toSide)
        assertEquals(orders.y + orders.height / 2, edge.points.first().y)
        assertEquals(users.y + users.height / 2, edge.points.last().y)
    }

    @Test
    fun `applies manual table positions and reroutes relationship endpoints`() {
        val schema = TolerantDbmlParser().parse(
            """
            Table users { id uuid [pk] }
            Table orders { user_id uuid [ref: > users.id] }
            """.trimIndent(),
        ).schema!!
        val requested = DiagramPoint(900.0, 420.0)

        val layout = LayeredDiagramLayoutEngine(mapOf("orders" to requested)).layout(schema)
        val orders = layout.nodes.single { it.tableId == "orders" }
        val edge = layout.edges.single()

        assertEquals(requested.x, orders.x)
        assertEquals(requested.y, orders.y)
        assertEquals(orders.x, edge.points.first().x)
        assertEquals(orders.y + orders.height / 2, edge.points.first().y)
        assertEquals(true, layout.width > orders.x + orders.width)
    }

    @Test
    fun `keeps manual route attached to selected top and bottom sides`() {
        val schema = TolerantDbmlParser().parse(
            """
            Table users { id uuid [pk] }
            Table orders { user_id uuid [ref: > users.id] }
            """.trimIndent(),
        ).schema!!
        val relation = schema.references.single()
        val control = DiagramPoint(640.0, 180.0)
        val route = ManualRelationRoute(TableSide.TOP, TableSide.BOTTOM, control)

        val layout = LayeredDiagramLayoutEngine(
            manualRoutes = mapOf(relation.routeId() to route),
        ).layout(schema)
        val users = layout.nodes.single { it.tableId == "users" }
        val orders = layout.nodes.single { it.tableId == "orders" }
        val edge = layout.edges.single()

        assertEquals(TableSide.TOP, edge.fromSide)
        assertEquals(TableSide.BOTTOM, edge.toSide)
        assertEquals(DiagramPoint(orders.x + orders.width / 2, orders.y), edge.points.first())
        assertEquals(DiagramPoint(users.x + users.width / 2, users.y + users.height), edge.points.last())
        assertEquals(control, edge.control)
        assertEquals(true, control in edge.points)
    }
}
