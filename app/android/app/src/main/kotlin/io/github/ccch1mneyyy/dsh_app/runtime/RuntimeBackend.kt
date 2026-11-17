package io.github.ccch1mneyyy.dsh_app.runtime

/**
 * Which execution world runs the bundled DSH engine.
 *
 * [DROIDSPACES] is the default on Android: DSH runs as PID 1 in a native
 * namespace container started through the user's root manager, which removes
 * PRoot's ptrace overhead and keeps the same app-private rootfs. [PROOT] stays
 * as the compatibility fallback for devices without root or without a kernel
 * that Droidspaces accepts.
 */
enum class RuntimeBackend(val wire: String) {
    DROIDSPACES("droidspaces"),
    PROOT("proot"),
    ;

    companion object {
        /** An explicit saved value, else the Droidspaces default. */
        fun parse(raw: String?): RuntimeBackend =
            entries.firstOrNull { it.wire == raw } ?: DROIDSPACES
    }
}
