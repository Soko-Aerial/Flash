package com.transfer.flash.server.directory

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * HTTP REST routes for Organization Directory and Scoped Group Discovery:
 * - GET /api/v1/directory/groups: Scoped visible groups for the calling device
 * - GET /api/v1/directory/members: Members within a department
 * - POST /api/v1/groups/join-request: Requests to join a group
 * - GET /api/v1/directory/departments: Department list
 */
fun Route.directoryRoutes(directory: OrganizationDirectory) {
    route("/api/v1") {
        route("/directory") {
            // GET /api/v1/directory/groups
            get("/groups") {
                val deviceId = call.request.queryParameters["deviceId"]
                    ?: call.request.headers["X-Device-Id"]

                if (deviceId.isNullOrBlank()) {
                    call.respondText(
                        text = """{"error":"Missing required 'deviceId' parameter or 'X-Device-Id' header"}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest
                    )
                    return@get
                }

                val department = call.request.queryParameters["department"]
                    ?: call.request.headers["X-Department-Id"]

                val visible = directory.listVisibleGroups(userDepartment = department, userDeviceId = deviceId)
                val json = buildGroupListJson(visible)
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            }

            // GET /api/v1/directory/members
            get("/members") {
                val departmentId = call.request.queryParameters["departmentId"]
                    ?: call.request.headers["X-Department-Id"]

                if (departmentId.isNullOrBlank()) {
                    call.respondText(
                        text = """{"error":"Missing required 'departmentId' parameter or 'X-Department-Id' header"}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest
                    )
                    return@get
                }

                val members = directory.listDepartmentMembers(departmentId)
                val json = buildMemberListJson(members)
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            }

            // GET /api/v1/directory/departments
            get("/departments") {
                val depts = directory.listDepartments()
                val json = buildDepartmentListJson(depts)
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            }
        }

        route("/groups") {
            // POST /api/v1/groups/join-request
            post("/join-request") {
                val payload = extractJoinPayload(call)
                val groupId = payload.groupId
                val deviceId = payload.deviceId
                val departmentId = payload.departmentId

                if (groupId.isNullOrBlank() || deviceId.isNullOrBlank()) {
                    call.respondText(
                        text = """{"error":"Missing required 'groupId' or 'deviceId'"}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest
                    )
                    return@post
                }

                when (val result = directory.requestJoinGroup(groupId, deviceId, departmentId)) {
                    is JoinRequestResult.Joined -> {
                        call.respondText(
                            text = """{"status":"JOINED","groupId":"${escapeJson(result.group.id)}","name":"${escapeJson(result.group.name)}"}""",
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.OK
                        )
                    }
                    is JoinRequestResult.Pending -> {
                        call.respondText(
                            text = """{"status":"PENDING","requestId":"${escapeJson(result.request.id)}","groupId":"${escapeJson(groupId)}"}""",
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.Accepted
                        )
                    }
                    is JoinRequestResult.Rejected -> {
                        call.respondText(
                            text = """{"status":"REJECTED","reason":"${escapeJson(result.reason)}"}""",
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.Forbidden
                        )
                    }
                }
            }
        }
    }
}

private data class JoinPayload(
    val groupId: String?,
    val deviceId: String?,
    val departmentId: String?
)

private suspend fun extractJoinPayload(call: ApplicationCall): JoinPayload {
    var rawText = ""
    try {
        rawText = call.receiveText()
    } catch (_: Exception) {
    }

    if (rawText.isNotBlank()) {
        val groupId = extractJsonField(rawText, "groupId")
        val deviceId = extractJsonField(rawText, "deviceId")
        val deptId = extractJsonField(rawText, "departmentId")
        if (groupId != null || deviceId != null) {
            return JoinPayload(groupId = groupId, deviceId = deviceId, departmentId = deptId)
        }
    }

    // Try query parameters
    val qGroupId = call.request.queryParameters["groupId"]
    val qDeviceId = call.request.queryParameters["deviceId"]
    val qDeptId = call.request.queryParameters["departmentId"]
    if (qGroupId != null || qDeviceId != null) {
        return JoinPayload(groupId = qGroupId, deviceId = qDeviceId, departmentId = qDeptId)
    }

    // Try form parameters
    return try {
        val params = call.receiveParameters()
        JoinPayload(
            groupId = params["groupId"],
            deviceId = params["deviceId"],
            departmentId = params["departmentId"]
        )
    } catch (_: Exception) {
        JoinPayload(null, null, null)
    }
}

private fun extractJsonField(json: String, field: String): String? {
    val regex = Regex("""\"$field\"\s*:\s*\"([^\"]*)\"""")
    return regex.find(json)?.groupValues?.get(1)
}

internal fun escapeJson(str: String): String {
    return buildString {
        for (ch in str) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append(String.format("\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
}

private fun buildGroupListJson(groups: List<GroupCharter>): String {
    val items = groups.joinToString(",") { g ->
        val admins = g.adminDeviceIds.joinToString(",") { "\"${escapeJson(it)}\"" }
        val members = g.memberDeviceIds.joinToString(",") { "\"${escapeJson(it)}\"" }
        """{"id":"${escapeJson(g.id)}","name":"${escapeJson(g.name)}","departmentId":"${escapeJson(g.departmentId)}","visibility":"${g.visibility.name}","joinMode":"${g.joinMode.name}","adminDeviceIds":[$admins],"memberDeviceIds":[$members]}"""
    }
    return "[$items]"
}

private fun buildMemberListJson(members: List<DepartmentMember>): String {
    val items = members.joinToString(",") { m ->
        """{"deviceId":"${escapeJson(m.deviceId)}","departmentId":"${escapeJson(m.departmentId)}","displayName":"${escapeJson(m.displayName)}","role":"${m.role.name}"}"""
    }
    return "[$items]"
}

private fun buildDepartmentListJson(departments: List<Department>): String {
    val items = departments.joinToString(",") { d ->
        val admins = d.adminDeviceIds.joinToString(",") { "\"${escapeJson(it)}\"" }
        """{"id":"${escapeJson(d.id)}","name":"${escapeJson(d.name)}","adminDeviceIds":[$admins]}"""
    }
    return "[$items]"
}
