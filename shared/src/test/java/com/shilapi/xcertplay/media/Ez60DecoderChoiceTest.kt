package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Mazda EZ-60 (MT8676) lists Codec2 decoders and OMX.MTK aliases of them (probe report 22). Upstream's
 * MediaTek tuning (#608), on by default there, must choose the decoder the fork's own low-latency path already
 * used, and never send the legacy OMX keys, which the aliases' Codec2 decoders do not take.
 */
class Ez60DecoderChoiceTest {
    private val avc = listOf(
        DecoderCandidate("c2.mtk.avc.decoder", hardware = true, alias = false, lowLatency = false),
        DecoderCandidate("OMX.MTK.VIDEO.DECODER.AVC", hardware = true, alias = true, lowLatency = false),
        DecoderCandidate("c2.mtk.avc.decoder.lowlatency", hardware = true, alias = false, lowLatency = true),
        DecoderCandidate("c2.android.avc.decoder", hardware = false, alias = false, lowLatency = false),
    )
    private val hevc = listOf(
        DecoderCandidate("c2.mtk.hevc.decoder", hardware = true, alias = false, lowLatency = false),
        DecoderCandidate("OMX.MTK.VIDEO.DECODER.HEVC", hardware = true, alias = true, lowLatency = false),
        DecoderCandidate("c2.mtk.hevc.decoder.lowlatency", hardware = true, alias = false, lowLatency = true),
        DecoderCandidate("c2.android.hevc.decoder", hardware = false, alias = false, lowLatency = false),
    )

    // MtkDecoderTuning lists decoders without aliases, as MediaCodecList does from Android 10.
    private fun mtkCandidates(list: List<DecoderCandidate>) =
        list.filterNot { it.alias }.map { MtkDecoderCandidate(it.name, it.lowLatency) }

    @Test fun mediaTekTuningPicksTheDecoderTheFastPathAlreadyUsed() {
        assertEquals("c2.mtk.avc.decoder.lowlatency", lowLatencyDecoderName(avc))
        assertEquals("c2.mtk.avc.decoder.lowlatency", MtkDecoderTuning.selectedDecoder(mtkCandidates(avc))?.name)
        assertEquals("c2.mtk.hevc.decoder.lowlatency", MtkDecoderTuning.selectedDecoder(mtkCandidates(hevc))?.name)
    }

    @Test fun legacyOmxKeysNeverReachTheCodec2Decoders() {
        for (name in listOf("c2.mtk.avc.decoder", "c2.mtk.avc.decoder.lowlatency", "c2.mtk.hevc.decoder.lowlatency")) {
            assertEquals(name, MtkDecoderMode.NONE, appliedMtkDecoderMode(MtkDecoderMode.LOW_LATENCY_NO_REORDER, name))
        }
        // The first non-alias decoder is Codec2, so no legacy path is offered at all.
        assertEquals(null, mtkCandidates(avc).firstOrNull()?.name?.takeIf(MtkDecoderTuning::supportsLegacyAcodecKeys))
    }
}
