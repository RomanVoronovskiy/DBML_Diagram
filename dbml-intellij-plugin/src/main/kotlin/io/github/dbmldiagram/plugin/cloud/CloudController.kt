package io.github.dbmldiagram.plugin.cloud

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.*
import com.intellij.openapi.wm.ToolWindowManager
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
internal class CloudController(private val project: Project) {
    private val busy = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Boolean, String) -> Unit>()
    fun listen(listener: (Boolean, String) -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    private fun state(message: String) { listeners.forEach { it(busy.get(), message) } }
    fun show() { ToolWindowManager.getInstance(project).getToolWindow("dbdiagram.io")?.show() }
    fun settings() {
        if (busy.get()) return
        val dialog = CloudConnectionDialog(project)
        if (dialog.showAndGet()) { dialog.save(); state("Connection settings saved. Click Login / Connect.") }
    }
    fun login(done: (List<CloudDiagram>) -> Unit) {
        if (busy.get()) return
        val config = CloudSettings.get().snapshot()
        if (config.mode == CloudMode.CLI.name && (config.nodePath.isBlank() || config.cliEntry.isBlank())) {
            settings()
            val saved = CloudSettings.get().snapshot()
            if (saved.mode == CloudMode.CLI.name && (saved.nodePath.isBlank() || saved.cliEntry.isBlank())) return
        }
        if (CloudSettings.get().mode() == CloudMode.CLI) CloudSettings.get().invalidateBindings()
        run("Log in to dbdiagram.io", { client, _ -> client.login(); client.list() }) { diagrams ->
            done(diagrams); state("Connected. ${diagrams.size} diagrams available. Relink files after changing accounts.")
        }
    }
    fun refresh(done: (List<CloudDiagram>) -> Unit) = run("Fetch dbdiagram diagrams", { client, _ -> client.list() }) { diagrams -> done(diagrams); state("${diagrams.size} diagrams available.") }
    fun logout() {
        if (busy.get()) return
        val api = CloudSettings.get().mode() == CloudMode.API
        val message = if (api) "Remove the saved workspace API token from IDE PasswordSafe?" else "Log out of the official dbdiagram CLI? This also logs out CLI sessions used outside the plugin."
        if (Messages.showYesNoDialog(project, message, "Disconnect dbdiagram", Messages.getQuestionIcon()) != Messages.YES) return
        if (busy.get()) return
        if (api) { CloudSettings.saveToken(null); CloudSettings.get().invalidateBindings(); state("Disconnected."); return }
        run("Disconnect dbdiagram", { client, _ -> client.logout() }) { CloudSettings.get().invalidateBindings(); state("Disconnected.") }
    }

    fun bindId(file: VirtualFile) {
        val input = Messages.showInputDialog(project, "Diagram ID or https://dbdiagram.io/d/... URL", "Link local DBML", Messages.getQuestionIcon()) ?: return
        val id = try { CloudData.diagramId(input) } catch (e: CloudException) { error(e); return }
        bind(file, CloudDiagram(id, id))
    }
    fun bind(file: VirtualFile, selected: CloudDiagram) {
        if (!isDbml(file)) { error(CloudException("Select a local .dbml file in the editor.")); return }
        run("Link DBML to dbdiagram", { client, scope -> client.get(selected.id) to scope }) { (remote, scope) ->
            val diagram = remote.copy(name = if (remote.name == remote.id) selected.name else remote.name)
            CloudBindings.get(project).bind(file.url, diagram, scope, CloudData.fingerprint(remote.content!!))
            state("Linked ${file.name} to ${diagram.name}. Local content was not changed.")
        }
    }
    fun importDiagram(selected: CloudDiagram) {
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val name = selected.name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "-").trim('-').take(80).ifBlank { "schema" }
        val chosen = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Pull diagram to a DBML file", "", "dbml"), project)
            .save(base, "$name.dbml")?.file ?: return
        if (!chosen.name.endsWith(".dbml", true)) { error(CloudException("Choose a .dbml target file.")); return }
        val existing = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(chosen.toPath())
        if (existing != null) { pullFrom(existing, selected.id, selected.name); return }
        run("Download DBML diagram", { client, scope -> client.get(selected.id) to scope }) { (remote, scope) ->
            try {
                if (chosen.exists()) throw CloudException("The target file appeared during download. Nothing was overwritten; choose it again.")
                var file: VirtualFile? = null
                WriteCommandAction.runWriteCommandAction(project) {
                    val parent = VfsUtil.createDirectories(chosen.parentFile.absolutePath)
                    file = parent.createChildData(this, chosen.name).apply { charset = StandardCharsets.UTF_8; setBinaryContent(remote.content!!.toByteArray(StandardCharsets.UTF_8)) }
                }
                val target = file!!
                CloudBindings.get(project).bind(target.url, remote.copy(name = if (remote.name == remote.id) selected.name else remote.name), scope, CloudData.fingerprint(remote.content!!))
                FileEditorManager.getInstance(project).openFile(target, true)
                state("Downloaded ${selected.name} into ${target.name}.")
            } catch (e: Exception) { error(e) }
        }
    }
    fun pull(file: VirtualFile) {
        val binding = binding(file) ?: return
        pullFrom(file, binding.diagramId, binding.diagramName)
    }
    private fun pullFrom(file: VirtualFile, id: String, name: String) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val original = document.text
        run("Pull DBML from dbdiagram", { client, scope -> client.get(id) to scope }) { (remote, scope) ->
            try {
                if (!file.isValid || document.text != original) throw CloudException("The local file changed during download. Pull was stopped; your edits are intact.")
                val text = remote.content!!
                if (original != text && original.isNotEmpty() && Messages.showYesNoDialog(project,
                        "Replace ${file.name}, including unsaved edits, with '$name' from dbdiagram? This is undoable in the editor.",
                        "Confirm Pull", Messages.getWarningIcon()) != Messages.YES) return@run
                // Modal dialogs can process document events; check again after confirmation.
                if (!file.isValid || document.text != original || scope != CloudSettings.get().state.scope()) throw CloudException("The local document or connection changed. Pull was canceled.")
                if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(file).hasReadonlyFiles()) throw CloudException("The local file is read-only.")
                WriteCommandAction.runWriteCommandAction(project, "Pull DBML from dbdiagram", null, Runnable { document.setText(text) })
                FileDocumentManager.getInstance().saveDocument(document)
                CloudBindings.get(project).bind(file.url, remote.copy(name = if (remote.name == remote.id) name else remote.name), scope, CloudData.fingerprint(text))
                state("Pulled $name into ${file.name}.")
            } catch (e: Exception) { error(e) }
        }
    }
    fun push(file: VirtualFile) {
        val binding = binding(file) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val local = document.text
        try { CloudData.checkContent(local) } catch (e: CloudException) { error(e); return }
        val diagnostics = TolerantDbmlParser().parse(local)
        if (diagnostics.hasErrors && Messages.showYesNoDialog(project,
                "The local preview reports DBML errors. The cloud supports a wider DBML grammar. Send the full text anyway and let dbdiagram validate it?",
                "Check DBML before Push", Messages.getWarningIcon()) != Messages.YES) return
        if (Messages.showYesNoDialog(project, "Push the current editor content of ${file.name} to '${binding.diagramName}' (${binding.diagramId})? The remote DBML will be replaced; the website layout is retained.",
                "Confirm Push", Messages.getWarningIcon()) != Messages.YES) return
        if (document.text != local) { error(CloudException("The document changed while confirming. Review the current content and push again.")); return }
        if (CloudBindings.get(project).find(file.url)?.diagramId != binding.diagramId) { error(CloudException("The linked diagram changed while confirming. Review the link and push again.")); return }
        run("Push DBML to dbdiagram", { client, scope ->
            if (scope != binding.connectionScope) throw CloudException("The connection or workspace changed. Relink this file before pushing.")
            val remote = client.get(binding.diagramId)
            CloudData.checkPush(binding.baselineHash, remote.content!!, local)
            client.push(binding.diagramId, remote.name, local)
            scope
        }) { scope ->
            CloudBindings.get(project).bind(file.url, CloudDiagram(binding.diagramId, binding.diagramName), scope, CloudData.fingerprint(local))
            state("Pushed ${binding.diagramName}.${if (document.text != local) " Newer local edits remain unpushed." else ""}")
        }
    }
    private fun binding(file: VirtualFile): DiagramBinding? {
        val binding = CloudBindings.get(project).find(file.url)
        if (binding == null) { error(CloudException("Link this DBML file to a diagram first: choose it in the dbdiagram.io tool window or use Link ID.")); show(); return null }
        if (CloudSettings.get().state.scope() != binding.connectionScope) { error(CloudException("Connection/workspace changed. Relink this file first.")); return null }
        return binding
    }
    fun openWeb(file: VirtualFile) { CloudBindings.get(project).find(file.url)?.let { BrowserUtil.browse("https://dbdiagram.io/d/${CloudData.diagramId(it.diagramId)}") } ?: show() }
    private fun <T> run(title: String, action: (CloudClient, String) -> T, done: (T) -> Unit) {
        if (!busy.compareAndSet(false, true)) { state("Another dbdiagram operation is running."); return }
        state(title)
        val settings = CloudSettings.get().snapshot()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val client = if (settings.mode == CloudMode.API.name) DbdiagramApiClient.connect(CloudSettings.token() ?: throw CloudException("Enter a workspace API token in Connection settings."), indicator::checkCanceled)
                        else DbdiagramCliClient.connect(CliPaths(settings.nodePath, settings.cliEntry), settings.workspaceId, indicator::checkCanceled)
                    val result = action(client, settings.scope())
                    ApplicationManager.getApplication().invokeLater { busy.set(false); if (!project.isDisposed) { state("Ready."); try { done(result) } catch (e: Exception) { error(e) } } }
                } catch (_: ProcessCanceledException) {
                    ApplicationManager.getApplication().invokeLater { busy.set(false); if (!project.isDisposed) state("Canceled. If a push was in flight, check the remote before retrying.") }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater { busy.set(false); if (!project.isDisposed) { error(e); state("Operation failed. Local edits were not discarded.") } }
                }
            }
        })
    }
    private fun error(e: Exception) { Messages.showErrorDialog(project, if (e is CloudException) e.message.orEmpty() else "dbdiagram operation failed (${e.javaClass.simpleName}). Check your connection and retry; verify the remote first if pushing.", "DBML Diagram — dbdiagram.io") }
    companion object {
        fun get(project: Project) = project.service<CloudController>()
        fun isDbml(file: VirtualFile) = file.extension.equals("dbml", true)
    }
}
