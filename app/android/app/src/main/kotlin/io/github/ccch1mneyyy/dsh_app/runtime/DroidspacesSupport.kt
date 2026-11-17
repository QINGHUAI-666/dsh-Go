package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Finds and drives the Droidspaces CLI through the device's root manager.
 *
 * The release binary is bundled as `jniLibs/libdroidspaces.so` (the same
 * extraction trick used for proot), so a normal install needs only root, not a
 * separate Droidspaces app. If that file is missing, an installed
 * `/data/local/Droidspaces/bin/droidspaces` is used instead. Every call in this
 * file is a probe or a start/stop command; no shell string carries a credential.
 *
 * The container uses the existing app-private rootfs and `--net=host`. Host
 * networking is deliberate: `dsh web` binds 127.0.0.1 and the phone-control
 * MCP relay uses an abstract Unix socket, both scoped to the host network
 * namespace. A private NAT namespace would break both.
 */
object DroidspacesSupport {
    const val CONTAINER_NAME = "dsh-go"
    const val DEFAULT_BIN = "/data/local/Droidspaces/bin/droidspaces"
    /** The release binary is packaged as a jniLib so Android extracts it executable. */
    const val BUNDLED_NAME = "libdroidspaces.so"
    const val BUNDLED_VERSION = "7.2.0"
    const val GUEST_INIT = "/opt/dsh-go/droidspaces-init.sh"

    /** Where the phone's shared storage and Android system files are exposed. */
    private val SYSTEM_BINDS = listOf(
        "/system:/system:ro",
        "/apex:/apex:ro",
        "/vendor:/vendor:ro",
    )

    data class Status(
        val checked: Boolean,
        val root: Boolean,
        val binary: String?,
        val requirements: Boolean?,
        val version: String?,
        val error: String?,
    ) {
        val usable: Boolean get() = checked && root && binary != null && requirements == true

        fun toMap(): Map<String, Any?> = mapOf(
            "checked" to checked,
            "root" to root,
            "binary" to binary,
            "requirements" to requirements,
            "version" to version,
            "error" to error,
        )
    }

    private val unchecked = Status(false, false, null, null, null, "unchecked")
    @Volatile private var cached: Status = unchecked
    @Volatile private var checkedAt = 0L
    private val shellLock = Any()
    private const val CACHE_MS = 60_000L

    fun cached(): Status = cached

    /**
     * Asks the root manager for a shell, resolves the CLI and runs
     * `droidspaces check --json`. Blocking; call from a worker thread.
     *
     * [force] bypasses the short cache (the settings page and an explicit
     * start should not trust a state from before the user installed the app).
     */
    fun probe(context: Context? = null, force: Boolean = false): Status {
        val now = SystemClock.elapsedRealtime()
        if (!force && cached.checked && now - checkedAt < CACHE_MS) return cached
        synchronized(shellLock) {
            if (!force && cached.checked && SystemClock.elapsedRealtime() - checkedAt < CACHE_MS) return cached
            val next = probeLocked(context)
            cached = next
            checkedAt = SystemClock.elapsedRealtime()
            return next
        }
    }

    /** The Droidspaces release binary bundled in jniLibs, already extracted by Android. */
    fun bundledBinary(context: Context?): String? {
        val directory = context?.applicationInfo?.nativeLibraryDir ?: return null
        return File(directory, BUNDLED_NAME).takeIf { it.isFile && it.canExecute() }?.absolutePath
    }

    private fun probeLocked(context: Context?): Status {
        val id = runRoot("id -u", 5_000)
        if (id.timedOut) return Status(true, false, null, null, null, "root_timeout")
        if (id.code != 0 || id.output.trim() != "0") return Status(true, false, null, null, null, "root_denied")

        val installed = runRoot(
            "if [ -x ${shellQuote(DEFAULT_BIN)} ]; then printf '%s\\n' ${shellQuote(DEFAULT_BIN)}; fi",
            5_000,
        )
        val bundled = bundledBinary(context)
        val found = if (installed.output.lineSequence().any { it.trim() == DEFAULT_BIN }) {
            installed
        } else if (bundled != null) {
            val usable = runRoot("[ -x ${shellQuote(bundled)} ] && printf '%s\\n' ${shellQuote(bundled)}", 5_000)
            if (usable.code == 0) usable else runRoot("true", 1_000)
        } else {
            runRoot("command -v droidspaces 2>/dev/null || true", 5_000)
        }
        val binary = found.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") && File(it).name in setOf("droidspaces", BUNDLED_NAME) }
            ?: return Status(true, true, null, null, null, "droidspaces_missing")

        val version = runRoot("${shellQuote(binary)} version", 5_000)
            .output.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(160)

        val check = runRoot("${shellQuote(binary)} check --json", 20_000)
        if (check.timedOut) return Status(true, true, binary, null, version, "check_timeout")
        val json = jsonObjectOf(check.output)
        val requirements = when (val value = json?.opt("requirements_met")) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> check.code == 0
        }
        val reportedVersion = json?.optString("version")?.takeIf { it.isNotBlank() } ?: version
        return Status(
            checked = true,
            root = true,
            binary = binary,
            requirements = requirements,
            version = reportedVersion,
            error = if (requirements) null else "kernel_unsupported",
        )
    }

    /** The root command that starts the container in the foreground and keeps its console. */
    fun startCommand(binary: String, rootfs: File, binds: List<String>, permissive: Boolean): String {
        val args = mutableListOf(
            binary,
            "--name", CONTAINER_NAME,
            "--rootfs", rootfs.absolutePath,
            "--init", GUEST_INIT,
            "--net", "host",
        )
        if (permissive) args += "--selinux-permissive"
        for (bind in normalizeBinds(binds)) {
            args += "--bind"
            args += bind
        }
        args += "--foreground"
        args += "start"
        return args.joinToString(" ") { if (it.startsWith("--") || it == "start") it else shellQuote(it) }
    }

    fun stopCommand(binary: String): String =
        "${shellQuote(binary)} --name ${shellQuote(CONTAINER_NAME)} stop"

    /** Stops the container through the root manager; the caller waits for the console process. */
    fun stopContainer(binary: String) {
        runRoot(stopCommand(binary), 20_000)
    }

    /**
     * PRoot bind syntax (`host:guest`) and the bare `/storage` special case,
     * converted to Droidspaces' `source:destination[:ro]`. Missing sources are
     * skipped by Droidspaces with a warning, not a failed boot.
     */
    fun normalizeBinds(binds: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in binds) {
            val value = raw.trim()
            if (value.isEmpty()) continue
            out += if (value.contains(':')) value else "$value:$value"
        }
        for (path in SYSTEM_BINDS) {
            val source = path.substringBefore(':')
            if (File(source).exists()) out += path
        }
        return out.toList()
    }

    private fun jsonObjectOf(output: String): JSONObject? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(output.substring(start, end + 1)) }.getOrNull()
    }

    /** `su -c` through whichever root manager owns this device. */
    fun suPath(): String =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").firstOrNull { File(it).canExecute() } ?: "su"

    internal data class RootResult(val code: Int, val output: String, val timedOut: Boolean)

    internal fun runRoot(command: String, timeoutMs: Long): RootResult = synchronized(shellLock) {
        val process = try {
            ProcessBuilder(suPath(), "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return RootResult(-1, e.javaClass.simpleName, false)
        }
        val text = StringBuilder()
        val reader = thread(name = "droidspaces-root", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (text.length < 16 * 1024) text.append(line).append('\n')
                    }
                }
            } catch (_: Exception) {
            }
        }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished) process.destroyForcibly()
        reader.join(1_000)
        return RootResult(
            code = if (finished) process.exitValue() else -1,
            output = text.toString(),
            timedOut = !finished,
        )
    }

    internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
