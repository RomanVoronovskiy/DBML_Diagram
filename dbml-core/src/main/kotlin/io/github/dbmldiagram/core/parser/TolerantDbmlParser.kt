package io.github.dbmldiagram.core.parser

import io.github.dbmldiagram.core.model.*

class TolerantDbmlParser : DbmlParser {
    override fun parse(text: String): ParseResult {
        val lexer = Lexer(text)
        val tokens = lexer.lex()
        return Parser(tokens, lexer.errors).parse()
    }
}

private enum class Kind { WORD, STRING, SYMBOL, NEWLINE, EOF }
private data class Token(val kind: Kind, val text: String, val line: Int, val column: Int)

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
        advance(); val value = StringBuilder()
        while (offset < source.length && source[offset] != quote) {
            if (source[offset] == '\\' && offset + 1 < source.length) {
                advance(); value.append(source[offset]); advance()
            } else if (source[offset] == '\n') {
                value.append('\n'); advanceLine()
            } else { value.append(source[offset]); advance() }
        }
        if (offset >= source.length) errors += DbmlParseError("Unterminated quoted value", startLine, startColumn, DbmlParseSeverity.ERROR)
        else advance()
        tokens += Token(Kind.STRING, value.toString(), startLine, startColumn)
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
    private var project: DbmlProject? = null

    fun parse(): ParseResult {
        while (!atEnd()) {
            skipNewlines()
            when {
                matchWord("Project") -> parseProject()
                matchWord("Table") -> parseTable()
                matchWord("Enum") -> parseEnum()
                matchWord("Ref") -> parseReference()
                atEnd() -> break
                else -> { warning("Unexpected top-level token '${peek().text}'", peek()); skipLine() }
            }
        }
        val schema = DbmlSchema(project, tables.toList(), enums.toList(), references.toList())
        validate(schema)
        return ParseResult(schema, errors.toList())
    }

    private fun parseProject() {
        val name = identifierOrNull()
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
        if (!consumeSymbol("{")) { error("Expected '{' after table name", peek()); skipLine(); return }
        val columns = mutableListOf<DbmlColumn>()
        val indexes = mutableListOf<DbmlIndex>()
        var blockNote: String? = null
        while (!atEnd() && !checkSymbol("}")) {
            skipNewlines(); if (checkSymbol("}")) break
            when {
                matchWord("indexes") -> indexes += parseIndexes()
                matchWord("Note") -> { consumeSymbol(":"); blockNote = collectLine().joinToString(" ") { it.text } }
                else -> parseColumn(header, columns)?.let { (column, inlineRef) ->
                    columns += column
                    inlineRef?.let(references::add)
                }
            }
        }
        closeBlock("Table ${header.second}")
        tables += DbmlTable(header.first, header.second, alias, headerNote ?: blockNote, columns, indexes)
    }

    private fun parseColumn(table: Pair<String?, String>, existing: List<DbmlColumn>): Pair<DbmlColumn, DbmlReference?>? {
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
            increment = hasSetting(settings, "increment"), defaultValue = settingValue(settings, "default"),
            note = settingValue(settings, "note"),
        )
        if (existing.any { it.name.equals(name, true) }) warning("Duplicate column '$name' in table '${table.second}'", start)
        val inline = parseInlineReference(settings, table, name)
        return column to inline
    }

    private fun parseIndexes(): List<DbmlIndex> {
        if (!consumeSymbol("{")) { error("Expected '{' after indexes", peek()); skipLine(); return emptyList() }
        val result = mutableListOf<DbmlIndex>()
        while (!atEnd() && !checkSymbol("}")) {
            skipNewlines(); if (checkSymbol("}")) break
            val line = collectLine(stopAtBrace = true)
            if (line.isEmpty()) continue
            val settingsStart = line.indexOfFirst { it.text == "[" }
            val expression = if (settingsStart >= 0) line.take(settingsStart) else line
            val settings = if (settingsStart >= 0) line.drop(settingsStart + 1).dropLastWhile { it.text == "]" } else emptyList()
            val names = expression.filter { it.kind == Kind.WORD || it.kind == Kind.STRING }.map { it.text }
            if (names.isNotEmpty()) result += DbmlIndex(names, hasSetting(settings, "unique"), settingValue(settings, "name"))
        }
        closeBlock("indexes")
        return result
    }

    private fun parseEnum() {
        val name = identifierOrNull() ?: run { error("Expected enum name", peek()); skipLine(); return }
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
        // Reference actions (`[delete: cascade, update: no action]`) describe the
        // relation, not its right endpoint. They are intentionally ignored by the
        // MVP model, but must not make an otherwise valid reference fail parsing.
        val endpoints = expression.takeWhile { it.text != "[" }
        val opIndex = endpoints.indexOfFirst { it.text in setOf(">", "<", "-", "<>") }
        if (opIndex < 0) { endpoints.firstOrNull()?.let { error("Reference operator is missing", it) }; return null }
        val left = parseColumnRef(endpoints.take(opIndex))
        val right = parseColumnRef(endpoints.drop(opIndex + 1))
        if (left == null || right == null) { error("Invalid reference endpoint", endpoints.first()); return null }
        val cardinality = when (endpoints[opIndex].text) {
            ">" -> DbmlCardinality.MANY_TO_ONE
            "<" -> DbmlCardinality.ONE_TO_MANY
            "-" -> DbmlCardinality.ONE_TO_ONE
            else -> DbmlCardinality.MANY_TO_MANY
        }
        return DbmlReference(left, right, cardinality, name)
    }

    private fun parseInlineReference(settings: List<Token>, table: Pair<String?, String>, column: String): DbmlReference? {
        val refIndex = settings.indexOfFirst { it.text.equals("ref", true) }
        if (refIndex < 0) return null
        val tail = settings.drop(refIndex + 1).dropWhile { it.text == ":" }
        val opIndex = tail.indexOfFirst { it.text in setOf(">", "<", "-", "<>") }
        if (opIndex < 0) return null
        val target = parseColumnRef(tail.drop(opIndex + 1).takeWhile { it.text != "," }) ?: return null
        val source = DbmlColumnRef(table.first, table.second, column)
        val cardinality = when (tail[opIndex].text) {
            ">" -> DbmlCardinality.MANY_TO_ONE
            "<" -> DbmlCardinality.ONE_TO_MANY
            "-" -> DbmlCardinality.ONE_TO_ONE
            else -> DbmlCardinality.MANY_TO_MANY
        }
        return DbmlReference(source, target, cardinality)
    }

    private fun parseColumnRef(tokens: List<Token>): DbmlColumnRef? {
        val parts = tokens.filter { it.kind == Kind.WORD || it.kind == Kind.STRING }.map { it.text }
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
        schema.references.forEach { ref ->
            listOf(ref.from, ref.to).forEach { endpoint ->
                val table = schema.tables.firstOrNull { it.qualifiedName.equals(endpoint.tableName, true) || it.alias?.equals(endpoint.table, true) == true }
                when {
                    table == null -> warning("Unknown reference table '${endpoint.tableName}'", Token(Kind.WORD, endpoint.tableName, 1, 1))
                    table.columns.none { it.name.equals(endpoint.column, true) } -> warning("Unknown reference column '${endpoint.tableName}.${endpoint.column}'", Token(Kind.WORD, endpoint.column, 1, 1))
                }
            }
        }
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
            if (token.text == "[") depth++
            if (token.text == "]") depth--
            if (depth > 0) result += token
        }
        if (depth > 0) error("Unterminated settings block", peek())
        return result
    }

    private fun compact(items: List<Token>): String = buildString {
        items.forEachIndexed { index, token ->
            if (index > 0 && token.text !in setOf(")", ",") && items[index - 1].text !in setOf("(", ".")) append(' ')
            append(token.text)
        }
    }.replace(" ,", ",")

    private fun settingSegments(items: List<Token>): List<List<Token>> {
        val result = mutableListOf<MutableList<Token>>(mutableListOf()); var depth = 0
        items.forEach { token ->
            if (token.text == "(") depth++
            if (token.text == ")") depth--
            if (token.text == "," && depth == 0) result.add(mutableListOf()) else result.last().add(token)
        }
        return result
    }
    private fun hasSetting(items: List<Token>, key: String) = settingSegments(items).any { segment ->
        segment.takeWhile { it.text != ":" }.joinToString(" ") { it.text }.trim().equals(key, true)
    }
    private fun settingValue(items: List<Token>, key: String): String? = settingSegments(items).firstNotNullOfOrNull { segment ->
        val colon = segment.indexOfFirst { it.text == ":" }
        if (colon > 0 && segment.take(colon).joinToString(" ") { it.text }.trim().equals(key, true))
            compact(segment.drop(colon + 1)).trim().ifBlank { null } else null
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
    private fun matchWord(value: String): Boolean = if (peek().kind == Kind.WORD && peek().text.equals(value, true)) { advance(); true } else false
    private fun checkSymbol(value: String) = peek().kind == Kind.SYMBOL && peek().text == value
    private fun consumeSymbol(value: String): Boolean = if (checkSymbol(value)) { advance(); true } else false
    private fun skipNewlines() { while (peek().kind == Kind.NEWLINE) advance() }
    private fun skipLine() { while (!atEnd() && peek().kind != Kind.NEWLINE && !checkSymbol("}")) advance(); if (peek().kind == Kind.NEWLINE) advance() }
    private fun peek() = tokens[position]
    private fun advance(): Token = tokens[position].also { if (position < tokens.lastIndex) position++ }
    private fun atEnd() = peek().kind == Kind.EOF
    private fun error(message: String, token: Token) { errors += DbmlParseError(message, token.line, token.column, DbmlParseSeverity.ERROR) }
    private fun warning(message: String, token: Token) { errors += DbmlParseError(message, token.line, token.column, DbmlParseSeverity.WARNING) }
}
