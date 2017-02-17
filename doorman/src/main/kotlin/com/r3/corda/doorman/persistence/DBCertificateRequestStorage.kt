package com.r3.corda.doorman.persistence

import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.commonName
import net.corda.node.utilities.*
import org.jetbrains.exposed.sql.*
import java.security.cert.Certificate
import java.time.Instant

// TODO Relax the uniqueness requirement to be on the entire X.500 subject rather than just the legal name
class DBCertificateRequestStorage(private val database: Database) : CertificationRequestStorage {
    private object DataTable : Table("certificate_signing_request") {
        val requestId = varchar("request_id", 64).index().primaryKey()
        val hostName = varchar("hostName", 100)
        val ipAddress = varchar("ip_address", 15)
        val legalName = varchar("legal_name", 256)
        // TODO : Do we need to store this in column? or is it ok with blob.
        val request = blob("request")
        val requestTimestamp = instant("request_timestamp")
        val processTimestamp = instant("process_timestamp").nullable()
        val certificate = blob("certificate").nullable()
        val rejectReason = varchar("reject_reason", 256).nullable()
    }

    init {
        // Create table if not exists.
        databaseTransaction(database) {
            SchemaUtils.create(DataTable)
        }
    }

    override fun saveRequest(certificationData: CertificationRequestData): String {
        val legalName = certificationData.request.subject.commonName
        val requestId = SecureHash.randomSHA256().toString()
        databaseTransaction(database) {
            val duplicate = DataTable.select {
                // A duplicate legal name is one where a previously approved, or currently pending, request has the same legal name.
                // A rejected request with the same legal name doesn't count as a duplicate
                DataTable.legalName eq legalName and (DataTable.certificate.isNotNull() or DataTable.processTimestamp.isNull())
            }.any()
            val rejectReason = if (duplicate) {
                "Duplicate legal name"
            } else if ("[=,]".toRegex() in legalName) {
                "Legal name cannot contain '=' or ','"
            } else {
                null
            }
            val now = Instant.now()
            withFinalizables { finalizables ->
                DataTable.insert {
                    it[this.requestId] = requestId
                    it[hostName] = certificationData.hostName
                    it[ipAddress] = certificationData.ipAddress
                    it[this.legalName] = legalName
                    it[request] = serializeToBlob(certificationData.request, finalizables)
                    it[requestTimestamp] = now
                    if (rejectReason != null) {
                        it[this.rejectReason] = rejectReason
                        it[processTimestamp] = now
                    }
                }
            }
        }
        return requestId
    }

    override fun getResponse(requestId: String): CertificateResponse {
        return databaseTransaction(database) {
            val response = DataTable
                    .select { DataTable.requestId eq requestId and DataTable.processTimestamp.isNotNull() }
                    .map { Pair(it[DataTable.certificate], it[DataTable.rejectReason]) }
                    .singleOrNull()
            if (response == null) {
                CertificateResponse.NotReady
            } else {
                val (certificate, rejectReason) = response
                if (certificate != null) {
                    CertificateResponse.Ready(deserializeFromBlob<Certificate>(certificate))
                } else {
                    CertificateResponse.Unauthorised(rejectReason!!)
                }
            }
        }
    }

    override fun approveRequest(requestId: String, generateCertificate: CertificationRequestData.() -> Certificate) {
        databaseTransaction(database) {
            val request = singleRequestWhere { DataTable.requestId eq requestId and DataTable.processTimestamp.isNull() }
            if (request != null) {
                withFinalizables { finalizables ->
                    DataTable.update({ DataTable.requestId eq requestId }) {
                        it[certificate] = serializeToBlob(request.generateCertificate(), finalizables)
                        it[processTimestamp] = Instant.now()
                    }
                }
            }
        }
    }

    override fun rejectRequest(requestId: String, rejectReason: String) {
        databaseTransaction(database) {
            val request = singleRequestWhere { DataTable.requestId eq requestId and DataTable.processTimestamp.isNull() }
            if (request != null) {
                DataTable.update({ DataTable.requestId eq requestId }) {
                    it[this.rejectReason] = rejectReason
                    it[processTimestamp] = Instant.now()
                }
            }
        }
    }

    override fun getRequest(requestId: String): CertificationRequestData? {
        return databaseTransaction(database) {
            singleRequestWhere { DataTable.requestId eq requestId }
        }
    }

    override fun getPendingRequestIds(): List<String> {
        return databaseTransaction(database) {
            DataTable.select { DataTable.processTimestamp.isNull() }.map { it[DataTable.requestId] }
        }
    }

    override fun getApprovedRequestIds(): List<String> = emptyList()

    private fun singleRequestWhere(where: SqlExpressionBuilder.() -> Op<Boolean>): CertificationRequestData? {
        return DataTable
                .select(where)
                .map { CertificationRequestData(it[DataTable.hostName], it[DataTable.ipAddress], deserializeFromBlob(it[DataTable.request])) }
                .singleOrNull()
    }
}