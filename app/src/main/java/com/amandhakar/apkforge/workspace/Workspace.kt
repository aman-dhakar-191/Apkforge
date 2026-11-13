package com.amandhakar.apkforge.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.UUID

data class Project(
    val id: String,
    val displayName: String,
    val createdAtMillis: Long,
    val sizeBytes: Long,
)

class ImportTooLargeException(val limitBytes: Long) : IOException("File is larger than $limitBytes bytes")

/**
 * App-private project storage:
 * `<root>/<project-id>/{input,decoded,build,output,logs}`, with the untouched original at `input/base.apk`.
 * Directory names are random ids, never package names or user-supplied names.
 */
class Workspace(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {

    init { root.mkdirs() }

    fun importApk(displayName: String, source: InputStream, maxBytes: Long): Project {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id)
        try {
            listOf("input", "decoded", "build", "output", "logs").forEach { File(dir, it).mkdirs() }
            val target = File(dir, "input/base.apk")
            var copied = 0L
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    copied += n
                    if (copied > maxBytes) throw ImportTooLargeException(maxBytes)
                    out.write(buf, 0, n)
                }
            }
            val project = Project(id, cleanLabel(displayName), clock(), copied)
            write(dir, project)
            return project
        } catch (e: Throwable) {
            dir.deleteRecursively() // never leave a half-imported project behind
            throw e
        }
    }

    fun list(): List<Project> =
        (root.listFiles { f -> f.isDirectory && ID.matches(f.name) } ?: emptyArray())
            .mapNotNull { read(it) }
            .sortedByDescending { it.createdAtMillis }

    /** Probably the same file imported before: another project with the same name and size. */
    fun hasProbableDuplicate(p: Project): Boolean =
        list().any { it.id != p.id && it.displayName == p.displayName && it.sizeBytes == p.sizeBytes }

    fun get(id: String): Project? = dirOf(id)?.let { read(it) }

    fun originalApk(id: String): File? = dirOf(id)?.let { File(it, "input/base.apk") }?.takeIf { it.isFile }

    fun exportOriginal(id: String, out: OutputStream) {
        val apk = originalApk(id) ?: throw IOException("Project has no original APK")
        apk.inputStream().use { it.copyTo(out) }
    }

    fun delete(id: String): Boolean = dirOf(id)?.deleteRecursively() ?: false

    private fun dirOf(id: String): File? = if (ID.matches(id)) File(root, id).takeIf { it.isDirectory } else null

    private fun write(dir: File, p: Project) {
        val props = Properties().apply {
            setProperty("name", p.displayName)
            setProperty("created", p.createdAtMillis.toString())
            setProperty("size", p.sizeBytes.toString())
        }
        File(dir, "project.properties").outputStream().use { props.store(it, null) }
    }

    private fun read(dir: File): Project? = try {
        val props = Properties()
        File(dir, "project.properties").inputStream().use { props.load(it) }
        Project(
            id = dir.name,
            displayName = props.getProperty("name") ?: "APK",
            createdAtMillis = props.getProperty("created")?.toLongOrNull() ?: 0L,
            sizeBytes = props.getProperty("size")?.toLongOrNull() ?: 0L,
        )
    } catch (_: IOException) {
        null
    }

    private fun cleanLabel(name: String): String =
        name.filter { !it.isISOControl() }.trim().take(120).ifEmpty { "APK" }

    companion object {
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
