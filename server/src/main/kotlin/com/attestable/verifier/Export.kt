package com.attestable.verifier

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns a *verified* recording into something a human can play: a WAV of the audio chunks, a
 * concat list for the video segments, and — when ffmpeg is on the PATH — a single MP4 with both.
 */
object Export {

    data class Result(val wav: File?, val concatList: File?, val video: File?, val combined: File?, val notes: List<String>)

    fun export(manifest: RecordingManifest, chunksDir: File, outDir: File): Result {
        outDir.mkdirs()
        val notes = mutableListOf<String>()
        val id = manifest.recordingId

        val audio = manifest.chunks(ChunkType.AUDIO)
        val wav = if (audio.isNotEmpty()) {
            val stream = manifest.streams?.optJSONObject("audio")
            val sampleRate = stream?.optInt("sample_rate", 44100) ?: 44100
            val channels = stream?.optInt("channels", 1) ?: 1
            val pcm = audio.flatMap { File(chunksDir, it.file).readBytes().toList() }.toByteArray()
            File(outDir, "$id.wav").also { it.writeBytes(wavHeader(pcm.size, sampleRate, channels) + pcm) }
        } else null

        val video = manifest.chunks(ChunkType.VIDEO)
        val concat = if (video.isNotEmpty()) {
            File(outDir, "${id}_video_concat.txt").also { f ->
                f.writeText(video.joinToString("\n") { "file '${File(chunksDir, it.file).absolutePath.replace("'", "'\\''")}'" } + "\n")
            }
        } else null

        var videoOut: File? = null
        var combined: File? = null
        val ffmpeg = findFfmpeg()
        if (ffmpeg == null) {
            notes += "ffmpeg not found on PATH: wrote WAV + concat list only. Combine with:\n" +
                "  ffmpeg -f concat -safe 0 -i ${concat?.name} -c copy ${id}_video.mp4"
        } else if (concat != null) {
            videoOut = File(outDir, "${id}_video.mp4")
            if (!run(ffmpeg, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", concat.absolutePath, "-c", "copy", videoOut.absolutePath)) {
                notes += "ffmpeg concat failed"; videoOut = null
            } else if (wav != null) {
                // Align audio to video by their signed start timestamps.
                val offsetMs = audio.first().timestamp - video.first().timestamp
                combined = File(outDir, "${id}_combined.mp4")
                val args = mutableListOf("-y", "-loglevel", "error", "-i", videoOut.absolutePath)
                if (offsetMs != 0L) args += listOf("-itsoffset", "%.3f".format(offsetMs / 1000.0))
                args += listOf("-i", wav.absolutePath, "-c:v", "copy", "-c:a", "aac", "-shortest", combined.absolutePath)
                if (!run(ffmpeg, *args.toTypedArray())) { notes += "ffmpeg mux failed"; combined = null }
                else notes += "audio offset vs video: $offsetMs ms (from signed chunk timestamps)"
            }
        }
        return Result(wav, concat, videoOut, combined, notes)
    }

    private fun wavHeader(dataSize: Int, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(byteRate); putShort((channels * bitsPerSample / 8).toShort()); putShort(bitsPerSample.toShort())
            put("data".toByteArray()); putInt(dataSize)
        }.array()
    }

    private fun findFfmpeg(): String? =
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .map { File(it, "ffmpeg") }.firstOrNull { it.canExecute() }?.absolutePath

    private fun run(vararg cmd: String): Boolean = try {
        ProcessBuilder(*cmd).inheritIO().start().waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
