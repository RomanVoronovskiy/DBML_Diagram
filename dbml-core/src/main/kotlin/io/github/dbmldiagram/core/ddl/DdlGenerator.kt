package io.github.dbmldiagram.core.ddl

import io.github.dbmldiagram.core.model.DbmlSchema

fun interface DdlGenerator {
    fun generate(schema: DbmlSchema): String
}
