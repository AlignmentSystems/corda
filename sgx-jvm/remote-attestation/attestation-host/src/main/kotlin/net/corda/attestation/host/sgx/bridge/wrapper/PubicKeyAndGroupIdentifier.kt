package net.corda.attestation.host.sgx.bridge.wrapper

import net.corda.attestation.host.sgx.system.GroupIdentifier

/**
 * The result of a call to [NativeWrapper.getPublicKeyAndGroupIdentifier].
 */
class PublicKeyAndGroupIdentifier(
    /**
     * The public elliptic curve key of the application enclave (of type
     * [ECKey] downstream).
     */
    val publicKey: ByteArray,

    /**
     * The identifier of the EPID group to which the enclave belongs.
     */
    val groupIdentifier: GroupIdentifier,

    /**
     * The result of the operation (of type [SgxStatus] downstream).
     */
    val result: Long
)
