package io.github.dbmldiagram.plugin.preview

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.dbmldiagram.core.layout.DiagramLayout
import io.github.dbmldiagram.core.layout.DiagramLayoutEngine
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.model.ParseResult
import io.github.dbmldiagram.core.parser.DbmlParser
import io.github.dbmldiagram.core.renderer.DiagramRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RenderSchedulerTest : BasePlatformTestCase() {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertTrue("Background rendering did not complete", condition())
    }
    fun testLayoutRefreshReusesParseButStillRendersNewLayout() {
        val parses = AtomicInteger(); val layouts = AtomicInteger(); val renders = AtomicInteger()
        val callbacks = AtomicInteger()
        val scheduler = DebouncedRenderScheduler(
            DbmlParser { parses.incrementAndGet(); ParseResult(DbmlSchema(), emptyList()) },
            DiagramLayoutEngine { layouts.incrementAndGet(); DiagramLayout(emptyList(), emptyList(), 100.0, 100.0) },
            DiagramRenderer { _, _ -> renders.incrementAndGet(); "<svg/>" },
        ) { _, _, _ -> callbacks.incrementAndGet() }
        try {
            scheduler.renderNow("same DBML")
            await { callbacks.get() == 1 }
            scheduler.renderNow("same DBML") // Table/route movement must recalculate layout, not parse text.
            await { callbacks.get() == 2 }
            assertEquals(1, parses.get()); assertEquals(2, layouts.get()); assertEquals(2, renders.get())
            scheduler.renderNow("changed DBML")
            await { callbacks.get() == 3 }
            assertEquals(2, parses.get())
        } finally { scheduler.dispose() }
    }
    fun testSupersededParseSkipsExpensiveLayoutAndSvg() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val layouts = AtomicInteger(); val renders = AtomicInteger(); val callbacks = AtomicInteger()
        val scheduler = DebouncedRenderScheduler(
            DbmlParser { text ->
                if (text == "old") { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                ParseResult(DbmlSchema(), emptyList())
            },
            DiagramLayoutEngine { layouts.incrementAndGet(); DiagramLayout(emptyList(), emptyList(), 100.0, 100.0) },
            DiagramRenderer { _, _ -> renders.incrementAndGet(); "<svg/>" },
        ) { _, _, _ -> callbacks.incrementAndGet() }
        try {
            scheduler.renderNow("old")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            scheduler.renderNow("new")
            release.countDown()
            await { callbacks.get() == 1 }
            assertEquals(1, layouts.get()); assertEquals(1, renders.get())
        } finally { release.countDown(); scheduler.dispose() }
    }
    fun testSupersededLayoutSkipsSvgAndDisposedSchedulerRejectsNoCalls() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val layouts = AtomicInteger(); val renders = AtomicInteger(); val callbacks = AtomicInteger()
        val scheduler = DebouncedRenderScheduler(
            DbmlParser { ParseResult(DbmlSchema(), emptyList()) },
            DiagramLayoutEngine {
                if (layouts.incrementAndGet() == 1) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                DiagramLayout(emptyList(), emptyList(), 100.0, 100.0)
            },
            DiagramRenderer { _, _ -> renders.incrementAndGet(); "<svg/>" },
        ) { _, _, _ -> callbacks.incrementAndGet() }
        try {
            scheduler.renderNow("same")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            scheduler.renderNow("same")
            release.countDown()
            await { callbacks.get() == 1 }
            assertEquals(2, layouts.get()); assertEquals(1, renders.get())
        } finally { release.countDown(); scheduler.dispose() }
        scheduler.schedule("after dispose")
        scheduler.renderNow("after dispose")
        assertEquals(1, callbacks.get())
    }
}
