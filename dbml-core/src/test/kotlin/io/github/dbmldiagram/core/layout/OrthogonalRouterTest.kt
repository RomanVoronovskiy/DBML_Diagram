package io.github.dbmldiagram.core.layout

import kotlin.math.abs
import kotlin.test.*

class OrthogonalRouterTest {
    private val orders = TableNode("orders", 48.0, 412.0, 360.0, 100.0)
    private val users = TableNode("users", 264.0, 174.0, 390.0, 100.0)

    @Test fun `reroutes screenshot regression around the referenced table`() {
        val nodes = listOf(orders, users)
        val route = OrthogonalRouter.route(
            relationshipAnchor(orders, TableSide.RIGHT, 78.0), TableSide.RIGHT,
            relationshipAnchor(users, TableSide.LEFT, 52.0), TableSide.LEFT,
            DiagramPoint(430.0, 146.0), nodes,
        )
        assertAvoidsCards(route.points, nodes)
        assertEquals(orders.y + 78.0, route.points.first().y)
        assertEquals(users.y + 52.0, route.points.last().y)
    }

    @Test fun `reroutes around unrelated cards and clamps a control inside a card`() {
        val obstacle = TableNode("third", 410.0, 300.0, 350.0, 80.0)
        val nodes = listOf(orders, users, obstacle)
        for (control in listOf(DiagramPoint(430.0, 146.0), DiagramPoint(400.0, 220.0))) {
            val route = OrthogonalRouter.route(
                relationshipAnchor(orders, TableSide.RIGHT, 78.0), TableSide.RIGHT,
                relationshipAnchor(users, TableSide.LEFT, 52.0), TableSide.LEFT, control, nodes,
            )
            assertAvoidsCards(route.points, nodes)
            assertTrue(nodes.none { inside(route.control, it) })
        }
    }

    @Test fun `all manual attachment sides have orthogonal obstacle free paths`() {
        val other = users.copy(x = 900.0, y = 80.0)
        val nodes = listOf(orders, other)
        for (from in TableSide.entries) for (to in TableSide.entries) {
            val a = relationshipAnchor(orders, from, 78.0)
            val b = relationshipAnchor(other, to, 52.0)
            val route = OrthogonalRouter.route(a, from, b, to, DiagramPoint(700.0, 300.0), nodes)
            assertEquals(a, route.points.first())
            assertEquals(b, route.points.last())
            assertAvoidsCards(route.points, nodes)
        }
    }

    @Test fun `self references route outside their card`() {
        val nodes = listOf(users)
        val route = OrthogonalRouter.route(
            relationshipAnchor(users, TableSide.RIGHT, 78.0), TableSide.RIGHT,
            relationshipAnchor(users, TableSide.LEFT, 52.0), TableSide.LEFT,
            DiagramPoint(users.x + users.width / 2, users.y - 35), nodes,
        )
        assertAvoidsCards(route.points, nodes)
    }

    @Test fun `closely spaced cards do not send exit stubs into the neighboring card`() {
        val a = orders.copy(y = 200.0)
        val b = users.copy(x = a.x + a.width + 8, y = a.y)
        val start = relationshipAnchor(a, TableSide.RIGHT, 52.0)
        val end = relationshipAnchor(b, TableSide.LEFT, 52.0)
        val route = OrthogonalRouter.route(start, TableSide.RIGHT, end, TableSide.LEFT, DiagramPoint((start.x + end.x) / 2, start.y), listOf(a, b))
        assertAvoidsCards(route.points, listOf(a, b))
        assertEquals(listOf(start, end), route.points)
    }

    private fun inside(p: DiagramPoint, n: TableNode) = p.x > n.x + .01 && p.x < n.x + n.width - .01 && p.y > n.y + .01 && p.y < n.y + n.height - .01
    private fun assertAvoidsCards(points: List<DiagramPoint>, nodes: List<TableNode>) {
        for ((a, b) in points.zipWithNext()) {
            assertTrue(abs(a.x - b.x) < .01 || abs(a.y - b.y) < .01, "Diagonal segment: $a -> $b")
            for (n in nodes) {
                val crosses = if (abs(a.y - b.y) < .01) a.y > n.y + .01 && a.y < n.y + n.height - .01 && maxOf(a.x, b.x) > n.x + .01 && minOf(a.x, b.x) < n.x + n.width - .01
                else a.x > n.x + .01 && a.x < n.x + n.width - .01 && maxOf(a.y, b.y) > n.y + .01 && minOf(a.y, b.y) < n.y + n.height - .01
                assertFalse(crosses, "Route crosses ${n.tableId}: $a -> $b")
            }
        }
    }
}
