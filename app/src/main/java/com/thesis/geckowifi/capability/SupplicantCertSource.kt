package com.thesis.geckowifi.capability

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SupplicantCertSource"

/**
 * Reads the server's certificate chain from wpa_supplicant's control
 * interface (root). Read-only: it watches the device's own handshakes and
 * changes nothing about the connection.
 *
 * wpa_supplicant puts the full certificate (`cert=`) into its
 * `CTRL-EVENT-EAP-PEER-CERT` events only when `cert_in_cb` is set, and only on
 * the control interface, not in logcat (logcat gets `hash=` only). So this
 * sets `cert_in_cb` for the running supplicant (not persisted; a restart
 * resets it) and stays attached as a monitor while the user reconnects.
 *
 * UNTESTED ON HARDWARE: socket paths, the `wpa_cli` binary and the
 * interface name vary by device; see docs/experiments.md (E1).
 */
class SupplicantCertSource(private val iface: String = "wlan0") : CertificateSource {

    private val socketDirs = listOf(
        "/data/vendor/wifi/wpa/sockets",   // Android 9+
        "/data/misc/wifi/sockets"          // older
    )

    private fun controlSocketDir(): String? = socketDirs.firstOrNull { dir ->
        RootShell.exec("ls $dir/$iface 2>/dev/null").isNotBlank()
    }

    override suspend fun captureHandshake(timeoutSeconds: Int): PeerChain? = withContext(Dispatchers.IO) {
        val dir = controlSocketDir() ?: return@withContext null.also { Log.w(TAG, "no wpa_supplicant control socket") }
        val cli = "wpa_cli -p $dir -i $iface"
        RootShell.exec("$cli set cert_in_cb 1")
        // Interactive wpa_cli attaches as a monitor and prints events; feeding it
        // a sleeping stdin keeps it attached for the capture window.
        val output = RootShell.exec("timeout $timeoutSeconds sh -c 'sleep $timeoutSeconds | $cli' 2>/dev/null")
        parsePeerCertChain(output.lines()).also {
            Log.i(TAG, "handshake: ${it?.subjects ?: "none"}, complete=${it?.isComplete}")
        }
    }

    /**
     * From the current network's configuration: the outer (anonymous) identity
     * first, which carries the realm without the username, else the identity.
     * Only the realm leaves this function.
     */
    override suspend fun accountRealm(): String? = withContext(Dispatchers.IO) {
        val dir = controlSocketDir() ?: return@withContext null
        val cli = "wpa_cli -p $dir -i $iface"
        val id = parseCurrentNetworkId(RootShell.exec("$cli status")) ?: return@withContext null
        realmOfIdentity(RootShell.exec("$cli get_network $id anonymous_identity").lineSequence().lastOrNull { it.isNotBlank() })
            ?: realmOfIdentity(RootShell.exec("$cli get_network $id identity").lineSequence().lastOrNull { it.isNotBlank() })
    }
}
