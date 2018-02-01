package com.r3.corda.networkmanage.doorman.webservice

import com.nhaarman.mockito_kotlin.*
import com.r3.corda.networkmanage.TestBase
import com.r3.corda.networkmanage.common.persistence.CertificateResponse
import com.r3.corda.networkmanage.doorman.NetworkManagementWebServer
import com.r3.corda.networkmanage.doorman.signer.CsrHandler
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.seconds
import net.corda.node.utilities.registration.cacheControl
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME
import net.corda.testing.internal.createDevIntermediateCaCertPath
import org.apache.commons.io.IOUtils
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralSubtree
import org.bouncycastle.asn1.x509.NameConstraints
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.HttpURLConnection.*
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.security.cert.CertPath
import java.security.cert.X509Certificate
import java.util.*
import java.util.zip.ZipInputStream
import javax.security.auth.x500.X500Principal
import javax.ws.rs.core.MediaType
import kotlin.test.assertEquals

class RegistrationWebServiceTest : TestBase() {
    private lateinit var webServer: NetworkManagementWebServer
    private lateinit var rootCaCert: X509Certificate
    private lateinit var intermediateCa: CertificateAndKeyPair
    private val pollInterval = 10.seconds

    @Before
    fun init() {
        val (rootCa, intermediateCa) = createDevIntermediateCaCertPath()
        rootCaCert = rootCa.certificate
        this.intermediateCa = intermediateCa
    }

    private fun startSigningServer(csrHandler: CsrHandler) {
        webServer = NetworkManagementWebServer(NetworkHostAndPort("localhost", 0), RegistrationWebService(csrHandler, pollInterval))
        webServer.start()
    }

    @After
    fun close() {
        webServer.close()
    }

    @Test
    fun `submit request`() {
        val id = SecureHash.randomSHA256().toString()

        val requestProcessor = mock<CsrHandler> {
            on { saveRequest(any()) }.then { id }
        }

        startSigningServer(requestProcessor)

        val keyPair = Crypto.generateKeyPair(DEFAULT_TLS_SIGNATURE_SCHEME)
        val request = X509Utilities.createCertificateSigningRequest(
                CordaX500Name(locality = "London", organisation = "Legal Name", country = "GB").x500Principal,
                "my@mail.com",
                keyPair)
        // Post request to signing server via http.

        assertEquals(id, submitRequest(request))
        verify(requestProcessor, times(1)).saveRequest(any())
        submitRequest(request)
        verify(requestProcessor, times(2)).saveRequest(any())
    }

    @Test
    fun `retrieve certificate`() {
        val keyPair = Crypto.generateKeyPair(DEFAULT_TLS_SIGNATURE_SCHEME)
        val id = SecureHash.randomSHA256().toString()

        val subject = CordaX500Name(locality = "London", organisation = "LegalName", country = "GB").x500Principal

        // Mock Storage behaviour.
        val certificateStore = mutableMapOf<String, CertPath>()
        val requestProcessor = mock<CsrHandler> {
            on { getResponse(eq(id)) }.then {
                certificateStore[id]?.let {
                    CertificateResponse.Ready(it)
                } ?: CertificateResponse.NotReady
            }
            on { processRequests() }.then {
                val request = X509Utilities.createCertificateSigningRequest(subject, "my@mail.com", keyPair)
                certificateStore[id] = JcaPKCS10CertificationRequest(request).run {
                    val tlsCert = X509Utilities.createCertificate(
                            CertificateType.TLS,
                            intermediateCa.certificate,
                            intermediateCa.keyPair,
                            X500Principal(subject.encoded),
                            publicKey)
                    X509Utilities.buildCertPath(tlsCert, intermediateCa.certificate, rootCaCert)
                }
                null
            }
        }

        startSigningServer(requestProcessor)

        val response = pollForResponse(id)
        assertEquals(pollInterval, (response as PollResponse.NotReady).pollInterval.seconds)

        requestProcessor.processRequests()

        val certificates = (pollForResponse(id) as PollResponse.Ready).certChain
        verify(requestProcessor, times(2)).getResponse(any())

        assertThat(certificates).hasSize(3)
        assertThat(certificates[0].subjectX500Principal).isEqualTo(subject)
        assertThat(certificates).endsWith(intermediateCa.certificate, rootCaCert)
    }

    @Test
    fun `retrieve certificate and create valid TLS certificate`() {
        val nodeCaKeyPair = Crypto.generateKeyPair(DEFAULT_TLS_SIGNATURE_SCHEME)
        val id = SecureHash.randomSHA256().toString()

        // Mock Storage behaviour.
        val certificateStore = mutableMapOf<String, CertPath>()
        val storage = mock<CsrHandler> {
            on { getResponse(eq(id)) }.then {
                certificateStore[id]?.let {
                    CertificateResponse.Ready(it)
                } ?: CertificateResponse.NotReady
            }
            on { processRequests() }.then {
                val request = X509Utilities.createCertificateSigningRequest(
                        CordaX500Name(locality = "London", organisation = "Legal Name", country = "GB").x500Principal,
                        "my@mail.com",
                        nodeCaKeyPair)
                certificateStore[id] = JcaPKCS10CertificationRequest(request).run {
                    val nameConstraints = NameConstraints(
                            arrayOf(GeneralSubtree(GeneralName(GeneralName.directoryName, X500Name("CN=LegalName, L=London")))),
                            arrayOf())
                    val clientCert = X509Utilities.createCertificate(
                            CertificateType.NODE_CA,
                            intermediateCa.certificate,
                            intermediateCa.keyPair,
                            X500Principal(subject.encoded),
                            publicKey,
                            nameConstraints = nameConstraints)
                    X509Utilities.buildCertPath(clientCert, intermediateCa.certificate, rootCaCert)
                }
                true
            }
        }

        startSigningServer(storage)
        val response = pollForResponse(id)
        assertEquals(pollInterval, (response as PollResponse.NotReady).pollInterval.seconds)

        storage.processRequests()

        val certificates = (pollForResponse(id) as PollResponse.Ready).certChain
        verify(storage, times(2)).getResponse(any())
        assertEquals(3, certificates.size)

        val sslKeyPair = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
        val sslCert = X509Utilities.createCertificate(
                CertificateType.TLS,
                certificates[0],
                nodeCaKeyPair,
                // TODO Investigate why X500Principal("CN=LegalName, L=London") results in a name constraints violation
                X500Principal(X500Name("CN=LegalName, L=London").encoded),
                sslKeyPair.public)

        // TODO: This is temporary solution, remove all certificate re-shaping after identity refactoring is done.
        X509Utilities.validateCertificateChain(rootCaCert, sslCert, *certificates.toTypedArray())
    }

    @Test
    fun `request not authorised`() {
        val id = SecureHash.randomSHA256().toString()

        val requestProcessor = mock<CsrHandler> {
            on { getResponse(eq(id)) }.then { CertificateResponse.Unauthorised("Not Allowed") }
        }

        startSigningServer(requestProcessor)
        assertThat(pollForResponse(id)).isEqualTo(PollResponse.Unauthorised("Not Allowed"))
    }

    private fun submitRequest(request: PKCS10CertificationRequest): String {
        val conn = URL("http://${webServer.hostAndPort}/certificate").openConnection() as HttpURLConnection
        conn.doOutput = true
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", MediaType.APPLICATION_OCTET_STREAM)
        conn.outputStream.write(request.encoded)
        return conn.inputStream.bufferedReader().use { it.readLine() }
    }

    private fun pollForResponse(id: String): PollResponse {
        val url = URL("http://${webServer.hostAndPort}/certificate/$id")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        return when (conn.responseCode) {
            HTTP_OK -> ZipInputStream(conn.inputStream).use {
                val certificates = ArrayList<X509Certificate>()
                val factory = X509CertificateFactory()
                while (it.nextEntry != null) {
                    certificates += factory.generateCertificate(it)
                }
                PollResponse.Ready(certificates)
            }
            HTTP_NO_CONTENT -> PollResponse.NotReady(conn.cacheControl().maxAgeSeconds())
            HTTP_UNAUTHORIZED -> PollResponse.Unauthorised(IOUtils.toString(conn.errorStream, UTF_8))
            else -> throw IOException("Cannot connect to Certificate Signing Server, HTTP response code : ${conn.responseCode}")
        }
    }

    private interface PollResponse {
        data class NotReady(val pollInterval: Int) : PollResponse
        data class Ready(val certChain: List<X509Certificate>) : PollResponse
        data class Unauthorised(val message: String) : PollResponse
    }
}