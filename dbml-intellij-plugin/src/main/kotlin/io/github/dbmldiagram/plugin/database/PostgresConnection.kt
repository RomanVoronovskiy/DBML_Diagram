package io.github.dbmldiagram.plugin.database

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import org.postgresql.ds.PGSimpleDataSource
import java.awt.GridLayout
import java.sql.Connection
import javax.swing.*

internal class PostgresConnectionState {
    var host = "localhost"
    var port = 5432
    var database = "postgres"
    var user = "postgres"
    var schema = "public"
    var sslMode = "prefer"
    var credentialId = java.util.UUID.randomUUID().toString()
    fun copy() = PostgresConnectionState().also { it.host=host; it.port=port; it.database=database; it.user=user;
        it.schema=schema; it.sslMode=sslMode; it.credentialId=credentialId }
}

@Service(Service.Level.PROJECT)
@State(name="DbmlPostgresConnection",storages=[Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class PostgresConnectionSettings : PersistentStateComponent<PostgresConnectionState> {
    private var data = PostgresConnectionState()
    override fun getState() = data
    override fun loadState(state: PostgresConnectionState) { data=state }
}

internal object PostgresCredentials {
    private fun key(id: String) = CredentialAttributes("DBML Diagram: PostgreSQL $id")
    fun get(id: String): String? = PasswordSafe.instance.getPassword(key(id))
    fun save(id: String, password: String) { PasswordSafe.instance.set(key(id),Credentials("postgresql",password)) }
    fun clear(id: String) { PasswordSafe.instance.set(key(id),null) }
}

internal object PostgresConnections {
    fun open(config: PostgresConnectionState, password: String): Connection {
        require(config.host.isNotBlank() && config.database.isNotBlank() && config.port in 1..65535)
        require(config.sslMode in setOf("disable","prefer","require","verify-ca","verify-full"))
        val source = PGSimpleDataSource()
        source.setServerNames(arrayOf(config.host))
        source.setPortNumbers(intArrayOf(config.port))
        source.databaseName=config.database
        source.user=config.user
        source.password=password
        source.sslMode=config.sslMode
        source.connectTimeout=10
        source.socketTimeout=35
        source.applicationName="DBML Diagram schema comparison"
        source.setReadOnly(true)
        source.setReadOnlyMode("always")
        return source.connection
    }
}

internal class PostgresConnectionDialog(private val project: Project) : DialogWrapper(project) {
    private val original = project.service<PostgresConnectionSettings>().state.copy()
    private val host=JTextField(original.host,35)
    private val port=JTextField(original.port.toString(),6)
    private val database=JTextField(original.database,35)
    private val user=JTextField(original.user,35)
    private val schema=JTextField(original.schema,35)
    private val ssl=JComboBox(arrayOf("prefer","require","verify-ca","verify-full","disable")).apply { selectedItem=original.sslMode }
    private val password=JPasswordField(35)
    private val keep=JCheckBox("Remember password in IDE PasswordSafe",true)
    init { title="Compare DBML with PostgreSQL"; setOKButtonText("Connect and compare"); init() }
    override fun createCenterPanel(): JComponent = JPanel(GridLayout(0,1,4,4)).apply {
        listOf("Host" to host,"Port" to port,"Database" to database,"User" to user,"Default schema for unqualified DBML tables" to schema,
            "SSL mode" to ssl,"Password (blank reuses the saved password for this connection)" to password).forEach { (label,field) -> add(JLabel(label));add(field) }
        add(keep)
        add(JLabel("Reads catalog metadata only. Migration SQL is exported for review; it is not executed."))
        add(JLabel("Explicit DBML schemas are also inspected. Passwords are never saved in workspace XML."))
    }
    override fun doValidate(): ValidationInfo? {
        if(host.text.isBlank()) return ValidationInfo("Enter a host.",host)
        if(port.text.toIntOrNull() !in 1..65535) return ValidationInfo("Enter a port from 1 to 65535.",port)
        if(database.text.isBlank() || user.text.isBlank() || schema.text.isBlank()) return ValidationInfo("Database, user and default schema are required.",database)
        return null
    }
    fun connection(): Pair<PostgresConnectionState,String> {
        val changed = host.text.trim()!=original.host || port.text.toInt()!=original.port || database.text.trim()!=original.database || user.text.trim()!=original.user
        val config=original.copy().also { it.host=host.text.trim();it.port=port.text.toInt();it.database=database.text.trim();it.user=user.text.trim();it.schema=schema.text.trim();it.sslMode=ssl.selectedItem as String
            if(changed) it.credentialId=java.util.UUID.randomUUID().toString() }
        val chars=password.password
        val secret=try { if(chars.isNotEmpty()) String(chars) else if(!changed) PostgresCredentials.get(original.credentialId).orEmpty() else "" }
            finally { chars.fill('\u0000');password.text="" }
        if(keep.isSelected) PostgresCredentials.save(config.credentialId,secret) else PostgresCredentials.clear(config.credentialId)
        project.service<PostgresConnectionSettings>().loadState(config)
        return config to secret
    }
}
