package com.r3.corda.networkmanage.common.persistence

import com.r3.corda.networkmanage.common.persistence.entity.CertificateDataEntity
import com.r3.corda.networkmanage.common.persistence.entity.CertificateRevocationRequestEntity
import com.r3.corda.networkmanage.common.persistence.entity.CertificateSigningRequestEntity
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.nodeapi.internal.network.CertificateRevocationRequest
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseTransaction
import net.corda.nodeapi.internal.persistence.TransactionIsolationLevel
import java.math.BigInteger
import java.security.cert.X509Certificate
import java.time.Instant

class PersistentCertificateRevocationRequestStorage(private val database: CordaPersistence) : CertificateRevocationRequestStorage {
    override fun saveRevocationRequest(request: CertificateRevocationRequest): String {
        return database.transaction(TransactionIsolationLevel.SERIALIZABLE) {
            // Get matching CSR
            val csr = retrieveCsr(request.certificateSerialNumber, request.csrRequestId, request.legalName)
            csr ?: throw IllegalArgumentException("No CSR matching the given criteria was found")
            // Check if there is an entry for the given certificate serial number
            val revocation = uniqueEntityWhere<CertificateRevocationRequestEntity> { builder, path ->
                val serialNumberEqual = builder.equal(path.get<BigInteger>(CertificateRevocationRequestEntity::certificateSerialNumber.name), request.certificateSerialNumber)
                val statusNotEqualRejected = builder.notEqual(path.get<RequestStatus>(CertificateRevocationRequestEntity::status.name), RequestStatus.REJECTED)
                builder.and(serialNumberEqual, statusNotEqualRejected)
            }
            if (revocation != null) {
                revocation.requestId
            } else {
                val certificateData = csr.certificateData!!
                requireNotNull(certificateData) { "The certificate with the given serial number cannot be found." }
                val requestId = SecureHash.randomSHA256().toString()
                session.save(CertificateRevocationRequestEntity(
                        certificateSerialNumber = certificateData.certificateSerialNumber,
                        revocationReason = request.reason,
                        requestId = requestId,
                        modifiedBy = request.reporter,
                        certificateData = certificateData,
                        reporter = request.reporter,
                        legalName = certificateData.legalName
                ))
                requestId
            }
        }
    }

    private fun DatabaseTransaction.retrieveCsr(certificateSerialNumber: BigInteger?, csrRequestId: String?, legalName: CordaX500Name?): CertificateSigningRequestEntity? {
        val csr = if (csrRequestId != null) {
            uniqueEntityWhere<CertificateSigningRequestEntity> { builder, path ->
                builder.and(
                        builder.equal(path.get<String>(CertificateSigningRequestEntity::requestId.name), csrRequestId),
                        builder.notEqual(path.get<RequestStatus>(CertificateSigningRequestEntity::status.name), RequestStatus.REJECTED),
                        builder.equal(path
                                .get<CertificateDataEntity>(CertificateSigningRequestEntity::certificateData.name)
                                .get<CertificateStatus>(CertificateDataEntity::certificateStatus.name), CertificateStatus.VALID)
                )
            }
        } else if (legalName != null) {
            uniqueEntityWhere<CertificateSigningRequestEntity> { builder, path ->
                builder.and(
                        builder.equal(path.get<String>(CertificateSigningRequestEntity::legalName.name), legalName.toString()),
                        builder.notEqual(path.get<RequestStatus>(CertificateSigningRequestEntity::status.name), RequestStatus.REJECTED),
                        builder.equal(path
                                .get<CertificateDataEntity>(CertificateSigningRequestEntity::certificateData.name)
                                .get<CertificateStatus>(CertificateDataEntity::certificateStatus.name), CertificateStatus.VALID)
                )
            }
        } else {
            val certificateData = uniqueEntityWhere<CertificateDataEntity> { builder, path ->
                builder.and(
                        builder.equal(path.get<CertificateStatus>(CertificateDataEntity::certificateStatus.name), CertificateStatus.VALID),
                        builder.equal(path.get<String>(CertificateDataEntity::certificateSerialNumber.name), certificateSerialNumber)
                )
            }
            certificateData?.certificateSigningRequest
        }
        return csr?.let {
            validateCsrConsistency(certificateSerialNumber, csrRequestId, legalName, it)
        }
    }

    private fun validateCsrConsistency(certificateSerialNumber: BigInteger?,
                                       csrRequestId: String?,
                                       legalName: CordaX500Name?,
                                       result: CertificateSigningRequestEntity): CertificateSigningRequestEntity {
        val certData = result.certificateData!!
        require(legalName == null || result.legalName == legalName) {
            "The legal name does not match."
        }
        require(csrRequestId == null || result.requestId == csrRequestId) {
            "The CSR request ID does not match."
        }
        require(certificateSerialNumber == null || certData.certificateSerialNumber == certificateSerialNumber) {
            "The certificate serial number does not match."
        }
        return result
    }

    override fun getRevocationRequest(requestId: String): CertificateRevocationRequestData? {
        return database.transaction {
            getRevocationRequestEntity(requestId)?.toCertificateRevocationRequestData()
        }
    }

    override fun getRevocationRequests(revocationStatus: RequestStatus): List<CertificateRevocationRequestData> {
        return database.transaction {
            val builder = session.criteriaBuilder
            val query = builder.createQuery(CertificateRevocationRequestEntity::class.java).run {
                from(CertificateRevocationRequestEntity::class.java).run {
                    where(builder.equal(get<RequestStatus>(CertificateRevocationRequestEntity::status.name), revocationStatus))
                }
            }
            session.createQuery(query).resultList.map { it.toCertificateRevocationRequestData() }
        }
    }

    override fun approveRevocationRequest(requestId: String, approvedBy: String) {
        database.transaction {
            val revocation = getRevocationRequestEntity(requestId)
            if (revocation == null) {
                throw NoSuchElementException("Error while approving! Certificate revocation id=$id does not exist")
            } else {
                session.merge(revocation.copy(
                        status = RequestStatus.APPROVED,
                        modifiedAt = Instant.now(),
                        modifiedBy = approvedBy
                ))
            }
        }
    }

    override fun rejectRevocationRequest(requestId: String, rejectedBy: String, reason: String?) {
        database.transaction {
            val revocation = getRevocationRequestEntity(requestId)
            if (revocation == null) {
                throw NoSuchElementException("Error while rejecting! Certificate revocation id=$id does not exist")
            } else {
                session.merge(revocation.copy(
                        status = RequestStatus.REJECTED,
                        modifiedAt = Instant.now(),
                        modifiedBy = rejectedBy,
                        remark = reason
                ))
            }
        }
    }

    override fun markRequestTicketCreated(requestId: String) {
        // Even though, we have an assumption that there is always a single instance of the doorman service running,
        // the SERIALIZABLE isolation level is used here just to ensure data consistency between the updates.
        return database.transaction(TransactionIsolationLevel.SERIALIZABLE) {
            val request = getRevocationRequestEntity(requestId, RequestStatus.NEW)
            request ?: throw IllegalArgumentException("Error when creating request ticket with id: $requestId. Request does not exist or its status is not NEW.")
            val update = request.copy(
                    modifiedAt = Instant.now(),
                    status = RequestStatus.TICKET_CREATED)
            session.merge(update)
        }
    }

    private fun getRevocationRequestEntity(requestId: String, status: RequestStatus? = null): CertificateRevocationRequestEntity? {
        return database.transaction {
            uniqueEntityWhere { builder, path ->
                val idEqual = builder.equal(path.get<String>(CertificateRevocationRequestEntity::requestId.name), requestId)
                if (status == null) {
                    idEqual
                } else {
                    builder.and(idEqual, builder.equal(path.get<RequestStatus>(CertificateRevocationRequestEntity::status.name), status))
                }
            }
        }
    }

    private fun CertificateRevocationRequestEntity.toCertificateRevocationRequestData(): CertificateRevocationRequestData {
        return CertificateRevocationRequestData(
                requestId,
                certificateData.certificateSigningRequest.requestId,
                certificateData.certPath.certificates.first() as X509Certificate,
                certificateSerialNumber,
                modifiedAt,
                legalName,
                status,
                revocationReason,
                reporter
        )
    }
}
