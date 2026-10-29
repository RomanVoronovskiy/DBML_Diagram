package io.github.dbmldiagram.core.database

import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

/** Only the explicitly configured disposable DB is used, with unique owned schemas per test. */
class PostgresIntegrationTest {
    private fun connect(): Connection {
        val url = System.getenv("DBML_PG_TEST_URL")
        assumeTrue(!url.isNullOrBlank(),"Set DBML_PG_TEST_URL to the disposable Docker database")
        Class.forName("org.postgresql.Driver")
        return DriverManager.getConnection(url,System.getenv("DBML_PG_TEST_USER") ?: "dbml_test",
            System.getenv("DBML_PG_TEST_PASSWORD") ?: "local_schema_test_only")
    }
    private fun inSchema(test: (Connection,String) -> Unit) {
        connect().use { connection ->
            val schema = "dbml_test_"+UUID.randomUUID().toString().replace("-","")
            connection.createStatement().use { it.execute("CREATE SCHEMA ${pgQuote(schema)}") }
            try { test(connection,schema) } finally {
                connection.createStatement().use { it.execute("DROP SCHEMA ${pgQuote(schema)} CASCADE") }
            }
        }
    }
    @Test fun `compare generate apply and compare again preserves existing rows`() = inSchema { connection,namespace ->
        connection.createStatement().use {
            it.execute("CREATE TABLE ${pgQuote(namespace)}.users(id bigint PRIMARY KEY, active boolean NOT NULL DEFAULT false)")
            it.execute("INSERT INTO ${pgQuote(namespace)}.users(id) VALUES (1)")
        }
        val desired = TolerantDbmlParser().parse("""
            Enum order_state {
              active
              closed
            }
            Table users {
              id bigint [pk]
              active bool [not null, default: false]
              email varchar(255) [unique]
              created_at timestamp [default: `now()`]
            }
            Table orders {
              id bigint [pk, increment]
              user_id bigint [not null]
              state order_state [not null, default: 'active']
              amount numeric(10,2) [default: 0]
              indexes { (user_id, state) [name: 'ix_order_state'] }
              checks { `amount >= 0` [name: 'ck_amount'] }
            }
            Ref: orders.user_id > users.id [delete: cascade]
        """.trimIndent())
        assertFalse(desired.hasErrors,desired.errors.toString())
        val reader = PostgresCatalogReader()
        val before = reader.read(connection,setOf(namespace))
        assertTrue(connection.autoCommit)
        assertFalse(connection.isReadOnly)
        val diff = PostgresSchemaDiff().compare(desired.schema!!,before,namespace)
        assertTrue(diff.differences.any { it.message.startsWith("Table is missing") })
        assertTrue(diff.differences.any { it.message.startsWith("Column is missing") && it.column == "email" })
        assertFalse(diff.differences.any { it.message.startsWith("Type differs") || it.message.startsWith("Default differs") })
        val migration = diff.migration(diff.differences.filter { it.sql != null }.map { it.id }.toSet())
        assertFalse(migration.contains("CREATE TABLE ${pgQuote(namespace)}.\"users\""))
        connection.createStatement().use { it.execute(migration) }
        val after = reader.read(connection,setOf(namespace))
        val remaining = PostgresSchemaDiff().compare(desired.schema!!,after,namespace)
        assertEquals(emptyList(),remaining.differences,remaining.differences.joinToString("\n"))
        connection.createStatement().use { st -> st.executeQuery("SELECT id FROM ${pgQuote(namespace)}.users").use { assertTrue(it.next()); assertEquals(1,it.getLong(1)); assertFalse(it.next()) } }
    }

    @Test fun `composite keys comments defaults quoted names and FK actions are compared structurally`() = inSchema { c,ns ->
        c.createStatement().use {
            it.execute("CREATE TABLE ${pgQuote(ns)}.\"Accounts\" (\"Tenant\" integer, id bigint, label varchar(30) DEFAULT 'A B', PRIMARY KEY(\"Tenant\",id))")
            it.execute("CREATE TABLE ${pgQuote(ns)}.child(tenant integer, account_id bigint, FOREIGN KEY(tenant,account_id) REFERENCES ${pgQuote(ns)}.\"Accounts\"(\"Tenant\",id) ON DELETE CASCADE)")
        }
        val desired = TolerantDbmlParser().parse("""
            Table "Accounts" {
              "Tenant" int
              id bigint
              label varchar(30) [default: 'A B']
              indexes { ("Tenant",id) [pk] }
            }
            Table child {
              tenant int
              account_id bigint
            }
            Ref: child.(tenant,account_id) > "Accounts".("Tenant",id) [delete: cascade]
        """.trimIndent())
        assertFalse(desired.hasErrors,desired.errors.toString())
        assertEquals(emptyList(),PostgresSchemaDiff().compare(desired.schema!!,PostgresCatalogReader().read(c,setOf(ns)),ns).differences)
    }

    @Test fun `cancelled catalog reads restore transaction and view name collisions are not create table operations`() = inSchema { c,ns ->
        c.createStatement().use { it.execute("CREATE VIEW ${pgQuote(ns)}.users AS SELECT 1 AS id") }
        assertFailsWith<java.util.concurrent.CancellationException> {
            PostgresCatalogReader().read(c,setOf(ns)) { throw java.util.concurrent.CancellationException() }
        }
        assertTrue(c.autoCommit)
        assertFalse(c.isReadOnly)
        val desired = TolerantDbmlParser().parse("Table users { id int }").schema!!
        val diff = PostgresSchemaDiff().compare(desired,PostgresCatalogReader().read(c,setOf(ns)),ns)
        assertTrue(diff.differences.any { it.sql == null && it.message.startsWith("Cannot create") })
    }

    @Test fun `catalog comparison works without SELECT on user tables and rejects invisible schema access`() = inSchema { c,ns ->
        val role="dbml_reader_"+UUID.randomUUID().toString().replace("-","")
        c.createStatement().use {
            it.execute("CREATE TABLE ${pgQuote(ns)}.private_rows(id int)")
            it.execute("CREATE ROLE ${pgQuote(role)} NOLOGIN")
        }
        try {
            c.createStatement().use { it.execute("SET ROLE ${pgQuote(role)}") }
            try { assertFailsWith<IllegalArgumentException> { PostgresCatalogReader().read(c,setOf(ns)) } }
            finally { c.createStatement().use { it.execute("RESET ROLE") } }
            c.createStatement().use { it.execute("GRANT USAGE ON SCHEMA ${pgQuote(ns)} TO ${pgQuote(role)}");it.execute("SET ROLE ${pgQuote(role)}") }
            val snapshot=PostgresCatalogReader().read(c,setOf(ns))
            assertEquals("private_rows",snapshot.tables.single().id.name)
            assertFailsWith<java.sql.SQLException> { c.createStatement().use { it.executeQuery("SELECT * FROM ${pgQuote(ns)}.private_rows") } }
        } finally {
            c.createStatement().use { it.execute("RESET ROLE");it.execute("REVOKE ALL ON SCHEMA ${pgQuote(ns)} FROM ${pgQuote(role)}");it.execute("DROP ROLE ${pgQuote(role)}") }
        }
    }

    @Test fun `reviewed widening nullability default and additive constraints converge with data intact`() = inSchema { c,ns ->
        c.createStatement().use {
            it.execute("CREATE TABLE ${pgQuote(ns)}.users(id int PRIMARY KEY, name varchar(20), active boolean DEFAULT false)")
            it.execute("INSERT INTO ${pgQuote(ns)}.users VALUES (1,'Ada',false)")
        }
        val schema=TolerantDbmlParser().parse("""
            Table users {
              id int [pk]
              name varchar(120) [not null]
              active bool [default: true]
              email text
              indexes { name [unique] }
              checks { `id > 0` }
            }
        """.trimIndent()).schema!!
        val diff=PostgresSchemaDiff().compare(schema,PostgresCatalogReader().read(c,setOf(ns)),ns)
        assertTrue(diff.differences.any { it.requiresReview })
        val sql=diff.migration(diff.differences.filter { it.sql!=null }.map { it.id }.toSet())
        c.createStatement().use { it.execute(sql) }
        assertEquals(emptyList(),PostgresSchemaDiff().compare(schema,PostgresCatalogReader().read(c,setOf(ns)),ns).differences)
        c.createStatement().use { st -> st.executeQuery("SELECT name,active FROM ${pgQuote(ns)}.users").use { assertTrue(it.next());assertEquals("Ada",it.getString(1));assertFalse(it.getBoolean(2)) } }
    }
}
