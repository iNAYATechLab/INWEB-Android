package com.inweb.app.dns

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RFC 1035 DNS packet parser + builder. Handles:
 *   - Standard queries (opcode 0)
 *   - A (type 1) and AAAA (type 28) records
 *   - Name compression (0xC0 pointers)
 *
 * Zero external dependencies. This is the codec used by both:
 *   - [InwebVpnService] — to intercept queries inside the VPN tunnel
 *   - [DnsServer]       — to answer UDP queries on port 5353 / 53
 */
object DnsPacket {

    data class Question(val name: String, val type: Int, val clazz: Int)
    data class Header(
        val id: Int, val flags: Int, val qdCount: Int,
        val anCount: Int, val nsCount: Int, val arCount: Int
    )
    data class Parsed(val header: Header, val questions: List<Question>, val raw: ByteArray)

    const val TYPE_A    = 1
    const val TYPE_AAAA = 28
    const val CLASS_IN  = 1

    /* ---------------------------------------------------------------- */
    /*  Parsing                                                          */
    /* ---------------------------------------------------------------- */

    fun parse(bytes: ByteArray): Parsed? {
        if (bytes.size < 12) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val id      = buf.short.toInt() and 0xFFFF
        val flags   = buf.short.toInt() and 0xFFFF
        val qd      = buf.short.toInt() and 0xFFFF
        val an      = buf.short.toInt() and 0xFFFF
        val ns      = buf.short.toInt() and 0xFFFF
        val ar      = buf.short.toInt() and 0xFFFF

        val questions = ArrayList<Question>(qd)
        for (i in 0 until qd) {
            val name = readName(bytes, buf) ?: return null
            val type = buf.short.toInt() and 0xFFFF
            val clz  = buf.short.toInt() and 0xFFFF
            questions += Question(name, type, clz)
        }
        return Parsed(Header(id, flags, qd, an, ns, ar), questions, bytes)
    }

    /**
     * Reads a DNS name from [buf] starting at its current position, following
     * compression pointers into the full [packet]. Returns the ASCII
     * dot-separated form (no trailing dot).
     */
    private fun readName(packet: ByteArray, buf: ByteBuffer): String? {
        val out = StringBuilder()
        var savedPos = -1
        var jumps = 0
        while (true) {
            if (!buf.hasRemaining()) return null
            val len = buf.get().toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                // Compression pointer.
                if (!buf.hasRemaining()) return null
                val ptr = ((len and 0x3F) shl 8) or (buf.get().toInt() and 0xFF)
                if (savedPos < 0) savedPos = buf.position()
                buf.position(ptr)
                if (++jumps > 10) return null
                continue
            }
            if (out.isNotEmpty()) out.append('.')
            for (i in 0 until len) {
                if (!buf.hasRemaining()) return null
                out.append((buf.get().toInt() and 0xFF).toChar())
            }
        }
        if (savedPos >= 0) buf.position(savedPos)
        return out.toString()
    }

    /* ---------------------------------------------------------------- */
    /*  Building responses                                              */
    /* ---------------------------------------------------------------- */

    /**
     * Build an A-record response for [q] pointing at [ipv4], echoing the
     * original query ID. TTL defaults to 60 s.
     */
    fun buildAResponse(query: Parsed, ipv4: String, ttl: Int = 60): ByteArray {
        val q = query.questions.firstOrNull() ?: return buildNxDomain(query)
        val ipBytes = parseIpv4(ipv4) ?: return buildNxDomain(query)

        // Header: same ID, QR=1, AA=1, RD copied from query, RA=1, rcode=0
        val flags = 0x8000 or             // QR
                    0x0400 or             // AA
                    (query.header.flags and 0x0100) or  // RD
                    0x0080                // RA
        val nameBytes = encodeName(q.name)

        val out = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN)
        out.putShort(query.header.id.toShort())
        out.putShort(flags.toShort())
        out.putShort(1); out.putShort(1); out.putShort(0); out.putShort(0)

        // Question section (echoed)
        out.put(nameBytes)
        out.putShort(TYPE_A.toShort()); out.putShort(CLASS_IN.toShort())

        // Answer section
        out.put(nameBytes)
        out.putShort(TYPE_A.toShort()); out.putShort(CLASS_IN.toShort())
        out.putInt(ttl)
        out.putShort(4)
        out.put(ipBytes)

        return out.array().copyOf(out.position())
    }

    /** Build a response indicating "domain does not exist" (rcode 3). */
    fun buildNxDomain(query: Parsed): ByteArray {
        val q = query.questions.firstOrNull()
        val flags = 0x8000 or (query.header.flags and 0x0100) or 0x0080 or 0x0003
        val out = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN)
        out.putShort(query.header.id.toShort())
        out.putShort(flags.toShort())
        out.putShort(if (q != null) 1 else 0); out.putShort(0); out.putShort(0); out.putShort(0)
        if (q != null) {
            out.put(encodeName(q.name))
            out.putShort(q.type.toShort())
            out.putShort(q.clazz.toShort())
        }
        return out.array().copyOf(out.position())
    }

    private fun encodeName(name: String): ByteArray {
        if (name.isEmpty()) return byteArrayOf(0)
        val labels = name.split('.').filter { it.isNotEmpty() }
        val out = ByteBuffer.allocate(name.length + labels.size + 1)
        for (label in labels) {
            val b = label.toByteArray(Charsets.US_ASCII)
            if (b.size > 63) return byteArrayOf(0)   // invalid; return root
            out.put(b.size.toByte())
            out.put(b)
        }
        out.put(0)
        return out.array().copyOf(out.position())
    }

    private fun parseIpv4(ip: String): ByteArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        return runCatching {
            ByteArray(4) { parts[it].toInt().also { n -> require(n in 0..255) }.toByte() }
        }.getOrNull()
    }
}
