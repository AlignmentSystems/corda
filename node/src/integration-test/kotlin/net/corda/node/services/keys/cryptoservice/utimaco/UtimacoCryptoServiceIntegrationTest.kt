package net.corda.node.services.keys.cryptoservice.utimaco

import net.corda.core.crypto.Crypto
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.toPath
import net.corda.core.utilities.days
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.testing.core.getTestPartyAndCertificate
import org.junit.Rule
import org.junit.Test
import net.corda.node.hsm.HsmSimulator
import net.corda.nodeapi.internal.cryptoservice.CryptoServiceException
import net.corda.testing.core.DUMMY_BANK_A_NAME
import java.io.IOException
import java.time.Duration
import java.util.*
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtimacoCryptoServiceIntegrationTest {

    @Rule
    @JvmField
    val hsmSimulator: HsmSimulator = HsmSimulator()

    val config = testConfig(hsmSimulator.port)
    val login = { UtimacoCryptoService.UtimacoCredentials("INTEGRATION_TEST", "INTEGRATION_TEST".toByteArray()) }

    @Test
    fun `When credentials are incorrect, should throw UtimacoHSMException`() {
        val config = testConfig(hsmSimulator.port)
        assertFailsWith<UtimacoCryptoService.UtimacoHSMException> {
            UtimacoCryptoService.fromConfig(config) {
                UtimacoCryptoService.UtimacoCredentials("invalid", "invalid".toByteArray())
            }
        }
    }

    @Test
    fun `When credentials become incorrect, should throw UtimacoHSMException`() {
        var pw = "INTEGRATION_TEST"
        val cryptoService = UtimacoCryptoService.fromConfig(config) { UtimacoCryptoService.UtimacoCredentials("INTEGRATION_TEST", pw.toByteArray()) }
        cryptoService.logOff()
        pw = "foo"
        assertFailsWith<UtimacoCryptoService.UtimacoHSMException> { cryptoService.generateKeyPair("foo", Crypto.ECDSA_SECP256R1_SHA256.schemeNumberID) }
    }

    @Test
    fun `When connection cannot be established, should throw ConnectionException`() {
        val invalidConfig = testConfig(1)
        assertFailsWith<IOException> {
            UtimacoCryptoService.fromConfig(invalidConfig, login)
        }
    }

    @Test
    fun `When alias contains illegal characters, should throw `() {
        val cryptoService = UtimacoCryptoService.fromConfig(config, login)
        val alias = "a".repeat(257)
        assertFailsWith<UtimacoCryptoService.UtimacoHSMException> { cryptoService.generateKeyPair(alias, Crypto.ECDSA_SECP256R1_SHA256.schemeNumberID) }
    }

    @Test
    fun `Handles re-authentication properly`() {
        val cryptoService = UtimacoCryptoService.fromConfig(config, login)
        val alias = UUID.randomUUID().toString()
        cryptoService.logOff()
        val pubKey = cryptoService.generateKeyPair(alias, Crypto.ECDSA_SECP256R1_SHA256.schemeNumberID)
        assertTrue { cryptoService.containsKey(alias) }
        val data = UUID.randomUUID().toString().toByteArray()
        cryptoService.logOff()
        val signed = cryptoService.sign(alias, data)
        Crypto.doVerify(pubKey, signed, data)
    }

    @Test
    fun `Generate ECDSA key with r1 curve, then sign and verify data`() {
        val cryptoService = UtimacoCryptoService.fromConfig(config, login)
        val alias = UUID.randomUUID().toString()
        val pubKey = cryptoService.generateKeyPair(alias, Crypto.ECDSA_SECP256R1_SHA256.schemeNumberID)
        assertTrue { cryptoService.containsKey(alias) }
        val data = UUID.randomUUID().toString().toByteArray()
        val signed = cryptoService.sign(alias, data)
        Crypto.doVerify(pubKey, signed, data)
    }

    @Test
    fun `Generate ECDSA key with k1 curve, then sign and verify data`() {
        val cryptoService = UtimacoCryptoService.fromConfig(config, login)
        val alias = UUID.randomUUID().toString()
        val pubKey = cryptoService.generateKeyPair(alias, Crypto.ECDSA_SECP256K1_SHA256.schemeNumberID)
        assertTrue { cryptoService.containsKey(alias) }
        val data = UUID.randomUUID().toString().toByteArray()
        val signed = cryptoService.sign(alias, data)
        Crypto.doVerify(pubKey, signed, data)
    }

    @Test
    fun `Generate RSA key, then sign and verify data`() {
        val cryptoService = UtimacoCryptoService.fromConfig(config, login)
        val alias = UUID.randomUUID().toString()
        val pubKey = cryptoService.generateKeyPair(alias, Crypto.RSA_SHA256.schemeNumberID)
        assertTrue { cryptoService.containsKey(alias) }
        val data = UUID.randomUUID().toString().toByteArray()
        val signed = cryptoService.sign(alias, data)
        Crypto.doVerify(pubKey, signed, data)
    }

    @Test
    fun `When key does not exist, signing should throw`() {
        val cryptoService = UtimacoCryptoService.fromConfig(config, login)
        val alias = UUID.randomUUID().toString()
        assertFalse { cryptoService.containsKey(alias) }
        val data = UUID.randomUUID().toString().toByteArray()
        assertFailsWith<CryptoServiceException> { cryptoService.sign(alias, data) }
    }

    @Test
    fun `When key does not exist, getPublicKey should return null`() {
        val cryptoService = UtimacoCryptoService.fromConfig(config, login)
        val alias = UUID.randomUUID().toString()
        assertFalse { cryptoService.containsKey(alias) }
        assertNull(cryptoService.getPublicKey(alias))
    }

    @Test
    fun `When key does not exist, getContentSigner should throw`() {
        val cryptoService = UtimacoCryptoService.fromConfig(config)
        { UtimacoCryptoService.UtimacoCredentials("INTEGRATION_TEST", "INTEGRATION_TEST".toByteArray()) }
        val alias = UUID.randomUUID().toString()
        assertFalse { cryptoService.containsKey(alias) }
        assertFailsWith<CryptoServiceException> { cryptoService.getSigner(alias) }
    }

    @Test
    fun `Content signer works with X509Utilities`() {
        val cryptoService = UtimacoCryptoService.fromConfig(config, login)
        val alias = UUID.randomUUID().toString()
        val pubKey = cryptoService.generateKeyPair(alias, Crypto.ECDSA_SECP256R1_SHA256.schemeNumberID)
        val signer = cryptoService.getSigner(alias)
        val otherAlias = UUID.randomUUID().toString()
        val otherPubKey = cryptoService.generateKeyPair(otherAlias, Crypto.ECDSA_SECP256R1_SHA256.schemeNumberID)
        val issuer = Party(DUMMY_BANK_A_NAME, pubKey)
        val partyAndCert = getTestPartyAndCertificate(issuer)
        val issuerCert = partyAndCert.certificate
        val window = X509Utilities.getCertificateValidityWindow(Duration.ZERO, 3650.days, issuerCert)
        val ourCertificate = X509Utilities.createCertificate(
                CertificateType.CONFIDENTIAL_LEGAL_IDENTITY,
                issuerCert.subjectX500Principal,
                issuerCert.publicKey,
                signer,
                partyAndCert.name.x500Principal,
                otherPubKey,
                window)
        ourCertificate.checkValidity()
    }

    @Test
    fun `login with key file`() {
        // the admin user of the simulator is set up with key-file login
        val keyFile = UtimacoCryptoServiceIntegrationTest::class.java.getResource("ADMIN.keykey").toPath()
        val username = "ADMIN"
        val pw = "utimaco".toByteArray()
        val conf = config.copy(authThreshold = 0) // because auth state for the admin user is 570425344
        val cryptoService = UtimacoCryptoService.fromConfig(conf) { UtimacoCryptoService.UtimacoCredentials(username, pw, keyFile) }
        // the admin user does not have permission to access or create keys, so this operation will fail
        assertFailsWith<UtimacoCryptoService.UtimacoHSMException> { cryptoService.generateKeyPair("no", Crypto.ECDSA_SECP256R1_SHA256.schemeNumberID) }
    }
}