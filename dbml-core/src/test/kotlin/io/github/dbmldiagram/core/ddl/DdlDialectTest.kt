package io.github.dbmldiagram.core.ddl

import kotlin.test.Test
import kotlin.test.assertEquals

class DdlDialectTest {
    @Test
    fun `recognizes supported DBML project database types`() {
        assertEquals(DdlDialect.POSTGRESQL, DdlDialect.fromDatabaseType("PostgreSQL"))
        assertEquals(DdlDialect.MYSQL, DdlDialect.fromDatabaseType("mysql"))
        assertEquals(DdlDialect.ORACLE, DdlDialect.fromDatabaseType("Oracle Database"))
    }
}
