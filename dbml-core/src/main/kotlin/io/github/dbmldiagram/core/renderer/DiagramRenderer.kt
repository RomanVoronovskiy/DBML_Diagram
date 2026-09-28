package io.github.dbmldiagram.core.renderer

import io.github.dbmldiagram.core.layout.DiagramLayout
import io.github.dbmldiagram.core.model.DbmlSchema

fun interface DiagramRenderer {
    fun render(schema: DbmlSchema, layout: DiagramLayout): String
}
