package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidspacesBackendTest {
    @Test fun anUnsetBackendUsesDroidspaces() {
        assertEquals(RuntimeBackend.DROIDSPACES, RuntimeBackend.parse(null))
        assertEquals(RuntimeBackend.DROIDSPACES, RuntimeBackend.parse("droidspaces"))
        assertEquals(RuntimeBackend.PROOT, RuntimeBackend.parse("proot"))
        assertEquals(RuntimeBackend.DROIDSPACES, RuntimeBackend.parse("unknown"))
    }

    @Test fun bindsAreDeduplicatedAndBarePathsGetTheSameDestination() {
        val binds = DroidspacesSupport.normalizeBinds(
            listOf("/storage/emulated/0:/sdcard", "/storage", "/storage", " /sdcard:/sdcard "),
        )
        assertTrue(binds.contains("/storage/emulated/0:/sdcard"))
        assertTrue(binds.contains("/storage:/storage"))
        assertTrue(binds.contains("/sdcard:/sdcard"))
        assertEquals(binds.size, binds.distinct().size)
    }

    @Test fun theStartCommandCarriesTheContainerContract() {
        val rootfs = File("/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime/rootfs")
        val command = DroidspacesSupport.startCommand(
            binary = "/data/local/Droidspaces/bin/droidspaces",
            rootfs = rootfs,
            binds = listOf("/storage:/storage"),
            permissive = true,
        )
        assertTrue(command.contains("'/data/local/Droidspaces/bin/droidspaces'"))
        assertTrue(command.contains("--name 'dsh-go'"))
        assertTrue(command.contains("'${rootfs.absolutePath}'"))
        assertTrue(command.contains("--init '/opt/dsh-go/droidspaces-init.sh'"))
        assertTrue(command.contains("--net 'host'"))
        assertTrue(command.contains("--selinux-permissive"))
        assertTrue(command.contains("--bind '/storage:/storage'"))
        assertTrue(command.contains("--foreground"))
        assertTrue(command.endsWith("start"))
    }

    @Test fun aPathWithSpacesSurvivesShellQuoting() {
        val command = DroidspacesSupport.startCommand(
            binary = "/tmp/droid spaces/droidspaces",
            rootfs = File("/tmp/root fs"),
            binds = emptyList(),
            permissive = false,
        )
        assertTrue(command.contains("'/tmp/droid spaces/droidspaces'"))
        assertTrue(command.contains("'/tmp/root fs'"))
        assertTrue(!command.contains("--selinux-permissive"))
    }
}
