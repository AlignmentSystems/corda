package com.r3.corda.signing.persistence

import net.corda.node.utilities.CordaPersistence
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.hibernate.envers.Audited
import java.security.cert.CertPath
import java.sql.Connection
import java.time.Instant
import javax.persistence.*
import javax.persistence.criteria.CriteriaBuilder
import javax.persistence.criteria.Path
import javax.persistence.criteria.Predicate

data class ApprovedCertificateRequestData(val requestId: String, val request: PKCS10CertificationRequest, var certPath: CertPath? = null)

class DBCertificateRequestStorage(private val database: CordaPersistence) : CertificateRequestStorage {

    enum class Status {
        Approved, Signed
    }

    @Entity
    @Table(name = "certificate_signing_request")
    class CertificateSigningRequest(

            @Id
            @Column(name = "request_id", length = 64)
            var requestId: String = "",

            @Lob
            @Column
            var request: ByteArray = ByteArray(0),

            @Lob
            @Column(nullable = true)
            var certificatePath: ByteArray? = null,

            @Audited
            @Column(name = "status")
            @Enumerated(EnumType.STRING)
            var status: Status = Status.Approved,

            @Audited
            @Column(name = "modified_by", length = 512)
            @ElementCollection(targetClass = String::class, fetch = FetchType.EAGER)
            var modifiedBy: List<String> = emptyList(),

            @Audited
            @Column(name = "modified_at")
            var modifiedAt: Instant? = Instant.now()
    )

    override fun getApprovedRequests(): List<ApprovedCertificateRequestData> {
        return getRequestIdsByStatus(Status.Approved)
    }

    override fun sign(requests: List<ApprovedCertificateRequestData>, signers: List<String>) {
        requests.forEach {
            database.transaction(Connection.TRANSACTION_SERIALIZABLE) {
                val request = singleRequestWhere { builder, path ->
                    builder.and(
                            builder.equal(path.get<String>(CertificateSigningRequest::requestId.name), it.requestId),
                            builder.equal(path.get<String>(CertificateSigningRequest::status.name), Status.Approved)
                    )
                }
                if (request != null) {
                    val now = Instant.now()
                    request.certificatePath = it.certPath?.encoded
                    request.status = Status.Signed
                    request.modifiedAt = now
                    request.modifiedBy = signers
                    session.update(request)
                }
            }
        }
    }

    private fun singleRequestWhere(predicate: (CriteriaBuilder, Path<CertificateSigningRequest>) -> Predicate): CertificateSigningRequest? {
        return database.transaction {
            val builder = session.criteriaBuilder
            val criteriaQuery = builder.createQuery(CertificateSigningRequest::class.java)
            val query = criteriaQuery.from(CertificateSigningRequest::class.java).run {
                criteriaQuery.where(predicate(builder, this))
            }
            session.createQuery(query).uniqueResultOptional().orElse(null)
        }
    }

    private fun getRequestIdsByStatus(status: Status): List<ApprovedCertificateRequestData> {
        return database.transaction {
            val builder = session.criteriaBuilder
            val query = builder.createQuery(CertificateSigningRequest::class.java).run {
                from(CertificateSigningRequest::class.java).run {
                    where(builder.equal(get<Status>(CertificateSigningRequest::status.name), status))
                }
            }
            session.createQuery(query).setLockMode(LockModeType.PESSIMISTIC_WRITE).resultList.map { it.toRequestData() }
        }
    }

    private fun CertificateSigningRequest.toRequestData() = ApprovedCertificateRequestData(requestId, PKCS10CertificationRequest(request))
}