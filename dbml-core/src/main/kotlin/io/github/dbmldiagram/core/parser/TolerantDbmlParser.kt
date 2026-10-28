package io.github.dbmldiagram.core.parser

import io.github.dbmldiagram.core.model.*
import io.github.dbmldiagram.core.validation.ReferenceValidator

class TolerantDbmlParser : DbmlParser {
    override fun parse(text: String): ParseResult {
        val lexer = Lexer(text)
        val tokens = lexer.lex()
        return Parser(tokens, lexer.errors).parse()
    }
}

private enum class Kind { WORD, STRING, SYMBOL, NEWLINE, EOF }
private data class Token(
    val kind: Kind, val text: String, val line: Int, val column: Int, val quote: Char? = null,
    val endLine: Int = line, val endColumn: Int = column + text.length,
)
private fun Token.isSymbol(value: String) = kind == Kind.SYMBOL && text == value

private class Lexer(private val source: String) {
    val errors = mutableListOf<DbmlParseError>()
    private val tokens = mutableListOf<Token>()
    private var offset = 0
    private var line = 1
    private var column = 1

    fun lex(): List<Token> {
        while (offset < source.length) {
            when {
                source[offset] == '\r' -> advance()
                source[offset] == '\n' -> { tokens += Token(Kind.NEWLINE, "\n", line, column); advanceLine() }
                source[offset].isWhitespace() -> advance()
                startsWith("//") -> skipLineComment()
                startsWith("/*") -> skipBlockComment()
                source[offset] in charArrayOf('\'', '"', '`') -> quoted()
                source[offset].isLetterOrDigit() || source[offset] == '_' || source[offset] == '$' -> word()
                startsWith("<>") -> symbol("<>", 2)
                else -> symbol(source[offset].toString(), 1)
            }
        }
        tokens += Token(Kind.EOF, "", line, column)
        return tokens
    }

    private fun startsWith(value: String) = source.startsWith(value, offset)
    private fun advance() { offset++; column++ }
    private fun advanceLine() { offset++; line++; column = 1 }
    private fun skipLineComment() { while (offset < source.length && source[offset] != '\n') advance() }
    private fun skipBlockComment() {
        val startLine = line; val startColumn = column
        offset += 2; column += 2
        while (offset < source.length && !startsWith("*/")) {
            if (source[offset] == '\n') advanceLine() else advance()
        }
        if (offset >= source.length) errors += DbmlParseError("Unterminated block comment", startLine, startColumn, DbmlParseSeverity.ERROR)
        else { offset += 2; column += 2 }
    }
    private fun quoted() {
        val quote = source[offset]; val startLine = line; val startColumn = column
        val delimiter = if (quote == '\'' && startsWith("'''")) "'''" else quote.toString()
        repeat(delimiter.length) { advance() }
        val value = StringBuilder()
        while (offset < source.length && !startsWith(delimiter)) {
            if (source[offset] == '\\' && offset + 1 < source.length) {
                advance()
                if (source[offset] == '\r' && source.getOrNull(offset + 1) == '\n') advance()
                if (source[offset] == '\n') advanceLine()
                else { value.append(source[offset]); advance() }
            } else if (source[offset] == '\n') {
                value.append('\n'); advanceLine()
            } else if (source[offset] == '\r' && source.getOrNull(offset + 1) == '\n') {
                advance()
            } else { value.append(source[offset]); advance() }
        }
        if (offset >= source.length) errors += DbmlParseError("Unterminated quoted value", startLine, startColumn, DbmlParseSeverity.ERROR)
        else repeat(delimiter.length) { advance() }
        val text = if (delimiter.length == 3) value.toString().trimIndent() else value.toString()
        tokens += Token(Kind.STRING, text, startLine, startColumn, quote, line, column)
    }
    private fun word() {
        val start = offset; val startColumn = column
        while (offset < source.length && (source[offset].isLetterOrDigit() || source[offset] in "_-$")) advance()
        tokens += Token(Kind.WORD, source.substring(start, offset), line, startColumn)
    }
    private fun symbol(value: String, length: Int) {
        tokens += Token(Kind.SYMBOL, value, line, column)
        repeat(length) { advance() }
    }
}

private class Parser(private val tokens: List<Token>, initialErrors: List<DbmlParseError>) {
    private var position = 0
    private val errors = initialErrors.toMutableList()
    private val tables = mutableListOf<DbmlTable>()
    private val enums = mutableListOf<DbmlEnum>()
    private val references = mutableListOf<DbmlReference>()
    private val tableGroups = mutableListOf<DbmlTableGroup>()
    private val notes = mutableListOf<DbmlNote>()
    private var project: DbmlProject? = null

    fun parse(): ParseResult {
        while (!atEnd()) {
            skipNewlines()
            if (atEnd()) break
            val before = position
            when {
                matchWord("Project") -> parseProject()
                matchWord("Table") -> parseTable()
                matchWord("Enum") -> parseEnum()
                matchWord("Ref") -> parseReference()
                matchWord("TableGroup") -> parseTableGroup()
                matchWord("Note") -> parseStickyNote()
                checkSymbol("}") -> { error("Unexpected closing '}'", peek()); advance() }
                else -> skipUnsupportedDeclaration()
            }
            // Recovery must always consume input, even for a future unsupported construct.
            // In particular, skipLine() intentionally leaves '}' for its enclosing block.
            if (position == before) {
                error("Unexpected token '${peek().text.take(80)}'", peek())
                advance()
            }
        }
        val schema = DbmlSchema(project, tables.toList(), enums.toList(), references.toList(), tableGroups.toList(), notes.toList())
        validate(schema)
        return ParseResult(schema, errors.toList())
    }

    private fun parseStickyNote() {
        val start = peek()
        val name = identifierOrNull() ?: run { error("Expected Note name", peek()); skipLine(); return }
        skipNewlines()
        val settings = if (consumeSymbol("[")) collectUntilClosingBracket() else emptyList()
        val color = settingSegments(settings).firstOrNull { it.firstOrNull()?.text.equals("color", true) }
            ?.drop(2)?.joinToString("") { it.text }
        val safeColor = color?.takeIf { it == "none" || Regex("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?").matches(it) }
        if (color != null && safeColor == null) warning("Invalid Note color; using default yellow", start)
        skipNewlines()
        if (!consumeSymbol("{")) { error("Expected '{' after Note name", peek()); skipLine(); return }
        skipNewlines()
        val text = if (peek().kind == Kind.STRING) advance().text else {
            error("Expected quoted Note text", peek()); ""
        }
        skipNewlines()
        closeBlock("Note")
        if (notes.any { it.name.equals(name, true) }) error("Duplicate Note '$name'", start)
        else notes += DbmlNote(name, text, safeColor)
    }

    private fun parseTableGroup() {
        val start = peek()
        val name = parseQualifiedName() ?: run { error("Expected TableGroup name", peek()); skipLine(); return }
        skipNewlines()
        val settings = if (consumeSymbol("[")) collectUntilClosingBracket().filter { it.kind != Kind.NEWLINE } else emptyList()
        skipNewlines()
        if (!consumeSymbol("{")) { error("Expected '{' after TableGroup name", peek()); skipLine(); return }
        val members = mutableListOf<DbmlTableRef>()
        var note = settingValue(settings, "note")
        val colorSetting = settingSegments(settings).firstOrNull { it.firstOrNull()?.text.equals("color", true) }
        val color = colorSetting?.drop(2)?.joinToString("") { it.text }
        val safeColor = color?.takeIf { colorSetting.getOrNull(1)?.isSymbol(":") == true && Regex("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?").matches(it) }
        if (colorSetting != null && (safeColor == null || colorSetting.getOrNull(1)?.isSymbol(":") != true)) {
            warning("Invalid TableGroup color; using the default palette (expected #rgb or #rrggbb)", colorSetting.first())
        }
        while (!atEnd() && !checkSymbol("}")) {
            skipNewlines(); if (checkSymbol("}") || atEnd()) break
            if (matchWord("Note")) {
                if (consumeSymbol(":")) note = collectLine(stopAtBrace = true).joinToString(" ") { it.text }
                else if (consumeSymbol("{")) {
                    skipNewlines()
                    note = collectLine(stopAtBrace = true).joinToString(" ") { it.text }
                    skipNewlines()
                    closeBlock("TableGroup Note")
                } else { error("Expected ':' or '{' after TableGroup Note", peek()); skipLine() }
            } else {
                val memberStart = peek()
                val member = parseQualifiedName()
                if (member == null) {
                    error("Expected table name in TableGroup", peek())
                    advance()
                } else members += DbmlTableRef(member.second, member.first, sourceRange(listOf(memberStart, tokens[position - 1])))
            }
        }
        if (!consumeSymbol("}")) error("Unterminated TableGroup ${name.second} block", start)
        else skipNewlines()
        if (tableGroups.any { it.qualifiedName.equals(name.first?.let { schema -> "$schema.${name.second}" } ?: name.second, true) }) {
            error("Duplicate TableGroup '${name.second}'", start)
        }
        tableGroups += DbmlTableGroup(name.second, members, safeColor, note, name.first, sourceRange(listOf(start)))
    }

    /** Ignore visualization/unknown declarations without parsing their contents as schema. */
    private fun skipUnsupportedDeclaration(requireBlock: Boolean = false) {
        val start = peek()
        warning("Unsupported declaration '${start.text.take(80)}' is ignored in the local preview", start)
        while (!atEnd() && peek().kind != Kind.NEWLINE && !checkSymbol("{") && !checkSymbol("}")) advance()
        skipNewlines()
        if (!consumeSymbol("{")) {
            if (requireBlock) error("Expected '{' after ${start.text}", start)
            return
        }
        var depth = 1
        while (!atEnd() && depth > 0) {
            val token = advance()
            if (token.isSymbol("{")) depth++
            if (token.isSymbol("}")) depth--
        }
        if (depth > 0) error("Unterminated ${start.text.take(80)} block", start)
    }

    private fun parseProject() {
        val name = identifierOrNull()
        skipNewlines()
        if (!consumeSymbol("{")) { error("Expected '{' after Project", peek()); skipLine(); return }
        val properties = linkedMapOf<String, String>()
        while (!atEnd() && !checkSymbol("}")) {
            skipNewlines(); if (checkSymbol("}")) break
            val key = identifierOrNull()
            if (key == null) { skipLine(); continue }
            consumeSymbol(":")
            properties[key] = collectLine(stopAtBrace = true).joinToString(" ") { it.text }
        }
        closeBlock("Project")
        project = DbmlProject(name, properties)
    }

    private fun parseTable() {
        val header = parseQualifiedName() ?: run { error("Expected table name", peek()); skipLine(); return }
        var alias: String? = null
        if (matchWord("as")) alias = identifierOrNull()
        val headerSettings = if (consumeSymbol("[")) collectUntilClosingBracket() else emptyList()
        val headerNote = settingValue(headerSettings, "note")
        skipNewlines()
        if (!consumeSymbol("{")) { error("Expected '{' after table name", peek()); skipLine(); return }
        val columns = mutableListOf<DbmlColumn>()
        val indexes = mutableListOf<DbmlIndex>()
        val checks = mutableListOf<DbmlCheck>()
        var blockNote: String? = null
        while (!atEnd() && !checkSymbol("}")) {
            skipNewlines(); if (checkSymbol("}")) break
            when {
                matchWord("indexes") -> indexes += parseIndexes()
                matchWord("checks") -> checks += parseChecks()
                matchWord("Note") -> { consumeSymbol(":"); blockNote = collectLine(stopAtBrace = true).joinToString(" ") { it.text } }
                else -> parseColumn(header, columns)?.let { (column, inlineRefs) ->
                    columns += column
                    references += inlineRefs
                }
            }
        }
        closeBlock("Table ${header.second}")
        val checkNames = mutableSetOf<String>()
        (checks + columns.flatMap { it.checks }).forEach { check ->
            if (check.name != null && !checkNames.add(check.name.lowercase())) {
                val range = check.sourceRange
                errors += DbmlParseError("Duplicate CHECK constraint name '${check.name}'", range?.line ?: 1,
                    range?.column ?: 1, DbmlParseSeverity.ERROR)
            }
        }
        tables += DbmlTable(header.first, header.second, alias, headerNote ?: blockNote, columns, indexes, checks)
    }

    private fun parseColumn(table: Pair<String?, String>, existing: List<DbmlColumn>): Pair<DbmlColumn, List<DbmlReference>>? {
        val start = peek()
        val name = identifierOrNull() ?: run { warning("Expected column name", peek()); skipLine(); return null }
        val typeTokens = mutableListOf<Token>()
        var parentheses = 0
        while (!atEnd() && !(peek().kind == Kind.NEWLINE && parentheses == 0) && !checkSymbol("[") && !checkSymbol("}")) {
            if (checkSymbol("(")) parentheses++
            if (checkSymbol(")")) parentheses--
            typeTokens += advance()
        }
        val type = compact(typeTokens)
        val settings = if (consumeSymbol("[")) collectUntilClosingBracket() else emptyList()
        if (type.isBlank()) error("Column '$name' has no type", start)
        skipLine()
        val primaryKey = hasSetting(settings, "pk") || hasSetting(settings, "primary key")
        val notNull = hasSetting(settings, "not null")
        val explicitlyNull = hasSetting(settings, "null")
        val column = DbmlColumn(
            name = name, type = type.ifBlank { "?" }, primaryKey = primaryKey,
            nullable = explicitlyNull || !notNull, unique = hasSetting(settings, "unique"),
            increment = hasSetting(settings, "increment"), defaultValue = defaultValue(settings),
            note = settingValue(settings, "note"),
            checks = settingSegments(settings).filter { it.firstOrNull()?.text.equals("check", true) }.mapNotNull { segment ->
                if (segment.getOrNull(1)?.isSymbol(":") != true) {
                    error("Expected ':' after check", segment.first())
                    return@mapNotNull null
                }
                val expression = segment.drop(2)
                parseCheckExpression(expression, emptyList(), segment.first())
            },
        )
        if (existing.any { it.name.equals(name, true) }) warning("Duplicate column '$name' in table '${table.second}'", start)
        val inline = settingSegments(settings).mapNotNull { parseInlineReference(it, table, name) }
        return column to inline
    }

    private fun parseChecks(): List<DbmlCheck> {
        skipNewlines()
        if (!consumeSymbol("{")) { error("Expected '{' after checks", peek()); skipLine(); return emptyList() }
        val checks = mutableListOf<DbmlCheck>()
        while (!atEnd() && !checkSymbol("}")) {
            skipNewlines(); if (checkSymbol("}") || atEnd()) break
            val start = advance()
            val settings = if (consumeSymbol("[")) collectUntilClosingBracket() else emptyList()
            parseCheckExpression(listOf(start), settings, start)?.let(checks::add)
            if (!atEnd() && peek().kind != Kind.NEWLINE && !checkSymbol("}")) {
                error("Unexpected token after CHECK expression", peek())
                skipLine()
            }
        }
        closeBlock("checks")
        return checks
    }

    private fun parseCheckExpression(expression: List<Token>, settings: List<Token>, start: Token): DbmlCheck? {
        val value = expression.singleOrNull()
        if (value == null || value.kind != Kind.STRING || value.quote != '`' || value.text.isBlank()) {
            error("CHECK requires a nonempty backtick-quoted SQL expression", start)
            return null
        }
        return DbmlCheck(value.text, settingValue(settings, "name"), sourceRange(listOf(start) + expression + settings))
    }

    private fun parseIndexes(): List<DbmlIndex> {
        skipNewlines()
        if (!consumeSymbol("{")) { error("Expected '{' after indexes", peek()); skipLine(); return emptyList() }
        val result = mutableListOf<DbmlIndex>()
        while (!atEnd() && !checkSymbol("}")) {
            skipNewlines(); if (checkSymbol("}")) break
            val line = collectLine(stopAtBrace = true)
            if (line.isEmpty()) continue
            val settingsStart = line.indexOfFirst { it.isSymbol("[") }
            val expression = if (settingsStart >= 0) line.take(settingsStart) else line
            val settings = if (settingsStart >= 0) line.drop(settingsStart + 1).dropLastWhile { it.isSymbol("]") } else emptyList()
            val names = expression.filter { it.kind == Kind.WORD || it.kind == Kind.STRING }.map { it.text }
            if (names.isNotEmpty()) result += DbmlIndex(
                columns = names,
                unique = hasSetting(settings, "unique"),
                primaryKey = hasSetting(settings, "pk") || hasSetting(settings, "primary key"),
                name = settingValue(settings, "name"),
            )
        }
        closeBlock("indexes")
        return result
    }

    private fun parseEnum() {
        val name = identifierOrNull() ?: run { error("Expected enum name", peek()); skipLine(); return }
        skipNewlines()
        if (!consumeSymbol("{")) { error("Expected '{' after Enum", peek()); skipLine(); return }
        val values = mutableListOf<String>()
        while (!atEnd() && !checkSymbol("}")) {
            skipNewlines(); if (checkSymbol("}")) break
            identifierOrNull()?.let(values::add)
            skipLine()
        }
        closeBlock("Enum $name")
        enums += DbmlEnum(name, values)
    }

    private fun parseReference() {
        var name: String? = null
        if (!checkSymbol(":") && !checkSymbol("{")) name = identifierOrNull()
        skipNewlines()
        when {
            consumeSymbol(":") -> parseReferenceExpression(name, collectLine())?.let(references::add)
            consumeSymbol("{") -> {
                val body = mutableListOf<Token>()
                while (!atEnd() && !checkSymbol("}")) body += advance()
                closeBlock("Ref")
                parseReferenceExpression(name, body.filter { it.kind != Kind.NEWLINE })?.let(references::add)
            }
            else -> { error("Expected ':' or '{' after Ref", peek()); skipLine() }
        }
    }

    private fun parseReferenceExpression(name: String?, expression: List<Token>): DbmlReference? {
        val settingsStart = expression.indexOfFirst { it.isSymbol("[") }
        val endpoints = if (settingsStart >= 0) expression.take(settingsStart) else expression
        val settings = if (settingsStart >= 0) {
            expression.drop(settingsStart + 1).dropLastWhile { it.isSymbol("]") }
        } else {
            emptyList()
        }
        val opIndex = endpoints.indexOfFirst { it.kind == Kind.SYMBOL && it.text in setOf(">", "<", "-", "<>") }
        if (opIndex < 0) { endpoints.firstOrNull()?.let { error("Reference operator is missing", it) }; return null }
        // New official converters include optional-side markers next to the operator (e.g. <?).
        // Physical nullability is retained on the FK column; markers do not alter key validation.
        val left = parseColumnRef(endpoints.take(opIndex).dropLastWhile { it.isSymbol("?") })
        val right = parseColumnRef(endpoints.drop(opIndex + 1).dropWhile { it.isSymbol("?") })
        if (left == null || right == null) { error("Invalid reference endpoint", endpoints.first()); return null }
        val cardinality = when (endpoints[opIndex].text) {
            ">" -> DbmlCardinality.MANY_TO_ONE
            "<" -> DbmlCardinality.ONE_TO_MANY
            "-" -> DbmlCardinality.ONE_TO_ONE
            else -> DbmlCardinality.MANY_TO_MANY
        }
        return DbmlReference(
            from = left,
            to = right,
            cardinality = cardinality,
            name = name,
            onDelete = settingValue(settings, "delete"),
            onUpdate = settingValue(settings, "update"),
            sourceRange = sourceRange(endpoints),
        )
    }

    private fun parseInlineReference(settings: List<Token>, table: Pair<String?, String>, column: String): DbmlReference? {
        val refIndex = settings.indexOfFirst { it.kind == Kind.WORD && it.text.equals("ref", true) }
        if (refIndex < 0) return null
        val tail = settings.drop(refIndex + 1).dropWhile { it.isSymbol(":") }
        val opIndex = tail.indexOfFirst { it.kind == Kind.SYMBOL && it.text in setOf(">", "<", "-", "<>") }
        if (opIndex < 0) { error("Reference operator is missing", settings[refIndex]); return null }
        val target = parseColumnRef(tail.drop(opIndex + 1).dropWhile { it.isSymbol("?") })
            ?: run { error("Invalid reference endpoint", settings[refIndex]); return null }
        val source = DbmlColumnRef(table.first, table.second, column)
        val cardinality = when (tail[opIndex].text) {
            ">" -> DbmlCardinality.MANY_TO_ONE
            "<" -> DbmlCardinality.ONE_TO_MANY
            "-" -> DbmlCardinality.ONE_TO_ONE
            else -> DbmlCardinality.MANY_TO_MANY
        }
        return DbmlReference(
            from = source,
            to = target,
            cardinality = cardinality,
            onDelete = settingValue(settings, "delete"),
            onUpdate = settingValue(settings, "update"),
            inline = true,
            sourceRange = sourceRange(settings.drop(refIndex)),
        )
    }

    private fun parseColumnRef(tokens: List<Token>): DbmlColumnRef? {
        var cursor = 0
        fun identifier(): String? = tokens.getOrNull(cursor)?.takeIf {
            it.kind == Kind.WORD || (it.kind == Kind.STRING && it.quote != '`')
        }?.also { cursor++ }?.text
        fun symbol(value: String): Boolean = if (tokens.getOrNull(cursor)?.isSymbol(value) == true) { cursor++; true } else false
        val parts = mutableListOf(identifier() ?: return null)
        while (symbol(".")) {
            if (symbol("(")) {
                if (parts.size !in 1..2) return null
                val columns = mutableListOf(identifier() ?: return null)
                while (symbol(",")) columns += identifier() ?: return null
                if (!symbol(")") || cursor != tokens.size) return null
                return DbmlColumnRef(parts.takeIf { it.size == 2 }?.first(), parts.last(), columns.first(), columns.drop(1))
            }
            parts += identifier() ?: return null
        }
        if (cursor != tokens.size) return null
        return when (parts.size) {
            2 -> DbmlColumnRef(table = parts[0], column = parts[1])
            3 -> DbmlColumnRef(schema = parts[0], table = parts[1], column = parts[2])
            else -> null
        }
    }

    private fun validate(schema: DbmlSchema) {
        val seen = mutableSetOf<String>()
        schema.tables.forEach { table ->
            if (!seen.add(table.qualifiedName.lowercase())) warning("Duplicate table '${table.qualifiedName}'", Token(Kind.WORD, table.name, 1, 1))
        }
        errors += ReferenceValidator().validate(schema)
        val owners = mutableMapOf<String, String>()
        schema.tableGroups.forEach { group ->
            val members = mutableSetOf<String>()
            group.tables.forEach { reference ->
                val table = reference.resolve(schema.tables)
                val range = reference.sourceRange ?: group.sourceRange
                val token = Token(Kind.WORD, reference.tableName, range?.line ?: 1, range?.column ?: 1)
                if (table == null) warning("Unknown TableGroup member '${reference.tableName}' in '${group.qualifiedName}'", token)
                else {
                    val id = table.qualifiedName.lowercase()
                    if (!members.add(id)) warning("Duplicate member '${reference.tableName}' in TableGroup '${group.qualifiedName}'", token)
                    val previous = owners.putIfAbsent(id, group.qualifiedName)
                    if (previous != null && previous != group.qualifiedName) warning("Table '${table.qualifiedName}' belongs to multiple groups; auto-layout uses '$previous' and frames may overlap", token)
                }
            }
            if (members.isEmpty()) {
                val range = group.sourceRange
                warning("TableGroup '${group.qualifiedName}' has no resolved tables; no frame is drawn", Token(Kind.WORD, group.name, range?.line ?: 1, range?.column ?: 1))
            }
        }
    }

    private fun sourceRange(items: List<Token>): DbmlSourceRange? {
        val first = items.firstOrNull() ?: return null
        val last = items.last()
        return DbmlSourceRange(first.line, first.column, last.endLine, last.endColumn)
    }

    private fun parseQualifiedName(): Pair<String?, String>? {
        val first = identifierOrNull() ?: return null
        return if (consumeSymbol(".")) {
            val second = identifierOrNull() ?: run { error("Expected name after '.'", peek()); return null }
            first to second
        } else null to first
    }

    private fun collectUntilClosingBracket(): List<Token> {
        val result = mutableListOf<Token>(); var depth = 1
        while (!atEnd() && depth > 0) {
            val token = advance()
            if (token.isSymbol("[")) depth++
            if (token.isSymbol("]")) depth--
            if (depth > 0) result += token
        }
        if (depth > 0) error("Unterminated settings block", peek())
        return result
    }

    private fun compact(items: List<Token>): String = buildString {
        items.forEachIndexed { index, token ->
            if (index > 0 && token.text !in setOf("(", ")", ",") && items[index - 1].text !in setOf("(", ".")) append(' ')
            append(token.text)
        }
    }.replace(" ,", ",")

    private fun settingSegments(items: List<Token>): List<List<Token>> {
        val result = mutableListOf<MutableList<Token>>(mutableListOf()); var depth = 0
        items.forEach { token ->
            if (token.isSymbol("(")) depth++
            if (token.isSymbol(")")) depth--
            if (token.isSymbol(",") && depth == 0) result.add(mutableListOf()) else result.last().add(token)
        }
        return result
    }
    private fun hasSetting(items: List<Token>, key: String) = settingSegments(items).any { segment ->
        segment.takeWhile { !it.isSymbol(":") }.joinToString(" ") { it.text }.trim().equals(key, true)
    }
    private fun settingValue(items: List<Token>, key: String): String? = settingSegments(items).firstNotNullOfOrNull { segment ->
        val colon = segment.indexOfFirst { it.isSymbol(":") }
        if (colon > 0 && segment.take(colon).joinToString(" ") { it.text }.trim().equals(key, true))
            compact(segment.drop(colon + 1)).trim().ifBlank { null } else null
    }

    private fun defaultValue(items: List<Token>): String? {
        val segment = settingSegments(items).firstOrNull { candidate ->
            val colon = candidate.indexOfFirst { it.isSymbol(":") }
            colon > 0 && candidate.take(colon).joinToString(" ") { it.text }.trim().equals("default", true)
        } ?: return null
        val colon = segment.indexOfFirst { it.isSymbol(":") }
        val valueTokens = segment.drop(colon + 1)
        if (valueTokens.isEmpty()) return null
        return buildString {
            valueTokens.forEachIndexed { index, token ->
                if (index > 0 && token.text !in setOf(")", ",") && valueTokens[index - 1].text !in setOf("(", ".")) append(' ')
                when (token.quote) {
                    '`' -> append(token.text)
                    '\'', '"' -> append('\'').append(token.text.replace("'", "''")).append('\'')
                    else -> append(token.text)
                }
            }
        }.trim().ifBlank { null }
    }

    private fun collectLine(stopAtBrace: Boolean = false): List<Token> {
        val result = mutableListOf<Token>()
        while (!atEnd() && peek().kind != Kind.NEWLINE && !(stopAtBrace && checkSymbol("}"))) result += advance()
        if (peek().kind == Kind.NEWLINE) advance()
        return result
    }
    private fun closeBlock(name: String) {
        if (!consumeSymbol("}")) error("Unterminated $name block", peek())
        else if (peek().kind == Kind.NEWLINE) advance()
    }
    private fun identifierOrNull(): String? = if (peek().kind == Kind.WORD || peek().kind == Kind.STRING) advance().text else null
    private fun checkWord(value: String) = peek().kind == Kind.WORD && peek().text.equals(value, true)
    private fun matchWord(value: String): Boolean = if (checkWord(value)) { advance(); true } else false
    private fun checkSymbol(value: String) = peek().isSymbol(value)
    private fun consumeSymbol(value: String): Boolean = if (checkSymbol(value)) { advance(); true } else false
    private fun skipNewlines() { while (peek().kind == Kind.NEWLINE) advance() }
    private fun skipLine() { while (!atEnd() && peek().kind != Kind.NEWLINE && !checkSymbol("}")) advance(); if (peek().kind == Kind.NEWLINE) advance() }
    private fun peek() = tokens[position]
    private fun advance(): Token = tokens[position].also { if (position < tokens.lastIndex) position++ }
    private fun atEnd() = peek().kind == Kind.EOF
    private fun error(message: String, token: Token) { errors += DbmlParseError(message, token.line, token.column, DbmlParseSeverity.ERROR) }
    private fun warning(message: String, token: Token) { errors += DbmlParseError(message, token.line, token.column, DbmlParseSeverity.WARNING) }
}
