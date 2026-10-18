package com.dsoft.bookshelf

import de.sciss.jump3r.mp3.BRHist
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parsed RIFF/WAVE PCM header. */
data class WavInfo(val sampleRate: Int, val channels: Int, val bits: Int, val dataOffset: Long, val dataLength: Long)

object Wav {
    /** Parses the header of a PCM WAV file. Tolerates streaming headers with a 0 / bogus data size. */
    fun parse(header: ByteArray, fileLength: Long): WavInfo {
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var rate = 0
        var ch = 0
        var bits = 0
        while (pos + 8 <= header.size) {
            val id = String(header, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (id == "fmt ") {
                val fmt = bb.getShort(body).toInt() and 0xFFFF
                require(fmt == 1 || fmt == 0xFFFE) { "Unsupported WAV format $fmt" }
                ch = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(rate > 0 && ch > 0) { "WAV fmt chunk missing" }
                val avail = fileLength - body
                val len = if (size == 0L || size > avail) avail else size
                return WavInfo(rate, ch, bits, body.toLong(), len.coerceAtLeast(0))
            }
            pos = body + size.toInt() + (size.toInt() and 1)
        }
        throw IllegalArgumentException("WAV data chunk not found")
    }

    fun read(file: File): Pair<WavInfo, ShortArray> {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(4096L, raf.length()).toInt())
            raf.readFully(head)
            val info = parse(head, raf.length())
            require(info.bits == 16) { "Only 16-bit PCM is supported (got ${info.bits})" }
            val bytes = ByteArray(info.dataLength.toInt() and 1.inv())
            raf.seek(info.dataOffset)
            raf.readFully(bytes)
            val samples = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            return info to samples
        }
    }
}

/**
 * MP3 encoding with the pure-Java LAME port jump3r, using its low-level API directly
 * (the high-level LameEncoder depends on javax.sound, which Android does not have).
 * Each call produces a self-contained run of MP3 frames; runs can be concatenated into one .mp3 file.
 */
object Mp3 {
    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int, kbps: Int, out: OutputStream) {
        require(channels == 1 || channels == 2)
        val lame = Lame()
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        BRHist()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        val gaud = GetAudio()
        lame.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        val gfp: LameGlobalFlags = lame.lame_init()
        gfp.num_channels = channels
        gfp.in_samplerate = sampleRate
        gfp.out_samplerate = sampleRate
        gfp.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        gfp.VBR = VbrMode.vbr_off
        gfp.brate = kbps
        gfp.quality = 5
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        gfp.bWriteVbrTag = false
        val rc = lame.lame_init_params(gfp)
        check(rc >= 0) { "LAME init failed ($rc)" }

        val frames = pcm.size / channels
        val block = 1152 * 4
        val left = IntArray(block)
        val right = IntArray(block)
        val buf = ByteArray((1.25 * block + 7200).toInt())
        var f = 0
        while (f < frames) {
            val n = minOf(block, frames - f)
            for (k in 0 until n) {
                val base = (f + k) * channels
                left[k] = pcm[base].toInt() shl 16
                right[k] = if (channels == 2) pcm[base + 1].toInt() shl 16 else left[k]
            }
            val bytes = lame.lame_encode_buffer_int(gfp, left, right, n, buf, 0, buf.size)
            check(bytes >= 0) { "LAME encode error ($bytes)" }
            if (bytes > 0) out.write(buf, 0, bytes)
            f += n
        }
        val tail = lame.lame_encode_flush(gfp, buf, 0, buf.size)
        if (tail > 0) out.write(buf, 0, tail)
        lame.lame_close(gfp)
    }
}
