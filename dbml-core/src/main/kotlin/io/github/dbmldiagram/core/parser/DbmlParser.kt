package io.github.dbmldiagram.core.parser

import io.github.dbmldiagram.core.model.ParseResult

fun interface DbmlParser {
    fun parse(text: String): ParseResult
}
