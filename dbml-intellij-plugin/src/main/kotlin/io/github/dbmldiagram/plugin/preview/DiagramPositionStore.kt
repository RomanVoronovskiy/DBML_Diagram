package io.github.dbmldiagram.plugin.preview

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dbmldiagram.core.layout.DiagramPoint
import io.github.dbmldiagram.core.layout.ManualRelationRoute
import io.github.dbmldiagram.core.layout.TableSide
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Stores personal table positions and relationship routes in the project's workspace settings. */
internal class DiagramPositionStore(project: Project, file: VirtualFile) {
    private val properties = PropertiesComponent.getInstance(project)
    private val key = "io.github.dbmldiagram.layout.${sha256(file.url).take(24)}"
    private val routeKey = "$key.routes"

    fun load(): Map<String, DiagramPoint> = properties.getValue(key)
        ?.lineSequence()
        ?.mapNotNull(::decode)
        ?.toMap()
        .orEmpty()

    fun save(positions: Map<String, DiagramPoint>) {
        val value = positions.entries
            .asSequence()
            .filter { it.value.x.isFinite() && it.value.y.isFinite() }
            .sortedBy { it.key.lowercase() }
            .joinToString("\n") { (table, point) ->
                "${encodeName(table)}\t${point.x}\t${point.y}"
            }
        properties.setValue(key, value.ifBlank { null })
    }

    fun clear() = properties.unsetValue(key)

    fun loadRoutes(): Map<String, ManualRelationRoute> = properties.getValue(routeKey)
        ?.lineSequence()
        ?.mapNotNull(::decodeRoute)
        ?.toMap()
        .orEmpty()

    fun saveRoutes(routes: Map<String, ManualRelationRoute>) {
        val value = routes.entries
            .asSequence()
            .filter { it.value.control.x.isFinite() && it.value.control.y.isFinite() }
            .sortedBy { it.key }
            .joinToString("\n") { (relation, route) ->
                "${encodeName(relation)}\t${route.fromSide.name}\t${route.toSide.name}\t${route.control.x}\t${route.control.y}"
            }
        properties.setValue(routeKey, value.ifBlank { null })
    }

    fun clearRoutes() = properties.unsetValue(routeKey)

    private fun decode(line: String): Pair<String, DiagramPoint>? {
        val parts = line.split('\t')
        if (parts.size != 3) return null
        val table = runCatching {
            String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val x = parts[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        val y = parts[2].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        return table to DiagramPoint(x, y)
    }

    private fun encodeName(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeRoute(line: String): Pair<String, ManualRelationRoute>? {
        val parts = line.split('\t')
        if (parts.size != 5) return null
        val relation = runCatching {
            String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val fromSide = runCatching { TableSide.valueOf(parts[1]) }.getOrNull() ?: return null
        val toSide = runCatching { TableSide.valueOf(parts[2]) }.getOrNull() ?: return null
        val x = parts[3].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        val y = parts[4].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        return relation to ManualRelationRoute(fromSide, toSide, DiagramPoint(x, y))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
