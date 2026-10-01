package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import kotlin.test.Test
import kotlin.test.assertEquals

class LayeredDiagramLayoutEngineTest {
    @Test
    fun `routes a one to many reference from foreign key row to referenced row`() {
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

        assertEquals(orders.y + 48.0 + 26.0 + 13.0, edge.points.first().y)
        assertEquals(users.y + 48.0 + 13.0, edge.points.last().y)
    }
}
