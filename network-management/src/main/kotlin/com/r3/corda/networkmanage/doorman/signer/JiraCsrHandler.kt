package com.r3.corda.networkmanage.doorman.signer

import com.r3.corda.networkmanage.common.persistence.CertificateResponse
import com.r3.corda.networkmanage.common.persistence.CertificateSigningRequest
import com.r3.corda.networkmanage.common.persistence.CertificationRequestStorage
import com.r3.corda.networkmanage.common.persistence.RequestStatus
import com.r3.corda.networkmanage.doorman.ApprovedRequest
import com.r3.corda.networkmanage.doorman.JiraClient
import com.r3.corda.networkmanage.doorman.RejectedRequest
import net.corda.core.utilities.contextLogger
import org.bouncycastle.pkcs.PKCS10CertificationRequest

class JiraCsrHandler(private val jiraClient: JiraClient, private val storage: CertificationRequestStorage, private val delegate: CsrHandler) : CsrHandler by delegate {
    private companion object {
        val log = contextLogger()
    }

    override fun saveRequest(rawRequest: PKCS10CertificationRequest): String {
        val requestId = delegate.saveRequest(rawRequest)
        // Make sure request has been accepted.
        try {
            if (delegate.getResponse(requestId) !is CertificateResponse.Unauthorised) {
                jiraClient.createRequestTicket(requestId, rawRequest)
                storage.markRequestTicketCreated(requestId)
            }
        } catch (e: Exception) {
            log.warn("There was an error while creating Jira tickets", e)
        } finally {
            return requestId
        }
    }

    override fun processRequests() {
        createTickets()
        val (approvedRequests, rejectedRequests) = updateRequestStatus()
        delegate.processRequests()
        updateJiraTickets(approvedRequests, rejectedRequests)
    }

    private fun updateRequestStatus(): Pair<List<ApprovedRequest>, List<RejectedRequest>> {
        // Update local request statuses.
        val approvedRequest = jiraClient.getApprovedRequests()
        approvedRequest.forEach { (id, approvedBy) -> storage.approveRequest(id, approvedBy) }
        val rejectedRequest = jiraClient.getRejectedRequests()
        rejectedRequest.forEach { (id, rejectedBy, reason) -> storage.rejectRequest(id, rejectedBy, reason) }
        return Pair(approvedRequest, rejectedRequest)
    }

    private fun updateJiraTickets(approvedRequest: List<ApprovedRequest>, rejectedRequest: List<RejectedRequest>) {
        // Reconfirm request status and update jira status
        val signedRequests = approvedRequest.mapNotNull { storage.getRequest(it.requestId) }
                .filter { it.status == RequestStatus.DONE && it.certData != null }
                .associateBy { it.requestId }
                .mapValues { it.value.certData!!.certPath }
        jiraClient.updateSignedRequests(signedRequests)

        val rejectedRequestIDs = rejectedRequest.mapNotNull { storage.getRequest(it.requestId) }
                .filter { it.status == RequestStatus.REJECTED }
                .map { it.requestId }
        jiraClient.updateRejectedRequests(rejectedRequestIDs)
    }

    /**
     * Creates Jira tickets for all request in [RequestStatus.NEW] state.
     *
     * Usually requests are expected to move to the [RequestStatus.TICKET_CREATED] state immediately,
     * they might be left in the [RequestStatus.NEW] state if Jira is down.
     */
    private fun createTickets() {
        storage.getRequests(RequestStatus.NEW).forEach {
            try {
                createTicket(it)
            } catch (e: Exception) {
                log.warn("There were errors while creating Jira tickets for request '${it.requestId}'", e)
            }
        }
    }

    private fun createTicket(signingRequest: CertificateSigningRequest) {
        jiraClient.createRequestTicket(signingRequest.requestId, signingRequest.request)
        storage.markRequestTicketCreated(signingRequest.requestId)
    }
}
