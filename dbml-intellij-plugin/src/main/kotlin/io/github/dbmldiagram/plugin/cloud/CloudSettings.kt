package io.github.dbmldiagram.plugin.cloud

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

internal enum class CloudMode(val label: String) { CLI("Browser login (official CLI)"), API("Workspace API token (no Node.js)"); override fun toString() = label }

internal class ConnectionState {
    var mode: String = CloudMode.CLI.name
    var nodePath: String = ""
    var cliEntry: String = ""
    var workspaceId: String = ""
    var connectionId: String = java.util.UUID.randomUUID().toString()
}

@Service(Service.Level.APP)
@State(name = "DbmlDiagramCloudConnection", storages = [Storage("dbml-diagram-cloud.xml")])
internal class CloudSettings : PersistentStateComponent<ConnectionState> {
    private var data = ConnectionState()
    override fun getState() = data
    override fun loadState(state: ConnectionState) { data = state }
    fun snapshot() = ConnectionState().also { it.mode = data.mode; it.nodePath = data.nodePath; it.cliEntry = data.cliEntry; it.workspaceId = data.workspaceId; it.connectionId = data.connectionId }
    fun invalidateBindings() { data.connectionId = java.util.UUID.randomUUID().toString() }
    fun mode() = runCatching { CloudMode.valueOf(data.mode) }.getOrDefault(CloudMode.CLI)
    companion object {
        fun get() = service<CloudSettings>()
        private val tokenKey = CredentialAttributes("DBML Diagram: dbdiagram workspace API")
        fun token() = PasswordSafe.instance.getPassword(tokenKey)
        fun saveToken(value: String?) { PasswordSafe.instance.set(tokenKey, value?.let { Credentials("workspace", it) }) }
    }
}

internal class DiagramBinding {
    var fileUrl: String = ""
    var diagramId: String = ""
    var diagramName: String = ""
    var connectionScope: String = ""
    var baselineHash: String = ""
}
internal class BindingState { var bindings: MutableList<DiagramBinding> = mutableListOf() }

@Service(Service.Level.PROJECT)
@State(name = "DbmlDiagramCloudBindings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class CloudBindings : PersistentStateComponent<BindingState> {
    private var data = BindingState()
    override fun getState() = data
    override fun loadState(state: BindingState) { data = state }
    fun find(url: String) = data.bindings.firstOrNull { it.fileUrl == url }
    fun bind(url: String, diagram: CloudDiagram, scope: String, baseline: String) {
        data.bindings.removeAll { it.fileUrl == url }
        data.bindings.add(DiagramBinding().apply {
            fileUrl = url; diagramId = diagram.id; diagramName = diagram.name; connectionScope = scope; baselineHash = baseline
        })
    }
    companion object { fun get(project: Project) = project.service<CloudBindings>() }
}

internal fun ConnectionState.scope() = "$mode:$connectionId" + if (mode == CloudMode.CLI.name) ":$workspaceId" else ""
