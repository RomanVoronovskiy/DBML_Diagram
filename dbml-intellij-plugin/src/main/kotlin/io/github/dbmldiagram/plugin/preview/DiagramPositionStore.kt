package io.github.dbmldiagram.plugin.preview

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dbmldiagram.core.layout.DiagramPoint
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Stores personal diagram coordinates in the project's workspace settings. */
internal class DiagramPositionStore(project: Project, file: VirtualFile) {
    private val properties = PropertiesComponent.getInstance(project)
    private val key = "io.github.dbmldiagram.layout.${sha256(file.url).take(24)}"

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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
