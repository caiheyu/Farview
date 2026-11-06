package com.github.caiheyu.farview.camera

import android.media.MediaFormat
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer

data class VideoFormat(val generation: Int, val width: Int, val height: Int, val rotation: Int,
    val csd0: ByteArray, val csd1: ByteArray) {
    fun mediaFormat(): MediaFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        // Some encoders merge SPS and PPS into csd-0 and emit no separate csd-1.
        if (csd1.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
    }
    fun encode(): String = JSONObject().put("generation", generation).put("width", width).put("height", height)
        .put("rotation", rotation).put("csd0", Base64.encodeToString(csd0, Base64.NO_WRAP))
        .put("csd1", Base64.encodeToString(csd1, Base64.NO_WRAP)).toString()
    companion object {
        fun decode(text: String): VideoFormat {
            val j = JSONObject(text)
            val result = VideoFormat(j.getInt("generation"), j.getInt("width"), j.getInt("height"), j.getInt("rotation"),
                Base64.decode(j.getString("csd0"), Base64.NO_WRAP), Base64.decode(j.getString("csd1"), Base64.NO_WRAP))
            require(result.width in 16..3840 && result.height in 16..3840 && result.rotation in listOf(0, 90, 180, 270)
                && result.csd0.size in 1..4096 && result.csd1.size in 0..4096) { "视频格式无效" }
            return result
        }
        fun from(generation: Int, format: MediaFormat, rotation: Int): VideoFormat {
            fun bytes(key: String): ByteArray = format.getByteBuffer(key)?.duplicate()?.let { b -> ByteArray(b.remaining()).also { b.get(it) } } ?: byteArrayOf()
            return VideoFormat(generation, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), rotation, bytes("csd-0"), bytes("csd-1"))
        }
    }
}
