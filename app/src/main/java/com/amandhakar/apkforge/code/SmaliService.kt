package com.amandhakar.apkforge.code

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.io.StringWriter
import java.util.zip.ZipFile

class SmaliException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A class in the app's code. [descriptor] looks like `Lcom/example/Foo;`; [dexEntry] is the file it lives in. */
class SmaliClass(val descriptor: String, val dexEntry: String) {
    /** `com.example.Foo` */
    val name: String get() = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
}

class CodeIndex(val dexEntries: List<String>, val classes: List<SmaliClass>, val unreadable: List<String>) {
    /** Classes whose name contains [query] (ignoring case), at most [limit]. */
    fun search(query: String, limit: Int): List<SmaliClass> {
        val q = query.trim()
        return classes.asSequence().filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }.take(limit).toList()
    }
}

/** [dex] is null when the smali could not be assembled; [errors] then say where. */
class AssembleResult(val dex: ByteArray?, val errors: List<String>)

/**
 * Reads an app's code as smali and assembles edited smali back, with Google's smali/baksmali (BSD-3).
 * Nothing here runs the app's code: a dex file is only read, and assembling only writes a new one.
 * Classes are read one at a time, so a phone never needs the whole program as text.
 */
class SmaliService(
    private val maxDexBytes: Int = 64 * 1024 * 1024,
    private val opcodes: Opcodes = Opcodes.forApi(35),
) {
    private var cached: Pair<String, DexBackedDexFile>? = null // the dex file last opened, so browsing one file is quick

    private fun dexEntryNames(zip: ZipFile): List<String> = zip.entries().asSequence().map { it.name }
        .filter { DEX_NAME.matches(it) }.sortedBy { dexNumber(it) }.toList()

    @Synchronized
    private fun open(apk: File, entry: String): DexBackedDexFile {
        val key = apk.path + "|" + apk.lastModified() + "|" + entry
        cached?.takeIf { it.first == key }?.let { return it.second }
        val bytes = ZipFile(apk).use { z ->
            val e = z.getEntry(entry) ?: throw SmaliException("$entry is not in the APK")
            if (e.size > maxDexBytes) throw SmaliException("$entry is larger than ${maxDexBytes / (1024 * 1024)} MB")
            val buf = ByteArray(e.size.toInt())
            z.getInputStream(e).use { s -> var off = 0; while (off < buf.size) { val n = s.read(buf, off, buf.size - off); if (n < 0) break; off += n }; if (off != buf.size) throw SmaliException("$entry is shorter than its header says") }
            buf
        }
        val file = parse(bytes, entry)
        cached = key to file
        return file
    }

    private fun parse(bytes: ByteArray, what: String): DexBackedDexFile = try {
        DexBackedDexFile(opcodes, bytes) // uses the array as it is: no second copy of a big file
    } catch (e: Exception) {
        throw SmaliException("$what could not be read as a dex file (${e.message ?: e.javaClass.simpleName})", e)
    }

    /** Every class of every dex file in the APK. A damaged dex file is listed in [CodeIndex.unreadable], not fatal. */
    @Synchronized
    fun index(apk: File): CodeIndex {
        val entries: List<String>
        val classes = ArrayList<SmaliClass>()
        val bad = ArrayList<String>()
        val seen = HashSet<String>() // a class in several dex files runs from the first one; later copies are never used
        ZipFile(apk).use { zip ->
            entries = dexEntryNames(zip)
            for (name in entries) {
                try {
                    val dex = open(apk, name)
                    for (c in dex.classes) if (seen.add(c.type)) classes += SmaliClass(c.type, name)
                } catch (e: SmaliException) { bad += name } catch (e: OutOfMemoryError) { bad += name }
            }
        }
        classes.sortBy { it.descriptor }
        return CodeIndex(entries, classes, bad)
    }

    /** The smali of one class. */
    @Synchronized
    fun disassemble(apk: File, cls: SmaliClass): String = disassemble(open(apk, cls.dexEntry), cls.descriptor)

    /** The smali of a class in dex bytes (used to check that assembling gives back the same code). */
    fun disassemble(dex: ByteArray, descriptor: String): String = disassemble(parse(dex, "the dex file"), descriptor)

    private fun disassemble(dex: DexBackedDexFile, descriptor: String): String {
        val def = dex.classes.firstOrNull { it.type == descriptor } ?: throw SmaliException("$descriptor is not in this dex file")
        val options = BaksmaliOptions().apply {
            apiLevel = 35
            parameterRegisters = true
            localsDirective = true
            sequentialLabels = true
            debugInfo = true
            codeOffsets = false
            accessorComments = false
        }
        return try {
            val out = StringWriter()
            BaksmaliWriter(out).use { w -> ClassDefinition(options, def).writeTo(w) }
            out.toString()
        } catch (e: Exception) {
            throw SmaliException("The class could not be turned into smali: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * Assembles smali sources, keyed by the class they define, into one dex file. [apiLevel] decides which
     * instructions are allowed and which dex version is written (26 or higher keeps it loadable from Android 8).
     * The result is checked: it must define exactly the classes asked for, so a changed `.class` line is caught.
     */
    fun assemble(sources: Map<String, String>, apiLevel: Int): AssembleResult {
        var result: AssembleResult? = null
        var failure: Throwable? = null
        val worker = Thread(null, { try { result = assembleHere(sources, apiLevel) } catch (t: Throwable) { failure = t } }, "smali-assemble", 32L * 1024 * 1024)
        worker.start(); worker.join()
        failure?.let { t -> return AssembleResult(null, listOf(if (t is StackOverflowError) "A method in this class is too deeply nested to assemble." else "Could not assemble: ${t.message ?: t.javaClass.simpleName}")) }
        return result ?: AssembleResult(null, listOf("Could not assemble."))
    }

    private fun assembleHere(sources: Map<String, String>, apiLevel: Int): AssembleResult {
        if (sources.isEmpty()) return AssembleResult(null, listOf("There is nothing to assemble."))
        val dir = java.nio.file.Files.createTempDirectory("smali").toFile()
        try {
            sources.entries.forEachIndexed { i, (_, text) -> File(dir, "c$i.smali").writeText(text, Charsets.UTF_8) }
            val outFile = File(dir, "out.dex")
            val options = SmaliOptions().apply { this.apiLevel = apiLevel; outputDexFile = outFile.path; jobs = 1; verboseErrors = true }
            val log = ByteArrayOutputStream()
            val ok: Boolean
            synchronized(ERR_LOCK) { // the assembler reports errors on System.err
                val old = System.err
                System.setErr(PrintStream(log, true, "UTF-8"))
                try { ok = Smali.assemble(options, listOf(dir.path)) } finally { System.setErr(old) }
            }
            val errors = log.toString("UTF-8").lines().map { it.trim().replace(dir.path + "/", "") }.filter { it.isNotEmpty() }
            if (!ok || !outFile.isFile) return AssembleResult(null, errors.ifEmpty { listOf("The smali could not be assembled.") })
            val dex = outFile.readBytes()
            val defined = try { parse(dex, "the assembled code").classes.map { it.type }.toSet() } catch (e: SmaliException) { return AssembleResult(null, listOf(e.message ?: "The assembled file is not valid.")) }
            val wanted = sources.keys
            if (defined != wanted) {
                val extra = defined - wanted; val missing = wanted - defined
                return AssembleResult(null, buildList {
                    if (missing.isNotEmpty()) add("The .class line must stay as it was. Missing: ${missing.joinToString()}")
                    if (extra.isNotEmpty()) add("The code defines other classes than the one being edited: ${extra.joinToString()}")
                })
            }
            return AssembleResult(dex, errors)
        } catch (e: IOException) {
            return AssembleResult(null, listOf("Could not assemble: ${e.message}"))
        } catch (e: RuntimeException) {
            return AssembleResult(null, listOf("Could not assemble: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        private val ERR_LOCK = Any()
        private val DEX_NAME = Regex("classes\\d*\\.dex")
        /** classes.dex is first, then classes2.dex, classes3.dex... (the order Android loads them in). */
        fun dexNumber(name: String): Int = if (name == "classes.dex") 1 else name.removePrefix("classes").removeSuffix(".dex").toInt()
        fun dexName(number: Int): String = if (number == 1) "classes.dex" else "classes$number.dex"
    }
}
