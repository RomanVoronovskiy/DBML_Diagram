package io.github.dbmldiagram.core.ddl

import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MySqlDdlGeneratorTest {
    @Test
    fun `generates MySQL types enums auto increment comments and references`() {
        val result = TolerantDbmlParser().parse(
            """
            Project shop { database_type: 'MySQL' }
            Enum order_status { CREATED
              PAID
            }
            Table sales.users [note: 'Application users'] {
              id bigint [pk, increment]
              external_id uuid [not null, unique]
              enabled bool [not null, default: true]
              email varchar(255) [note: 'Login address']
            }
            Table sales.orders {
              id bigint [pk, increment]
              user_id bigint [not null]
              status order_status [not null, default: 'CREATED']
            }
            Ref fk_orders_users: sales.orders.user_id > sales.users.id [delete: cascade, update: no action]
            """.trimIndent(),
        )
        assertFalse(result.hasErrors, result.errors.toString())

        val ddl = MySqlDdlGenerator().generate(result.schema!!)

        assertContains(ddl, "-- Dialect: MySQL 8.")
        assertContains(ddl, "CREATE SCHEMA IF NOT EXISTS `sales`;")
        assertContains(ddl, "`id` BIGINT NOT NULL AUTO_INCREMENT")
        assertContains(ddl, "`external_id` CHAR(36) NOT NULL")
        assertContains(ddl, "`enabled` BOOLEAN NOT NULL DEFAULT TRUE")
        assertContains(ddl, "`status` ENUM('CREATED', 'PAID') NOT NULL DEFAULT 'CREATED'")
        assertContains(ddl, "COMMENT 'Login address'")
        assertContains(ddl, ") COMMENT='Application users';")
        assertContains(ddl, "FOREIGN KEY (`user_id`) REFERENCES `sales`.`users` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION;")
    }
}
