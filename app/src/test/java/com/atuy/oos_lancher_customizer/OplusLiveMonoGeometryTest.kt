package com.atuy.oos_lancher_customizer

import org.junit.Assert.assertEquals
import org.junit.Test

class OplusLiveMonoGeometryTest {
    @Test fun squareAssetsFillFrame() {
        assertEquals(OplusLiveMonoRenderer.Geometry(192,192,0,0),
            OplusLiveMonoRenderer.geometry(96,96,192))
    }

    @Test fun wideAssetsKeepAspectRatio() {
        assertEquals(OplusLiveMonoRenderer.Geometry(192,96,0,48),
            OplusLiveMonoRenderer.geometry(384,192,192))
    }

    @Test fun smallNonSquareAssetsAreCenteredWithoutUpscaling() {
        assertEquals(OplusLiveMonoRenderer.Geometry(80,40,56,76),
            OplusLiveMonoRenderer.geometry(80,40,192))
    }

    @Test fun nearSquareArtUsesNativeCoverRule() {
        assertEquals(OplusLiveMonoRenderer.Geometry(192,202,0,-5),
            OplusLiveMonoRenderer.geometry(190,200,192))
    }
}
