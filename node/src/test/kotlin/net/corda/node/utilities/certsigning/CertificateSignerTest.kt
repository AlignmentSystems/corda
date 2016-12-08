package net.corda.node.utilities.certsigning

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.X509Utilities
import net.corda.core.div
import net.corda.core.exists
import net.corda.core.readLines
import net.corda.node.services.config.NodeConfiguration
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CertificateSignerTest {
    @Rule
    @JvmField
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun buildKeyStore() {

        val id = SecureHash.randomSHA256().toString()

        val certs = arrayOf(X509Utilities.createSelfSignedCACert("CORDA_CLIENT_CA").certificate,
                X509Utilities.createSelfSignedCACert("CORDA_INTERMEDIATE_CA").certificate,
                X509Utilities.createSelfSignedCACert("CORDA_ROOT_CA").certificate)

        val certService: CertificateSigningService = mock {
            on { submitRequest(any()) }.then { id }
            on { retrieveCertificates(eq(id)) }.then { certs }
        }


        val config = object : NodeConfiguration {
            override val basedir: Path = tempFolder.root.toPath()
            override val myLegalName: String = "me"
            override val nearestCity: String = "London"
            override val emailAddress: String = ""
            override val devMode: Boolean = true
            override val exportJMXto: String = ""
            override val keyStorePassword: String = "testpass"
            override val trustStorePassword: String = "trustpass"
        }

        assertFalse(config.keyStorePath.exists())
        assertFalse(config.trustStorePath.exists())

        CertificateSigner(config, certService).buildKeyStore()

        assertTrue(config.keyStorePath.exists())
        assertTrue(config.trustStorePath.exists())

        X509Utilities.loadKeyStore(config.keyStorePath, config.keyStorePassword).run {
            assertTrue(containsAlias(X509Utilities.CORDA_CLIENT_CA_PRIVATE_KEY))
            assertTrue(containsAlias(X509Utilities.CORDA_CLIENT_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_INTERMEDIATE_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_INTERMEDIATE_CA_PRIVATE_KEY))
            assertFalse(containsAlias(X509Utilities.CORDA_ROOT_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_ROOT_CA_PRIVATE_KEY))
        }

        X509Utilities.loadKeyStore(config.trustStorePath, config.trustStorePassword).run {
            assertFalse(containsAlias(X509Utilities.CORDA_CLIENT_CA_PRIVATE_KEY))
            assertFalse(containsAlias(X509Utilities.CORDA_CLIENT_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_INTERMEDIATE_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_INTERMEDIATE_CA_PRIVATE_KEY))
            assertTrue(containsAlias(X509Utilities.CORDA_ROOT_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_ROOT_CA_PRIVATE_KEY))
        }

        assertEquals(id, (config.certificatesPath / "certificate-request-id.txt").readLines { it.findFirst().get() })
    }

}
