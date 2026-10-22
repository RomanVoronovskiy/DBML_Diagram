package io.github.dbmldiagram.core.parser

import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.model.DbmlParseSeverity
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Run heap-exhaustion regressions in a bounded child JVM, not the Gradle/IDE test process. */
class ParserRecoveryTest {
    private fun verify(case: String) {
        val classpath = listOf(ParserRecoveryProbe::class.java, TolerantDbmlParser::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java${if (File.separatorChar == '\\') ".exe" else ""}")
        val process = ProcessBuilder(java.absolutePath, "-Xmx64m", "-cp", classpath, ParserRecoveryProbe::class.java.name, case)
            .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Parser recovery timed out for $case")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.exitValue(), "$case failed in the bounded JVM:\n$output")
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun `groups and sticky notes do not become tables or references`() = verify("visual-blocks")
    @Test fun `unknown nested blocks are skipped as a whole`() = verify("unknown-blocks")
    @Test fun `unexpected closing braces report their real locations and recover`() = verify("closing-braces")
    @Test fun `multiline notes preserve content and following declarations`() = verify("multiline-notes")
    @Test fun `quoted punctuation cannot change settings structure`() = verify("quoted-punctuation")
    @Test fun `opening braces may be on the following line`() = verify("opening-braces")
    @Test fun `unterminated blocks and multiline strings finish with errors`() = verify("unterminated")
    @Test fun `references retain line numbers after ignored multiline blocks`() = verify("locations")
    @Test fun `large schema with visualization blocks parses lays out and renders`() = verify("large-schema")
    @Test fun `malformed generated input has bounded diagnostics and always terminates`() = verify("malformed-input")
    @Test fun `CHECK blocks and composite references remain bounded in complex schemas`() = verify("complex-constraints")
}

object ParserRecoveryProbe {
    @JvmStatic fun main(args: Array<String>) {
        val parser = TolerantDbmlParser()
        when (args.single()) {
            "visual-blocks" -> {
                val result = parser.parse("""
                    Table users { id uuid [pk] }
                    TableGroup "Accounts" [note: '}', color: #123456] {
                      users
                      Note: '''A group's text with 'quotes' and {braces}'''
                    }
                    TableGroup secondary
                    {
                      users
                    }
                    Note reminder [note: '{'] {
                      '''A note with a single 'quote'.
                      Table phantom { id int }
                      Ref: phantom.id > missing.id
                      } ] // these are text, not DBML
                      '''
                    }
                    Table orders { user_id uuid }
                    Ref: orders.user_id > users.id
                """.trimIndent())
                check(!result.hasErrors) { result.errors.toString() }
                check(result.canRender)
                check(result.schema!!.tables.map { it.name } == listOf("users", "orders"))
                check(result.schema.references.size == 1)
                check(result.schema.tableGroups.size == 2)
                // Sticky Note is unsupported; the shared table produces a membership warning.
                check(result.errors.size == 2)
                check(result.errors.all { it.severity == DbmlParseSeverity.WARNING })
            }
            "unknown-blocks" -> {
                val result = parser.parse("""
                    FutureFeature widget [note: '}'] {
                      nested {
                        Table phantom { id int }
                        quoted: "}"
                      }
                    } Table actual { id int [pk] }
                    unknown_without_a_block
                    Table next { id int }
                """.trimIndent())
                check(!result.hasErrors) { result.errors.toString() }
                check(result.schema!!.tables.map { it.name } == listOf("actual", "next"))
                check(result.errors.size == 2)
            }
            "closing-braces" -> {
                val result = parser.parse("}\n}} Table actual { id int }\n")
                check(result.errors.map { it.line to it.column } == listOf(1 to 1, 2 to 1, 2 to 2))
                check(result.errors.all { it.severity == DbmlParseSeverity.ERROR })
                check(result.schema!!.tables.single().name == "actual")
                check(!result.canRender)
            }
            "multiline-notes" -> {
                val result = parser.parse("""
                    Table users {
                      id uuid [pk, note: '''Identifier with 'quotes', { }, and ]
                        second line''']
                      Note: '''Owner's description
                        Table phantom { id int }
                        and escaped \'quotes\' with \\ backslashes.
                        joined\
                    line
                    ''' }
                    Table orders { user_id uuid }
                    Ref: orders.user_id > users.id
                """.trimIndent().replace("\n", "\r\n"))
                check(!result.hasErrors) { result.errors.toString() }
                val schema = result.schema!!
                check(schema.tables.map { it.name } == listOf("users", "orders"))
                check(schema.tables.first().columns.single().note == "Identifier with 'quotes', { }, and ]\n    second line") {
                    schema.tables.first().columns.single().note.orEmpty()
                }
                val note = schema.tables.first().note!!
                check("Owner's description" in note && "and escaped 'quotes' with \\ backslashes." in note)
                check("joinedline" in note)
                check(schema.references.size == 1)
            }
            "quoted-punctuation" -> {
                val result = parser.parse("""
                    Table users {
                      id uuid [pk, note: ']']
                      label text [note: 'ref', default: ',']
                      other text [note: ':', default: '(']
                    }
                    Table orders { user_id uuid [note: ')', ref: > users.id] }
                """.trimIndent())
                check(!result.hasErrors) { result.errors.toString() }
                val columns = result.schema!!.tables.first().columns
                check(columns.map { it.note } == listOf("]", "ref", ":"))
                check(columns[1].defaultValue == "','")
                check(columns[2].defaultValue == "'('")
                check(result.schema.references.size == 1)
            }
            "opening-braces" -> {
                val result = parser.parse("""
                    Project example
                    {
                      database_type: 'PostgreSQL'
                    }
                    Enum status
                    {
                      ACTIVE
                    }
                    TABLE "users"
                    {
                      id uuid [pk]
                      email text
                      indexes
                      {
                        email [unique]
                      }
                    }
                    Table orders [note: 'Orders']
                    {
                      user_id uuid
                    }
                    Ref relation
                    {
                      orders.user_id > users.id
                    }
                """.trimIndent())
                check(!result.hasErrors) { result.errors.toString() }
                val schema = result.schema!!
                check(schema.project!!.name == "example")
                check(schema.enums.single().values == listOf("ACTIVE"))
                check(schema.tables.map { it.name } == listOf("users", "orders"))
                check(schema.tables.first().indexes.single().unique)
                check(schema.references.single().name == "relation")
            }
            "unterminated" -> {
                for (source in listOf(
                    "TableGroup unfinished {\n nested { }",
                    "TableGroup unfinished {\n missing_table",
                    "Note unfinished {\n'''not closed\n} Table phantom { id int }",
                    "Table actual {\n id int [note: 'open'",
                    "TableGroup missing_body\nTable actual { id int }",
                )) {
                    val result = parser.parse(source)
                    check(result.hasErrors && !result.canRender)
                    check(result.errors.size in 1..4) { result.errors.toString() }
                }
                val result = parser.parse("\nTableGroup unfinished {\n missing_table")
                check(result.errors.single { it.severity == DbmlParseSeverity.ERROR }.line == 2)
            }
            "locations" -> {
                val source = """
                    Note reminder {
                      '''Text with
                      'quotes' and }
                      '''
                    }
                    Table users { id uuid [pk, note: '''line one
                    line two'''] }
                    Table orders { user_id uuid }
                    Ref: orders.user_id > users.missing
                """.trimIndent()
                val result = parser.parse(source)
                val line = source.lines().indexOfFirst { it.startsWith("Ref:") } + 1
                check(result.schema!!.references.single().sourceRange!!.line == line)
                check(result.errors.single { it.severity == DbmlParseSeverity.ERROR }.line == line)
            }
            "large-schema" -> {
                val source = buildString {
                    appendLine("TableGroup application [color: #123456] {")
                    repeat(72) { appendLine("  t$it") }
                    appendLine("  Note: '''A group's multi-line text\n  with 'quotes' and }'''")
                    appendLine("}")
                    appendLine("Note summary { '''Table phantom { id int }\nThis is only documentation.''' }")
                    repeat(72) { table ->
                        appendLine("Table t$table {")
                        appendLine("  id uuid [pk]")
                        repeat(12) { column -> appendLine("  c$column varchar(255) [note: '''Column's description\n    second line''']") }
                        if (table > 0) appendLine("  parent_id uuid [ref: > t${table - 1}.id]")
                        appendLine("}")
                    }
                }
                val result = parser.parse(source)
                check(!result.hasErrors) { result.errors.toString() }
                val schema = result.schema!!
                check(schema.tables.size == 72 && schema.references.size == 71)
                check(schema.tables.sumOf { it.columns.size } == 1007)
                check(result.errors.size == 1)
                val layout = LayeredDiagramLayoutEngine().layout(schema)
                val svg = SvgDiagramRenderer().render(schema, layout)
                check(layout.nodes.size == 72 && layout.edges.size == 71)
                check(Regex("data-table=").findAll(svg).count() == 72)
            }
            "complex-constraints" -> {
                val source = buildString {
                    repeat(12) { group ->
                        appendLine("TableGroup domain_$group [color: #456] {")
                        repeat(5) { appendLine(" t${group * 5 + it}") }
                        appendLine("}")
                    }
                    repeat(60) { table ->
                        appendLine("Table t$table {")
                        appendLine(" a uuid [pk]")
                        appendLine(" b uuid [pk]")
                        appendLine(" parent_a uuid")
                        appendLine(" parent_b uuid")
                        appendLine(" value integer [check: `value >= 0`]")
                        appendLine(" checks { `value < 100` [name: 'ck_$table'] }")
                        appendLine("}")
                        if (table > 0) appendLine("Ref: t$table.(parent_a, parent_b) > t${table - 1}.(a, b)")
                    }
                }
                val result = parser.parse(source)
                check(!result.hasErrors) { result.errors.toString() }
                val schema = result.schema!!
                check(schema.tables.sumOf { it.checks.size } == 60)
                check(schema.references.size == 59)
                val layout = LayeredDiagramLayoutEngine().layout(schema)
                check(layout.groups.size == 12)
                val svg = SvgDiagramRenderer().render(schema, layout)
                check("CHECK (2)" in svg && svg.length < 1_000_000)
            }
            "malformed-input" -> {
                val random = Random(1729)
                val tokens = listOf("}", "{", "[", "]", "(", ")", "\n", "Table", "Note", "TableGroup",
                    "Enum", "Project", "Ref", "indexes", "checks", "`id > 0`", "FutureFeature", "id", "'text'", "'''text'''", ":", ",", ">")
                repeat(300) {
                    val source = List(50) { tokens.random(random) }.joinToString(" ")
                    val result = parser.parse(source)
                    check(result.errors.size <= 100) { "Unbounded recovery diagnostics" }
                }
            }
            else -> error("Unknown recovery case")
        }
        println("Parser recovery passed: ${args.single()}")
    }
}
