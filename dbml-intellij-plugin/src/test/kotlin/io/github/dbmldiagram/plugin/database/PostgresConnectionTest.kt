package io.github.dbmldiagram.plugin.database

import io.github.dbmldiagram.core.database.PostgresCatalogReader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PostgresConnectionTest {
    @Test fun configuredPluginConnectionIsReadOnlyAtTheServerAndCanReadCatalogs() {
        val url=System.getenv("DBML_PG_TEST_URL")
        assumeTrue("Run against the disposable Docker database",url!=null)
        val uri=java.net.URI(url!!.removePrefix("jdbc:"))
        val config=PostgresConnectionState().apply { host=uri.host;port=uri.port;database=uri.path.removePrefix("/");
            user=System.getenv("DBML_PG_TEST_USER") ?: "dbml_test";schema="public";sslMode="disable" }
        PostgresConnections.open(config,System.getenv("DBML_PG_TEST_PASSWORD") ?: "local_schema_test_only").use { c ->
            assertTrue(c.isReadOnly)
            c.createStatement().use { st -> st.executeQuery("SHOW default_transaction_read_only").use { assertTrue(it.next());assertEquals("on",it.getString(1)) } }
            val snapshot=PostgresCatalogReader().read(c,setOf("public"))
            assertTrue(snapshot.inspectedSchemas.contains("public"))
            try { c.createStatement().use { it.execute("CREATE TABLE public.dbml_readonly_should_never_exist(id int)") };fail("Writes must be blocked") }
            catch(expected: java.sql.SQLException) { assertEquals("25006",expected.sqlState) }
        }
    }
}
