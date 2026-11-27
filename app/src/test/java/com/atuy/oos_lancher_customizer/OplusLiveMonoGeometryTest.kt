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
    @Test fun customScaleExpandsAroundCenter() {
        assertEquals(OplusLiveMonoRenderer.Geometry(288,288,-48,-48),
            OplusLiveMonoRenderer.geometry(96,96,192,OplusMonoScale.parse("1.5")!!))
    }

    @Test fun customScalePreservesNonSquareAspect() {
        assertEquals(OplusLiveMonoRenderer.Geometry(288,144,-48,24),
            OplusLiveMonoRenderer.geometry(384,192,192,OplusMonoScale.parse("1.5")!!))
    }

    @Test fun validatesSettingAndCanonicalizesCacheKey() {
        assertEquals(OplusMonoScale.NATIVE, OplusMonoScale.parse(null))
        assertEquals(OplusMonoScale.NATIVE, OplusMonoScale.parse("native"))
        assertEquals("1.5", OplusMonoScale.parse("1.50")!!.cacheToken)
        for (invalid in listOf("NaN","Infinity","0","-1","4.1","oops")) {
            org.junit.Assert.assertNull(OplusMonoScale.parse(invalid))
        }
    }
}
