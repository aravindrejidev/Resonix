package com.resonix.player.audio

import java.util.Locale

/**
 * Codec names as FFmpeg reports them ("mp3", "pcm_s16le", "flac" …) → what a person expects
 * to read next to a song.
 */
fun displayFormatName(raw: String): String {
    val name = raw.trim().lowercase(Locale.ROOT)
    return when {
        name.isEmpty() -> ""
        name == "mp3" || name == "mp3float" -> "MP3"
        name == "mp2" || name == "mp2float" -> "MP2"
        name == "flac" -> "FLAC"
        name == "alac" -> "ALAC"
        name == "aac" || name == "aac_latm" -> "AAC"
        name == "vorbis" -> "Vorbis"
        name == "opus" -> "Opus"
        name.startsWith("wma") -> "WMA"
        name == "ape" -> "APE"
        name == "wavpack" -> "WavPack"
        name == "tta" -> "TTA"
        name == "ac3" -> "AC3"
        name == "eac3" -> "E-AC3"
        name == "dts" -> "DTS"
        name == "mlp" || name == "truehd" -> name.uppercase(Locale.ROOT)
        name.startsWith("pcm_") -> "PCM"
        name.startsWith("dsd_") -> "DSD"
        else -> raw.trim().uppercase(Locale.ROOT)
    }
}

/** Formats where "bit depth" is a property of the file rather than of the decoder's output. */
fun isLosslessFormat(raw: String): Boolean {
    val name = raw.trim().lowercase(Locale.ROOT)
    return name in setOf("flac", "alac", "ape", "wavpack", "tta", "mlp", "truehd", "wmalossless") ||
        name.startsWith("pcm_") ||
        name.startsWith("dsd_")
}

/** Lossless audio above CD quality (16-bit / 44.1–48 kHz). A lossy file is never "hi-res". */
fun isHiResAudio(format: String, bitDepth: Int, sampleRateHz: Int): Boolean =
    isLosslessFormat(format) && (bitDepth > 16 || sampleRateHz > 48_000)
