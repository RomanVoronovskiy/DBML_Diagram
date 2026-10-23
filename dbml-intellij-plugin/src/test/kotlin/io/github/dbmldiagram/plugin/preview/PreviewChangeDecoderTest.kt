package io.github.dbmldiagram.plugin.preview

import io.github.dbmldiagram.core.layout.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class PreviewChangeDecoderTest {
    private fun batch(tables: String, routes: String = "[]", camera: String = """{"scale":0.5,"x":200,"y":-300,"grid":false,"snap":true}""") =
        "B\t" + URLEncoder.encode("""{"tables":$tables,"routes":$routes,"view":$camera}""", StandardCharsets.UTF_8.name())

    @Test fun preservesNegativeTableAndRouteCoordinatesWithLegacyAndNewPayloads() {
        for (suffix in listOf("", "\t0\t1")) {
            val table = PreviewChangeDecoder.decode("T\tapp.users\t-500\t-200\t0.5\t100\t200$suffix")!!
            assertEquals(DiagramPoint(-500.0, -200.0), table.positions["app.users"])
            val route = PreviewChangeDecoder.decode("R\troute\tleft\ttop\t-800\t-400\t0.5\t100\t200$suffix")!!
            assertEquals(DiagramPoint(-800.0, -400.0), route.routes["route"]!!.control)
        }
    }

    @Test fun decodesBatchPositionsRoutesAndCameraAsOneUpdate() {
        val update = PreviewChangeDecoder.decode(batch("""[{"id":"Пользователи & notes","x":-420,"y":-150},{"id":"orders","x":100,"y":-100}]""",
            """[{"id":"rel|x","fromSide":"right","toSide":"left","x":-50,"y":-72}]"""))!!
        assertEquals(2, update.positions.size)
        assertEquals(DiagramPoint(-420.0, -150.0), update.positions["Пользователи & notes"])
        assertEquals(ManualRelationRoute(TableSide.RIGHT, TableSide.LEFT, DiagramPoint(-50.0, -72.0)), update.routes["rel|x"])
        assertEquals(PreviewViewState(.5, 200.0, -300.0, grid = false, snap = true), update.view)
    }

    @Test fun rejectsWholeBatchForBadOrDuplicateMemberAndNonfiniteValues() {
        for (tables in listOf("[]", """[{"id":"users","x":0,"y":0},{"id":"orders","x":"NaN","y":0}]""",
            """[{"id":"users","x":0,"y":0},{"id":"users","x":10,"y":20}]""", """[{"id":"users","x":0}]""")) {
            assertNull(PreviewChangeDecoder.decode(batch(tables)))
        }
        assertNull(PreviewChangeDecoder.decode(batch("""[{"id":"users","x":0,"y":0}]""",
            """[{"id":"r","fromSide":"unknown","toSide":"left","x":0,"y":0}]""")))
    }

    @Test fun rejectsMalformedPayloadsAndCameraFlags() {
        for (payload in listOf("B\t%not-json", "B\t%7B%7D", "V\tNaN\t0\t0\t1\t0", "V\t1\t0\t0\ttrue\tfalse",
            "T\tu\tInfinity\t0\t1\t0\t0", "R\tr\tleft\ttop\t0\t0\t1\tNaN\t0", "X\t1")) assertNull(PreviewChangeDecoder.decode(payload))
    }

    @Test fun cameraOnlyUpdatesDoNotChangePositionsOrRoutes() {
        val update = PreviewChangeDecoder.decode("V\t0.02\t-900\t700\t0\t1")!!
        assertTrue(update.positions.isEmpty() && update.routes.isEmpty())
        assertEquals(PreviewViewState(.02, -900.0, 700.0, grid = false, snap = true), update.view)
    }
}
