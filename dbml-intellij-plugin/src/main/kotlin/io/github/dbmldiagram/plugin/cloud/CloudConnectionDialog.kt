package io.github.dbmldiagram.plugin.cloud

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import java.awt.GridLayout
import javax.swing.*

internal class CloudConnectionDialog(project: Project) : DialogWrapper(project) {
    private val current = CloudSettings.get().snapshot()
    private val detected = CliPaths.detect()
    private val mode = JComboBox(CloudMode.entries.toTypedArray()).apply { selectedItem = CloudSettings.get().mode() }
    private val node = JTextField(current.nodePath.ifBlank { detected.node }, 45)
    private val entry = JTextField(current.cliEntry.ifBlank { detected.entry }, 45)
    private val workspace = JTextField(current.workspaceId, 45)
    private val token = JPasswordField(45)
    init { title = "Connect to dbdiagram.io"; setOKButtonText("Save connection"); init(); mode.addActionListener { updateEnabled() }; updateEnabled() }
    override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 1, 4, 4)).apply {
        add(JLabel("Authentication")); add(mode)
        add(JLabel("Node.js executable (CLI only)")); add(node)
        add(JLabel("dbdiagram CLI entry: node_modules/dbdiagram/dist/index.js")); add(entry)
        add(JLabel("Workspace ID (CLI only; blank = personal workspace)")); add(workspace)
        add(JLabel("Workspace API token (leave blank to keep the saved token)")); add(token)
        add(JLabel("CLI mode requires Node.js 22.14+ and npm install -g dbdiagram."))
        add(JLabel("API mode uses the workspace's API Tokens tab and requires API access on your plan."))
        add(JLabel("Browser login opens dbdiagram's official login page; no account password is stored by this plugin."))
    }
    private fun updateEnabled() { val cli = mode.selectedItem == CloudMode.CLI; node.isEnabled = cli; entry.isEnabled = cli; workspace.isEnabled = cli; token.isEnabled = !cli }
    override fun doValidate(): ValidationInfo? {
        if (mode.selectedItem == CloudMode.CLI && (node.text.isBlank() || entry.text.isBlank())) return ValidationInfo("Select Node.js and the installed dbdiagram CLI entry.", entry)
        if (mode.selectedItem == CloudMode.CLI && workspace.text.isNotBlank() && !workspace.text.trim().matches(Regex("[a-fA-F0-9]{24}"))) return ValidationInfo("Workspace ID must be 24 hexadecimal characters.", workspace)
        return null
    }
    fun save() {
        CloudSettings.get().loadState(ConnectionState().also {
            it.mode = (mode.selectedItem as CloudMode).name; it.nodePath = node.text.trim(); it.cliEntry = entry.text.trim(); it.workspaceId = workspace.text.trim()
            it.connectionId = current.connectionId
        })
        val password = token.password
        try { if (mode.selectedItem == CloudMode.API && password.isNotEmpty()) { CloudSettings.saveToken(String(password).trim()); CloudSettings.get().invalidateBindings() } }
        finally { password.fill('\u0000'); token.text = "" }
    }
}
