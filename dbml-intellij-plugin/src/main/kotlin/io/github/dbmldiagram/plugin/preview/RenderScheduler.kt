package io.github.dbmldiagram.plugin.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dbmldiagram.core.layout.DiagramLayoutEngine
import io.github.dbmldiagram.core.model.DbmlParseError
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.model.ParseResult
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.dbmldiagram.core.parser.DbmlParser
import io.github.dbmldiagram.core.renderer.DiagramRenderer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    private val revision = AtomicLong()
    // Single-entry, worker-confined cache: moving tables/routes does not change DBML.
    private var parsedText: String? = null
    private var parsedResult: ParseResult? = null

    override fun schedule(text: String) = submit(text, 300)
    override fun renderNow(text: String) = submit(text, 0)

    private fun submit(text: String, delayMs: Long) {
        synchronized(lock) {
            if (disposed) return
            val currentRevision = revision.incrementAndGet()
            pending?.cancel(false)
            pending = executor.schedule({ render(text, currentRevision) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun render(text: String, currentRevision: Long) {
        if (!isCurrent(currentRevision)) return
        try {
            val parseStarted = System.nanoTime()
            val result = if (text == parsedText) parsedResult!! else parser.parse(text).also {
                parsedText = text
                parsedResult = it
            }
            if (!isCurrent(currentRevision)) return
            val parseMs = elapsed(parseStarted)
            var svg: String? = null
            var layoutMs = 0L; var renderMs = 0L
            val schema = result.schema
            if (result.canRender && schema != null) {
                val layoutStarted = System.nanoTime()
                val layout = layoutEngine.layout(schema)
                if (!isCurrent(currentRevision)) return
                layoutMs = elapsed(layoutStarted)
                val renderStarted = System.nanoTime()
                svg = renderer.render(schema, layout)
                renderMs = elapsed(renderStarted)
            }
            if (!isCurrent(currentRevision)) return
            log.debug("DBML render timings: parse=${parseMs}ms layout=${layoutMs}ms render=${renderMs}ms")
            val rendered = svg
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && revision.get() == currentRevision) callback(rendered, if (rendered != null) schema else null, result.errors)
            }
        } catch (_: ProcessCanceledException) {
            // Cancellation is not a schema error and must not replace the preview.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Exception) {
            if (!isCurrent(currentRevision)) return
            log.warn("DBML preview render failed", t)
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && revision.get() == currentRevision) callback(null, null, listOf(DbmlParseError("Preview failed: ${t.message ?: t.javaClass.simpleName}", 1, 1, io.github.dbmldiagram.core.model.DbmlParseSeverity.ERROR)))
            }
        }
    }

    private fun isCurrent(currentRevision: Long) = !disposed && revision.get() == currentRevision

    private fun elapsed(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

    override fun dispose() {
        disposed = true
        synchronized(lock) { pending?.cancel(true) }
        executor.shutdownNow()
    }
}
