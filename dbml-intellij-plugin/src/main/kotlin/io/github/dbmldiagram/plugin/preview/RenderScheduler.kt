package io.github.dbmldiagram.plugin.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dbmldiagram.core.layout.DiagramLayoutEngine
import io.github.dbmldiagram.core.model.DbmlParseError
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.parser.DbmlParser
import io.github.dbmldiagram.core.renderer.DiagramRenderer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface RenderScheduler : Disposable {
    fun schedule(text: String)
    fun renderNow(text: String)
}

class DebouncedRenderScheduler(
    private val parser: DbmlParser,
    private val layoutEngine: DiagramLayoutEngine,
    private val renderer: DiagramRenderer,
    private val callback: (String?, DbmlSchema?, List<DbmlParseError>) -> Unit,
) : RenderScheduler {
    private val log = Logger.getInstance(DebouncedRenderScheduler::class.java)
    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("DBML diagram renderer", 1)
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var disposed = false

    override fun schedule(text: String) = submit(text, 300)
    override fun renderNow(text: String) = submit(text, 0)

    private fun submit(text: String, delayMs: Long) {
        synchronized(lock) {
            pending?.cancel(false)
            pending = executor.schedule({ render(text) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun render(text: String) {
        if (disposed) return
        try {
            val parseStarted = System.nanoTime()
            val result = parser.parse(text)
            val parseMs = elapsed(parseStarted)
            var svg: String? = null
            var layoutMs = 0L; var renderMs = 0L
            val schema = result.schema
            if (!result.hasErrors && schema != null) {
                val layoutStarted = System.nanoTime()
                val layout = layoutEngine.layout(schema)
                layoutMs = elapsed(layoutStarted)
                val renderStarted = System.nanoTime()
                svg = renderer.render(schema, layout)
                renderMs = elapsed(renderStarted)
            }
            log.debug("DBML render timings: parse=${parseMs}ms layout=${layoutMs}ms render=${renderMs}ms")
            val rendered = svg
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) callback(rendered, if (rendered != null) schema else null, result.errors)
            }
        } catch (t: Throwable) {
            log.warn("DBML preview render failed", t)
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) callback(null, null, listOf(DbmlParseError("Preview failed: ${t.message ?: t.javaClass.simpleName}", 1, 1, io.github.dbmldiagram.core.model.DbmlParseSeverity.ERROR)))
            }
        }
    }

    private fun elapsed(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

    override fun dispose() {
        disposed = true
        synchronized(lock) { pending?.cancel(true) }
        executor.shutdownNow()
    }
}
