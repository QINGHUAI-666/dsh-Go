package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.provider.Settings
import java.io.File

/**
 * Installs the Droidspaces Android backend that the bundled CLI needs on
 * Magisk / APatch and for the daemon/SELinux rules:
 *
 * * `/data/local/Droidspaces/bin/{droidspaces,busybox}` (mode 0755);
 * * `/data/adb/modules/dsh-go-droidspaces/` with the boot module, scripts and
 *   the `sepolicy.rule` applied by the root manager at boot;
 * * `/data/local/Droidspaces/.daemon_mode` = 1.
 *
 * The module is deliberately named differently from the upstream Droidspaces
 * module (`droidspaces`), so a user who already has the official app does not
 * get one module overwriting the other. The first boot after installation
 * applies the SELinux rules and starts `droidspacesd`; until then the app can
 * still use the bundled CLI directly or fall back to PRoot.
 *
 * GPL-3.0: the binaries and boot scripts are unmodified upstream v7.2.0 files;
 * provenance and hashes are in THIRD_PARTY_NOTICES.md.
 */
object DroidspacesBackend {
    const val VERSION = "7.2.0"
    const val MODULE_ID = "dsh-go-droidspaces"
    const val ROOT_DIR = "/data/local/Droidspaces"
    const val BIN_DIR = "$ROOT_DIR/bin"
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val MARKER = "$ROOT_DIR/.dsh-go-backend"
    private const val PREFS = "dsh_droidspaces_backend"
    private const val K_PENDING_BOOT = "pendingBootCount"
    private const val K_INSTALLED_VERSION = "installedVersion"

    private val MODULE_FILES = listOf(
        "module.prop",
        "post-fs-data.sh",
        "service.sh",
        "droidspaces_autoboot.sh",
        "sepolicy.rule",
    )

    data class Status(
        val installed: Boolean,
        val pendingReboot: Boolean,
        val version: String?,
        val error: String?,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "installed" to installed,
            "pendingReboot" to pendingReboot,
            "version" to version,
            "error" to error,
        )
    }

    /** Blocking root probe; call from a worker. */
    fun status(context: Context): Status {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val check = DroidspacesSupport.runRoot(
            "if [ -x '$BIN_DIR/droidspaces' ] && [ -f '$MODULE_DIR/module.prop' ] && [ -f '$MARKER' ]; " +
                "then cat '$MARKER'; fi",
            8_000,
        )
        if (check.timedOut) return Status(false, false, null, "root_timeout")
        if (check.code != 0) return Status(false, false, null, "root_denied")
        val installedVersion = check.output.trim().takeIf { it.isNotEmpty() }
        if (installedVersion == null) return Status(false, false, null, null)
        val pending = pendingAfterBoot(context, prefs)
        return Status(true, pending, prefs.getString(K_INSTALLED_VERSION, null) ?: installedVersion, null)
    }

    /**
     * Copies the APK-provided binaries and boot module into the two root-owned
     * locations. Resolves with the new status; a reboot is required before the
     * SELinux rules and daemon take effect.
     */
    fun install(context: Context, onLog: (String) -> Unit, onDone: (Status) -> Unit) {
        val staging = File(context.cacheDir, "droidspaces-backend")
        try {
            stage(context, staging, onLog)
            val script = buildString {
                append("set -e\n")
                append("mkdir -p '$BIN_DIR' '$MODULE_DIR' '$ROOT_DIR/Logs'\n")
                append("cp -f '${staging.path}/droidspaces' '$BIN_DIR/droidspaces'\n")
                append("cp -f '${staging.path}/busybox' '$BIN_DIR/busybox'\n")
                append("chmod 0755 '$BIN_DIR/droidspaces' '$BIN_DIR/busybox'\n")
                for (name in MODULE_FILES) {
                    append("cp -f '${staging.path}/$name' '$MODULE_DIR/$name'\n")
                }
                append("chmod 0644 '$MODULE_DIR/module.prop' '$MODULE_DIR/sepolicy.rule'\n")
                append("chmod 0755 '$MODULE_DIR/post-fs-data.sh' '$MODULE_DIR/service.sh' '$MODULE_DIR/droidspaces_autoboot.sh'\n")
                append("printf '1\\n' > '$ROOT_DIR/.daemon_mode'\n")
                append("printf '%s\\n' '$VERSION' > '$MARKER'\n")
                append("chcon u:object_r:droidspacesd_exec:s0 '$BIN_DIR/droidspaces' 2>/dev/null || true\n")
            }
            val result = DroidspacesSupport.runRoot(script, 60_000)
            if (result.timedOut) return onDone(Status(false, false, null, "install_timeout"))
            if (result.code != 0) {
                onLog("[dsh-app] droidspaces backend install failed: ${result.output.takeLast(400)}")
                return onDone(Status(false, false, null, "install_failed"))
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(K_PENDING_BOOT, bootCount(context))
                .putString(K_INSTALLED_VERSION, VERSION)
                .apply()
            onLog("[dsh-app] droidspaces backend installed; reboot required for SELinux/daemon")
            onDone(Status(true, true, VERSION, null))
        } catch (e: Exception) {
            onLog("[dsh-app] droidspaces backend install failed: ${e.javaClass.simpleName}: ${e.message}")
            onDone(Status(false, false, null, "install_failed"))
        } finally {
            staging.deleteRecursively()
        }
    }

    fun uninstall(context: Context, onDone: (Status) -> Unit) {
        val result = DroidspacesSupport.runRoot(
            // Only remove binaries this installer owns; never delete an official
            // Droidspaces installation that was already on the device.
            "if [ -f '$MARKER' ]; then rm -f '$BIN_DIR/droidspaces' '$BIN_DIR/busybox'; fi; " +
                "rm -rf '$MODULE_DIR'; rm -f '$MARKER' '$ROOT_DIR/.daemon_mode'; true",
            20_000,
        )
        if (result.timedOut || result.code != 0) return onDone(Status(false, false, null, "uninstall_failed"))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        onDone(Status(false, false, null, null))
    }

    private fun pendingAfterBoot(context: Context, prefs: android.content.SharedPreferences): Boolean {
        val pending = prefs.getInt(K_PENDING_BOOT, -1)
        if (pending < 0) return false
        val current = bootCount(context)
        if (current > 0 && current != pending) {
            prefs.edit().remove(K_PENDING_BOOT).apply()
            return false
        }
        return true
    }

    private fun bootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    private fun stage(context: Context, staging: File, onLog: (String) -> Unit) {
        staging.deleteRecursively()
        check(staging.mkdirs()) { "cannot create backend staging" }
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val droidspaces = File(nativeLibraryDir, DroidspacesSupport.BUNDLED_NAME)
        val busybox = File(nativeLibraryDir, BUSYBOX_NAME)
        check(droidspaces.isFile) { "bundled droidspaces binary is missing" }
        check(busybox.isFile) { "bundled busybox binary is missing" }
        droidspaces.copyTo(File(staging, "droidspaces"), overwrite = true)
        busybox.copyTo(File(staging, "busybox"), overwrite = true)
        for (name in MODULE_FILES) {
            context.assets.open("droidspaces/$name").use { input ->
                File(staging, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        onLog("[dsh-app] droidspaces backend staged in ${staging.path}")
    }

    private const val BUSYBOX_NAME = "libbusybox.so"
}
