package chat.neto.krypta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.neto.krypta.nativebridge.Libp2pNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 0 (spike): verifica en dispositivo que el AAR de go-libp2p (gomobile) carga su
 * librería nativa (libgojni.so) y que las llamadas JNI funcionan. Este es el gate del
 * pipeline gomobile -> AAR -> Kotlin antes de integrar go-libp2p de verdad.
 */
@RunWith(AndroidJUnit4::class)
class Libp2pBridgeTest {

    private val node = Libp2pNode(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun nativePing_returnsGreetingFromGo() {
        assertEquals("pong from krypta go-libp2p bridge", node.nativePing())
    }

    /**
     * El puente dice **de qué commit sale**: 40 hexadecimales, con "-modificado" si se compiló
     * con cambios sin confirmar en el módulo Go (lo inyecta build-aar.sh). Sustituye a una
     * versión escrita a mano que nadie actualizaba —decía "0.0.18-rdv1pass" hasta el 14 sep
     * 2026— y a una aserción que exigía "spike" y hacía tiempo que no se cumplía, sin que nadie
     * lo viera porque este test instrumentado no se ejecuta en el móvil del autor.
     */
    @Test
    fun nativeVersion_isTheSourceCommit() {
        val version = node.nativeVersion()
        assertTrue("versión inesperada: $version", Regex("^[0-9a-f]{40}(-modificado)?$").matches(version))
    }

    @Test
    fun nativeSum_marshalsValuesAcrossJni() {
        assertEquals(42L, node.nativeSum(40L, 2L))
    }

    /** Gate real de la Fase 0: un host go-libp2p arranca en el dispositivo y tiene PeerID. */
    @Test
    fun libp2pHost_startsAndExposesPeerId() = runBlocking {
        node.start()
        try {
            val peerId = node.peerId()
            assertTrue("PeerID no debe estar vacío", peerId.isNotEmpty())
            // Los PeerID libp2p son CIDs/base58 de >40 chars (p. ej. 12D3Koo... para Ed25519)
            assertTrue("PeerID demasiado corto: $peerId", peerId.length >= 40)
            assertTrue("debe anunciar al menos una multiaddr", node.listenAddrs().isNotEmpty())
        } finally {
            node.stop()
        }
    }
}
