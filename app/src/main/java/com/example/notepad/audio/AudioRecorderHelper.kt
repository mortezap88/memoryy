package com.example.notepad.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorderHelper(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var startTimeMillis: Long = 0L

    fun startRecording(): File? {
        stopRecording()

        return try {
            val audioDir = File(context.filesDir, "audio_notes")
            if (!audioDir.exists()) audioDir.mkdirs()

            val file = File(audioDir, "rec_${System.currentTimeMillis()}.m4a")
            currentOutputFile = file
            startTimeMillis = System.currentTimeMillis()

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(48000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            Log.d("AudioRecorderHelper", "Started recording to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e("AudioRecorderHelper", "Failed to start recording", e)
            recorder?.release()
            recorder = null
            currentOutputFile = null
            null
        }
    }

    fun stopRecording(): RecordingResult? {
        val activeRecorder = recorder ?: return null
        val file = currentOutputFile ?: return null

        return try {
            val durationSec = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt().coerceAtLeast(1)
            activeRecorder.stop()
            activeRecorder.release()
            recorder = null
            currentOutputFile = null
            RecordingResult(file = file, durationSec = durationSec)
        } catch (e: Exception) {
            Log.e("AudioRecorderHelper", "Error stopping recording", e)
            activeRecorder.release()
            recorder = null
            currentOutputFile = null
            null
        }
    }

    fun cancelRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        currentOutputFile?.delete()
        currentOutputFile = null
    }

    data class RecordingResult(
        val file: File,
        val durationSec: Int
    )
}
