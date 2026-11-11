package com.kernelloader.driver

import java.io.File

object ElfInfo {

    data class Section(
        val name: String,
        val type: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val entsize: Long
    )

    data class Symbol(
        val name: String,
        val value: Long,
        val shndx: Int,
        val bind: Int,
        val type: Int
    )

    data class Info(
        val machine: Int,
        val sections: List<Section>,
        val sectionNames: Set<String>,
        val symbols: List<Symbol>,
        val versions: LinkedHashMap<String, Long>,
        val modinfo: List<String>,
        val vermagic: String,
        val moduleName: String,
        val cfi: String,
        val undefinedSymbols: List<String>,
        val buildId: String
    ) {
        val modversions: Boolean get() = versions.isNotEmpty()
        val moduleLayoutCrc: Long? get() = versions["module_layout"]
    }

    fun parse(file: File): Info? = try {
        if (!file.isFile || file.length() < 64L || file.length() > 64L * 1024 * 1024) null
        else parse(file.readBytes())
    } catch (_: Exception) {
        null
    }

    fun parse(bytes: ByteArray): Info? {
        if (bytes.size < 64) return null
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return null
        if (bytes[4].toInt() != 2 || bytes[5].toInt() != 1) return null

        val machine = u16(bytes, 0x12) ?: return null
        val shoff = u64(bytes, 0x28) ?: return null
        val shentsize = u16(bytes, 0x3A) ?: return null
        val shnum = u16(bytes, 0x3C) ?: return null
        val shstrndx = u16(bytes, 0x3E) ?: return null
        if (shoff <= 0L || shnum <= 0 || shentsize < 64) return null
        if (shoff + shnum.toLong() * shentsize > bytes.size) return null

        val rawSections = ArrayList<Section>(shnum)
        for (i in 0 until shnum) {
            val o = (shoff + i.toLong() * shentsize).toInt()
            if (o < 0 || o + 64 > bytes.size) return null
            val nameOff = u32(bytes, o) ?: return null
            val type = u32(bytes, o + 4) ?: return null
            val offset = u64(bytes, o + 24) ?: return null
            val size = u64(bytes, o + 32) ?: return null
            val link = u32(bytes, o + 40)?.toInt() ?: 0
            val entsize = u64(bytes, o + 56) ?: 0L
            rawSections.add(Section("", type, offset, size, link, entsize))
        }
        if (shstrndx >= rawSections.size) return null
        val shstr = rawSections[shstrndx]
        if (shstr.offset < 0 || shstr.size < 0 || shstr.offset + shstr.size > bytes.size) return null
        val sectionNamesBytes = bytes.copyOfRange(shstr.offset.toInt(), (shstr.offset + shstr.size).toInt())

        fun sectionName(off: Long): String {
            if (off < 0 || off >= sectionNamesBytes.size) return ""
            var end = off.toInt()
            while (end < sectionNamesBytes.size && sectionNamesBytes[end] != 0.toByte()) end++
            return String(sectionNamesBytes, off.toInt(), end - off.toInt(), Charsets.ISO_8859_1)
        }

        val namedSections = ArrayList<Section>(shnum)
        for (i in 0 until shnum) {
            val o = (shoff + i.toLong() * shentsize).toInt()
            val nameOff = u32(bytes, o) ?: return null
            val base = rawSections[i]
            namedSections.add(base.copy(name = sectionName(nameOff.toLong())))
        }
        val sectionNames = namedSections.map { it.name }.toSet()
        val byName = namedSections.associateBy { it.name }

        val symbols = ArrayList<Symbol>()
        val symtab = byName[".symtab"] ?: byName["symtab"]
        if (symtab != null && symtab.link in namedSections.indices &&
            symtab.offset >= 0 && symtab.size > 0 &&
            symtab.offset + symtab.size <= bytes.size
        ) {
            val strtab = namedSections[symtab.link]
            if (strtab.offset >= 0 && strtab.size > 0 && strtab.offset + strtab.size <= bytes.size) {
                val strBytes = bytes.copyOfRange(strtab.offset.toInt(), (strtab.offset + strtab.size).toInt())
                fun symName(off: Long): String {
                    if (off < 0 || off >= strBytes.size) return ""
                    var end = off.toInt()
                    while (end < strBytes.size && strBytes[end] != 0.toByte()) end++
                    return String(strBytes, off.toInt(), end - off.toInt(), Charsets.ISO_8859_1)
                }
                val count = (symtab.size / 24L).toInt()
                for (i in 0 until count) {
                    val o = (symtab.offset + i.toLong() * 24L).toInt()
                    if (o + 24 > bytes.size) break
                    val stName = u32(bytes, o) ?: 0
                    val stInfo = bytes[o + 4].toInt() and 0xFF
                    val stShndx = u16(bytes, o + 6) ?: 0
                    val stValue = u64(bytes, o + 8) ?: 0L
                    val name = symName(stName.toLong())
                    if (name.isNotEmpty()) {
                        symbols.add(
                            Symbol(
                                name = name,
                                value = stValue,
                                shndx = stShndx,
                                bind = stInfo shr 4,
                                type = stInfo and 0xF
                            )
                        )
                    }
                }
            }
        }

        val versions = LinkedHashMap<String, Long>()
        val verSec = byName["__versions"]
        if (verSec != null && verSec.offset >= 0 && verSec.size >= 64 &&
            verSec.offset + verSec.size <= bytes.size
        ) {
            val count = (verSec.size / 64L).toInt()
            for (i in 0 until count) {
                val o = (verSec.offset + i.toLong() * 64L).toInt()
                if (o + 64 > bytes.size) break
                val crc = u64(bytes, o) ?: 0L
                var end = o + 8
                val max = o + 64
                while (end < max && bytes[end] != 0.toByte()) end++
                val name = String(bytes, o + 8, end - (o + 8), Charsets.ISO_8859_1)
                if (name.isNotBlank()) versions[name] = crc and 0xFFFFFFFFL
            }
        }

        val modinfo = ArrayList<String>()
        val modinfoSec = byName[".modinfo"]
        if (modinfoSec != null && modinfoSec.offset >= 0 && modinfoSec.size > 0 &&
            modinfoSec.offset + modinfoSec.size <= bytes.size
        ) {
            var start = modinfoSec.offset.toInt()
            val end = (modinfoSec.offset + modinfoSec.size).toInt()
            while (start < end) {
                var stop = start
                while (stop < end && bytes[stop] != 0.toByte()) stop++
                if (stop > start) {
                    val s = String(bytes, start, stop - start, Charsets.ISO_8859_1)
                    if (s.isNotBlank()) modinfo.add(s)
                }
                start = stop + 1
            }
        }

        val vermagic = modinfo.firstOrNull { it.startsWith("vermagic=") }
            ?.substringAfter("vermagic=")?.trim().orEmpty()
        val moduleName = modinfo.firstOrNull { it.startsWith("name=") }
            ?.substringAfter("name=")?.trim().orEmpty()

        val cfi = when {
            sectionNames.contains(".cfi_jt") -> "clang-cfi"
            symbols.any { it.name == "__cfi_slowpath" || it.name == "__cfi_check" } -> "clang-cfi"
            sectionNames.contains(".kcfi_traps") -> "kcfi"
            symbols.any { it.name.startsWith("__kcfi_typeid_") } -> "kcfi"
            else -> "none"
        }

        val undefined = symbols.asSequence()
            .filter { it.shndx == 0 && it.name.isNotBlank() }
            .map { it.name }
            .filter { !it.startsWith("__crc_") && it != "_GLOBAL_OFFSET_TABLE_" }
            .distinct()
            .sorted()
            .toList()

        val buildId = parseBuildId(bytes, byName[".note.gnu.build-id"])

        return Info(
            machine = machine,
            sections = namedSections,
            sectionNames = sectionNames,
            symbols = symbols,
            versions = versions,
            modinfo = modinfo,
            vermagic = vermagic,
            moduleName = moduleName,
            cfi = cfi,
            undefinedSymbols = undefined,
            buildId = buildId
        )
    }

    private fun parseBuildId(bytes: ByteArray, section: Section?): String {
        if (section == null || section.offset < 0 || section.size < 16 ||
            section.offset + section.size > bytes.size
        ) return ""
        return try {
            var o = section.offset.toInt()
            val end = (section.offset + section.size).toInt()
            while (o + 12 <= end) {
                val namesz = u32(bytes, o)?.toInt() ?: break
                val descsz = u32(bytes, o + 4)?.toInt() ?: break
                val type = u32(bytes, o + 8)?.toInt() ?: break
                val nameStart = o + 12
                val descStart = nameStart + align4(namesz)
                val next = descStart + align4(descsz)
                if (type == 3 && descsz in 1..64 && descStart + descsz <= end) {
                    return bytes.copyOfRange(descStart, descStart + descsz)
                        .joinToString("") { "%02x".format(it) }
                }
                if (next <= o) break
                o = next
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun align4(v: Int): Int = (v + 3) and 3.inv()

    private fun u16(b: ByteArray, off: Int): Int? {
        if (off < 0 || off + 2 > b.size) return null
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(b: ByteArray, off: Int): Long? {
        if (off < 0 || off + 4 > b.size) return null
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun u64(b: ByteArray, off: Int): Long? {
        if (off < 0 || off + 8 > b.size) return null
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
