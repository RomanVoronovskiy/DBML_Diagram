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
        assertEquals(orders.y + columnRowOffset(1), edge.points.first().y)
        assertEquals(users.y + columnRowOffset(0), edge.points.last().y)
        assertEquals(columnRowOffset(1), edge.fromColumnOffset)
        assertEquals(columnRowOffset(0), edge.toColumnOffset)
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
        assertEquals(orders.y + columnRowOffset(0), edge.points.first().y)
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

    @Test fun `anchors later columns with aliases and reversed references after moving tables`() {
        for (ref in listOf("Ref: o.user_id > u.id", "Ref: u.id < o.user_id")) {
            val schema = TolerantDbmlParser().parse("""
                Table users as u {
                  email text
                  id uuid [pk]
                }
                Table orders as o {
                  id uuid [pk]
                  amount int
                  user_id uuid
                }
                $ref
            """.trimIndent()).schema!!
            val route = ManualRelationRoute(TableSide.RIGHT, TableSide.LEFT, DiagramPoint(600.0, 200.0))
            val layout = LayeredDiagramLayoutEngine(
                mapOf("orders" to DiagramPoint(100.0, 800.0), "users" to DiagramPoint(900.0, 100.0)),
                mapOf(schema.references.single().routeId() to route),
            ).layout(schema)
            val edge = layout.edges.single()
            val orders = layout.nodes.single { it.tableId == "orders" }
            val users = layout.nodes.single { it.tableId == "users" }
            assertEquals(DiagramPoint(orders.x + orders.width, orders.y + columnRowOffset(2)), edge.points.first())
            assertEquals(DiagramPoint(users.x, users.y + columnRowOffset(1)), edge.points.last())
        }
    }

    @Test fun `one to one and many to many attach to their actual columns`() {
        for (operator in listOf("-", "<>")) {
            val schema = TolerantDbmlParser().parse("""
                Table users {
                  email text
                  id uuid [pk]
                }
                Table profiles {
                  name text
                  notes text
                  user_id uuid [pk]
                }
                Ref: users.id $operator profiles.user_id
            """.trimIndent()).schema!!
            val layout = LayeredDiagramLayoutEngine().layout(schema)
            val edge = layout.edges.single()
            val from = layout.nodes.single { it.tableId == edge.fromTableId }
            val to = layout.nodes.single { it.tableId == edge.toTableId }
            val fromIndex = if (edge.fromTableId == "users") 1 else 2
            val toIndex = if (edge.toTableId == "users") 1 else 2
            assertEquals(from.y + columnRowOffset(fromIndex), edge.points.first().y)
            assertEquals(to.y + columnRowOffset(toIndex), edge.points.last().y)
        }
    }
}
