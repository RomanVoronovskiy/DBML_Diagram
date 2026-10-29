package io.github.dbmldiagram.plugin.database

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.github.dbmldiagram.core.database.*
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

internal data class DatabaseComparisonSession(val snapshot: DatabaseSnapshot,val defaultSchema: String,val label: String)

@Service(Service.Level.PROJECT)
internal class DatabaseComparisonService(private val project: Project) : Disposable {
    private val sessions=ConcurrentHashMap<String,DatabaseComparisonSession>()
    private val revisions=ConcurrentHashMap<String,AtomicLong>()
    private val listeners=CopyOnWriteArrayList<() -> Unit>()
    fun session(file: VirtualFile): DatabaseComparisonSession? = sessions[file.url]
    fun comparison(file: VirtualFile,schema: DbmlSchema): SchemaComparison? = session(file)?.let { PostgresSchemaDiff().compare(schema,it.snapshot,it.defaultSchema) }
    fun subscribe(parent: Disposable, listener: () -> Unit) { listeners+=listener;Disposer.register(parent,Disposable { listeners-=listener }) }
    internal fun publish(file: VirtualFile,session: DatabaseComparisonSession) { sessions[file.url]=session;notify(file) }
    fun disconnect(file: VirtualFile) { revisions.computeIfAbsent(file.url) { AtomicLong() }.incrementAndGet();sessions.remove(file.url);notify(file) }
    private fun notify(file: VirtualFile) {
        if(project.isDisposed) return
        PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
        listeners.forEach { it() }
    }
    fun connectAndCompare(file: VirtualFile) {
        val document=FileDocumentManager.getInstance().getDocument(file) ?: return
        val text=document.immutableCharSequence.toString()
        val parsed=TolerantDbmlParser().parse(text)
        if(parsed.hasErrors || parsed.schema==null) { Messages.showErrorDialog(project,"Fix DBML errors before database comparison.","Compare PostgreSQL");return }
        val dialog=PostgresConnectionDialog(project)
        if(!dialog.showAndGet()) return
        val (config,password)=dialog.connection()
        val selectedSchemas=parsed.schema!!.tables.map { it.schema ?: config.schema }.toSet()+config.schema
        val revision=revisions.computeIfAbsent(file.url) { AtomicLong() }.incrementAndGet()
        sessions.remove(file.url);notify(file)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project,"Read PostgreSQL schema",true) {
            private var result: DatabaseSnapshot?=null
            private var failure: String?=null
            override fun run(indicator: ProgressIndicator) {
                try {
                    PostgresConnections.open(config,password).use { connection -> result=PostgresCatalogReader().read(connection,selectedSchemas,indicator::checkCanceled) }
                } catch(canceled: ProcessCanceledException) { throw canceled }
                catch(error: java.sql.SQLException) { failure="Cannot read PostgreSQL catalog (SQLSTATE ${error.sqlState ?: "unknown"}). Check host, database, credentials, schema permissions and SSL mode." }
                catch(error: Exception) { failure=error.message ?: "Cannot read PostgreSQL catalog." }
            }
            override fun onSuccess() {
                if(project.isDisposed || !file.isValid || revisions[file.url]?.get()!=revision) return
                failure?.let { Messages.showErrorDialog(project,it,"Compare PostgreSQL");return }
                val snapshot=result ?: return
                publish(file,DatabaseComparisonSession(snapshot,config.schema,"${config.host}:${config.port}/${config.database}"))
                showDifferences(file)
            }
        })
    }
    fun showDifferences(file: VirtualFile) {
        val session=session(file)
        if(session==null) { Messages.showInfoMessage(project,"Use Compare DB to read a PostgreSQL schema first.","Database differences");return }
        val document=FileDocumentManager.getInstance().getDocument(file) ?: return
        val text=document.immutableCharSequence.toString()
        val result=TolerantDbmlParser().parse(text)
        if(result.hasErrors || result.schema==null) { Messages.showErrorDialog(project,"Fix DBML errors before generating a migration.","Database differences");return }
        val comparison=PostgresSchemaDiff().compare(result.schema!!,session.snapshot,session.defaultSchema)
        DatabaseDifferencesDialog(project,file,text,session,comparison) { session(file) === session }.show()
    }
    override fun dispose() { sessions.clear();listeners.clear();revisions.values.forEach { it.incrementAndGet() } }
    companion object { fun get(project: Project)=project.service<DatabaseComparisonService>() }
}
