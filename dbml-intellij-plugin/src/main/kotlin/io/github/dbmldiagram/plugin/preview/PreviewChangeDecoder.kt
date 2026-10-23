package io.github.dbmldiagram.plugin.preview

import com.google.gson.JsonParser
import io.github.dbmldiagram.core.layout.DiagramPoint
import io.github.dbmldiagram.core.layout.ManualRelationRoute
import io.github.dbmldiagram.core.layout.TableSide
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class PreviewLayoutUpdate(
    val positions: Map<String, DiagramPoint> = emptyMap(),
    val routes: Map<String, ManualRelationRoute> = emptyMap(),
    val view: PreviewViewState,
)

/** Decode completely before applying a batch: malformed group moves never partially update the layout. */
internal object PreviewChangeDecoder {
    fun decode(payload: String): PreviewLayoutUpdate? = runCatching {
        require(payload.length <= 2_000_000)
        val parts = payload.split('\t')
        fun number(value: String) = value.toDouble().also { require(it.isFinite()) }
        fun name(value: String) = value.also { require(it.isNotEmpty() && it.length <= 8192) }
        fun unescape(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        fun side(value: String) = TableSide.valueOf(value.uppercase())
        fun view(offset: Int, selected: String? = null): PreviewViewState {
            val flags = parts.drop(offset + 3)
            require(flags.isEmpty() || (flags.size == 2 && flags.all { it == "0" || it == "1" }))
            return PreviewViewState(number(parts[offset]).coerceIn(.01, 4.0), number(parts[offset + 1]), number(parts[offset + 2]),
                selected, flags.firstOrNull() != "0", flags.getOrNull(1) == "1")
        }
        when (parts.firstOrNull()) {
            "V" -> { require(parts.size == 6); PreviewLayoutUpdate(view = view(1)) }
            "T" -> {
                require(parts.size == 7 || parts.size == 9)
                PreviewLayoutUpdate(mapOf(name(unescape(parts[1])) to DiagramPoint(number(parts[2]), number(parts[3]))), view = view(4))
            }
            "R" -> {
                require(parts.size == 9 || parts.size == 11)
                val id = name(unescape(parts[1]))
                PreviewLayoutUpdate(routes = mapOf(id to ManualRelationRoute(side(parts[2]), side(parts[3]),
                    DiagramPoint(number(parts[4]), number(parts[5])))), view = view(6, id))
            }
            "B" -> {
                require(parts.size == 2)
                val batch = JsonParser.parseString(unescape(parts[1])).asJsonObject
                val tables = batch.getAsJsonArray("tables")
                val relations = batch.getAsJsonArray("routes")
                require(tables.size() in 1..10_000 && relations.size() <= 10_000)
                val positions = linkedMapOf<String, DiagramPoint>()
                tables.forEach { item ->
                    val table = item.asJsonObject
                    val id = name(table["id"].asString)
                    require(positions.put(id, DiagramPoint(number(table["x"].asString), number(table["y"].asString))) == null)
                }
                val routes = linkedMapOf<String, ManualRelationRoute>()
                relations.forEach { item ->
                    val route = item.asJsonObject
                    val id = name(route["id"].asString)
                    require(routes.put(id, ManualRelationRoute(side(route["fromSide"].asString), side(route["toSide"].asString),
                        DiagramPoint(number(route["x"].asString), number(route["y"].asString)))) == null)
                }
                val camera = batch.getAsJsonObject("view")
                require(camera["grid"].asJsonPrimitive.isBoolean && camera["snap"].asJsonPrimitive.isBoolean)
                PreviewLayoutUpdate(positions, routes, PreviewViewState(number(camera["scale"].asString).coerceIn(.01, 4.0),
                    number(camera["x"].asString), number(camera["y"].asString),
                    grid = camera["grid"].asBoolean, snap = camera["snap"].asBoolean))
            }
            else -> error("Unknown preview operation")
        }
    }.getOrNull()
}
