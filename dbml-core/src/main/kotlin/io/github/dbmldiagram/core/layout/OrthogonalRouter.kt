package io.github.dbmldiagram.core.layout

import java.util.PriorityQueue
import kotlin.math.abs

/** Orthogonal visibility-grid routing, with a clearance around every table. */
object OrthogonalRouter {
    private const val CLEARANCE = 12.0
    private const val EPS = 0.01
    private data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double)
    private data class QueueEntry(val state: Int, val cost: Double, val estimate: Double)
    data class Route(val points: List<DiagramPoint>, val control: DiagramPoint)

    fun route(start: DiagramPoint, fromSide: TableSide, end: DiagramPoint, toSide: TableSide,
              control: DiagramPoint, nodes: List<TableNode>): Route {
        val padding = clearance(nodes)
        val boxes = nodes.map { Box(it.x - padding, it.y - padding, it.x + it.width + padding, it.y + it.height + padding) }
        val a = exit(start, fromSide, nodes)
        val b = exit(end, toSide, nodes)
        val waypoint = freeControl(control, boxes)
        // Preserve a safe user-defined route without running the grid search.
        val preferred = simplify(listOf(a) + leg(a, fromSide, waypoint) + leg(b, toSide, waypoint).reversed() + b)
        var usesWaypoint = true
        val middle = if (preferred.zipWithNext().all { (p, q) -> clear(p, q, boxes) }) preferred else {
            val first = search(a, waypoint, boxes)
            val second = search(waypoint, b, boxes)
            if (first != null && second != null) simplify(first + second.drop(1))
            else { usesWaypoint = false; search(a, b, boxes) ?: preferred } // Overlapping cards may make an endpoint inaccessible.
        }
        val actualControl = if (usesWaypoint) waypoint else middle[middle.size / 2]
        return Route(simplify(listOf(start) + middle + end), actualControl)
    }

    private fun inside(p: DiagramPoint, r: Box) = p.x > r.left + EPS && p.x < r.right - EPS && p.y > r.top + EPS && p.y < r.bottom - EPS
    private fun clear(a: DiagramPoint, b: DiagramPoint, boxes: List<Box>): Boolean = boxes.none { r ->
        if (abs(a.y - b.y) < EPS) a.y > r.top + EPS && a.y < r.bottom - EPS && maxOf(a.x, b.x) > r.left + EPS && minOf(a.x, b.x) < r.right - EPS
        else if (abs(a.x - b.x) < EPS) a.x > r.left + EPS && a.x < r.right - EPS && maxOf(a.y, b.y) > r.top + EPS && minOf(a.y, b.y) < r.bottom - EPS
        else true
    }

    private fun freeControl(p: DiagramPoint, boxes: List<Box>): DiagramPoint {
        if (boxes.none { inside(p, it) }) return p
        val candidates = boxes.flatMap { r -> listOf(
            DiagramPoint(r.left.coerceAtLeast(20.0), p.y), DiagramPoint(r.right, p.y),
            DiagramPoint(p.x, r.top.coerceAtLeast(20.0)), DiagramPoint(p.x, r.bottom),
        ) }.filter { q -> boxes.none { inside(q, it) } }
        return candidates.minByOrNull { abs(it.x - p.x) + abs(it.y - p.y) } ?: p
    }

    private fun search(start: DiagramPoint, end: DiagramPoint, boxes: List<Box>): List<DiagramPoint>? {
        if (start == end) return listOf(start)
        if (boxes.any { inside(start, it) || inside(end, it) }) return null
        val xs = (listOf(start.x, end.x) + boxes.flatMap { listOf(it.left, it.right) }).distinct().sorted()
        val ys = (listOf(start.y, end.y) + boxes.flatMap { listOf(it.top, it.bottom) }).distinct().sorted()
        // Avoid unbounded allocation for very large schemas with a blocked route.
        if (xs.size.toLong() * ys.size > 100_000) return null
        val width = xs.size
        fun point(cell: Int) = DiagramPoint(xs[cell % width], ys[cell / width])
        val source = ys.indexOf(start.y) * width + xs.indexOf(start.x)
        val target = ys.indexOf(end.y) * width + xs.indexOf(end.x)
        val distances = DoubleArray(width * ys.size * 3) { Double.POSITIVE_INFINITY }
        val previous = IntArray(distances.size) { -1 }
        val queue = PriorityQueue(compareBy<QueueEntry> { it.estimate }.thenBy { it.state })
        val initial = source * 3
        distances[initial] = 0.0
        queue += QueueEntry(initial, 0.0, abs(start.x - end.x) + abs(start.y - end.y))
        while (queue.isNotEmpty()) {
            val item = queue.remove()
            if (item.cost > distances[item.state] + EPS) continue
            val cell = item.state / 3
            val direction = item.state % 3
            if (cell == target) {
                val path = mutableListOf<DiagramPoint>()
                var state = item.state
                while (state >= 0) { path += point(state / 3); state = previous[state] }
                return simplify(path.reversed())
            }
            val p = point(cell)
            val neighbors = buildList {
                if (cell % width > 0) add(cell - 1 to 1)
                if (cell % width < width - 1) add(cell + 1 to 1)
                if (cell / width > 0) add(cell - width to 2)
                if (cell / width < ys.lastIndex) add(cell + width to 2)
            }
            for ((nextCell, nextDirection) in neighbors) {
                val q = point(nextCell)
                if (!clear(p, q, boxes)) continue
                val cost = item.cost + abs(q.x - p.x) + abs(q.y - p.y) + if (direction != 0 && direction != nextDirection) 18.0 else 0.0
                val next = nextCell * 3 + nextDirection
                if (cost + EPS >= distances[next]) continue
                distances[next] = cost
                previous[next] = item.state
                queue += QueueEntry(next, cost, cost + abs(q.x - end.x) + abs(q.y - end.y))
            }
        }
        return null
    }

    private fun clearance(nodes: List<TableNode>): Double {
        var padding = CLEARANCE
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]
            val gap = maxOf(maxOf(a.x - b.x - b.width, b.x - a.x - a.width), maxOf(a.y - b.y - b.height, b.y - a.y - a.height))
            if (gap > EPS) padding = minOf(padding, maxOf(.1, gap / 3))
        }
        return padding
    }
    private fun exit(p: DiagramPoint, side: TableSide, nodes: List<TableNode>): DiagramPoint {
        var distance = 24.0
        for (n in nodes) {
            val gap = when (side) {
                TableSide.RIGHT -> if (p.y in n.y..n.y + n.height && n.x > p.x + EPS) n.x - p.x else null
                TableSide.LEFT -> if (p.y in n.y..n.y + n.height && n.x + n.width < p.x - EPS) p.x - n.x - n.width else null
                TableSide.BOTTOM -> if (p.x in n.x..n.x + n.width && n.y > p.y + EPS) n.y - p.y else null
                TableSide.TOP -> if (p.x in n.x..n.x + n.width && n.y + n.height < p.y - EPS) p.y - n.y - n.height else null
            }
            if (gap != null) distance = minOf(distance, gap / 2)
        }
        return when (side) {
            TableSide.TOP -> p.copy(y = p.y - distance)
            TableSide.RIGHT -> p.copy(x = p.x + distance)
            TableSide.BOTTOM -> p.copy(y = p.y + distance)
            TableSide.LEFT -> p.copy(x = p.x - distance)
        }
    }
    private fun leg(p: DiagramPoint, side: TableSide, c: DiagramPoint): List<DiagramPoint> = when (side) {
        TableSide.RIGHT, TableSide.LEFT -> {
            val x = if (side == TableSide.RIGHT) maxOf(p.x, c.x) else minOf(p.x, c.x)
            listOf(p, DiagramPoint(x, p.y), DiagramPoint(x, c.y), c)
        }
        else -> {
            val y = if (side == TableSide.BOTTOM) maxOf(p.y, c.y) else minOf(p.y, c.y)
            listOf(p, DiagramPoint(p.x, y), DiagramPoint(c.x, y), c)
        }
    }
    private fun simplify(points: List<DiagramPoint>): List<DiagramPoint> = buildList {
        for (p in points) {
            if (lastOrNull() == p) continue
            while (size >= 2) {
                val a = get(size - 2); val b = last()
                val straight = (abs(a.x - b.x) < EPS && abs(b.x - p.x) < EPS && (b.y - a.y) * (p.y - b.y) >= 0) ||
                    (abs(a.y - b.y) < EPS && abs(b.y - p.y) < EPS && (b.x - a.x) * (p.x - b.x) >= 0)
                if (!straight) break
                removeAt(lastIndex)
            }
            add(p)
        }
    }
}
