package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * REST HTTP client for querying the Enterprise Organization Directory and Scoped Groups.
 */
@FlashInternalApi
public class EnterpriseDirectoryClient(
    private val httpClient: HttpClient = createDefaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Lists visible groups within the calling device's authorized scope and department.
     */
    public suspend fun fetchVisibleGroups(
        hubUrl: String,
        deviceId: String,
        department: String? = null,
    ): FlashResult<List<EnterpriseDirectoryGroup>> = withContext(ioDispatcher) {
        return@withContext try {
            val base = normalizeUrl(hubUrl)
            val queryParams = buildString {
                append("?deviceId=").append(deviceId)
                if (!department.isNullOrBlank()) {
                    append("&department=").append(department)
                }
            }
            val url = "$base/api/v1/directory/groups$queryParams"
            val response = httpClient.get(url) {
                header("X-Device-Id", deviceId)
                if (!department.isNullOrBlank()) {
                    header("X-Department-Id", department)
                }
            }

            if (response.status != HttpStatusCode.OK) {
                return@withContext FlashResult.Failure(
                    FlashError.Unknown("Failed to fetch groups: HTTP ${response.status}")
                )
            }

            val groups = EnterpriseJsonHelper.parseDirectoryGroups(response.bodyAsText())
            FlashResult.Success(groups)
        } catch (error: Throwable) {
            FlashResult.Failure(FlashError.Unknown(error.message ?: "Failed to fetch groups", error))
        }
    }

    /**
     * Lists all members enrolled in the specified [departmentId].
     */
    public suspend fun fetchDepartmentMembers(
        hubUrl: String,
        departmentId: String,
    ): FlashResult<List<EnterpriseDirectoryMember>> = withContext(ioDispatcher) {
        return@withContext try {
            val base = normalizeUrl(hubUrl)
            val url = "$base/api/v1/directory/members?departmentId=$departmentId"
            val response = httpClient.get(url) {
                header("X-Department-Id", departmentId)
            }

            if (response.status != HttpStatusCode.OK) {
                return@withContext FlashResult.Failure(
                    FlashError.Unknown("Failed to fetch department members: HTTP ${response.status}")
                )
            }

            val members = EnterpriseJsonHelper.parseDirectoryMembers(response.bodyAsText())
            FlashResult.Success(members)
        } catch (error: Throwable) {
            FlashResult.Failure(FlashError.Unknown(error.message ?: "Failed to fetch department members", error))
        }
    }

    /**
     * Lists all departments configured on the enterprise hub.
     */
    public suspend fun fetchDepartments(
        hubUrl: String,
    ): FlashResult<List<EnterpriseDepartment>> = withContext(ioDispatcher) {
        return@withContext try {
            val base = normalizeUrl(hubUrl)
            val url = "$base/api/v1/directory/departments"
            val response = httpClient.get(url)

            if (response.status != HttpStatusCode.OK) {
                return@withContext FlashResult.Failure(
                    FlashError.Unknown("Failed to fetch departments: HTTP ${response.status}")
                )
            }

            val departments = EnterpriseJsonHelper.parseDepartments(response.bodyAsText())
            FlashResult.Success(departments)
        } catch (error: Throwable) {
            FlashResult.Failure(FlashError.Unknown(error.message ?: "Failed to fetch departments", error))
        }
    }

    /**
     * Sends a request to join a restricted or approval-required group.
     */
    public suspend fun requestJoinGroup(
        hubUrl: String,
        groupId: String,
        deviceId: String,
        departmentId: String? = null,
    ): FlashResult<Boolean> = withContext(ioDispatcher) {
        return@withContext try {
            val base = normalizeUrl(hubUrl)
            val url = "$base/api/v1/groups/join-request"
            val payload = buildString {
                append("{")
                append(""""groupId":"${EnterpriseJsonHelper.escape(groupId)}",""")
                append(""""deviceId":"${EnterpriseJsonHelper.escape(deviceId)}",""")
                append(""""departmentId":"${EnterpriseJsonHelper.escape(departmentId.orEmpty())}"""")
                append("}")
            }

            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                FlashResult.Success(true)
            } else {
                FlashResult.Failure(FlashError.Unknown("Join request failed: HTTP ${response.status}"))
            }
        } catch (error: Throwable) {
            FlashResult.Failure(FlashError.Unknown(error.message ?: "Join request failed", error))
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "http://$trimmed"
        }
    }

    public companion object {
        public fun createDefaultClient(): HttpClient = HttpClient(CIO)
    }
}
