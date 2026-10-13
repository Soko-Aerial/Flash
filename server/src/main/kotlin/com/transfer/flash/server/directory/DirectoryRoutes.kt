package com.transfer.flash.server.directory

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.CreateDepartmentBody
import com.transfer.flash.core.hubprotocol.rest.CreateGroupBody
import com.transfer.flash.core.hubprotocol.rest.DepartmentDto
import com.transfer.flash.core.hubprotocol.rest.GroupDto
import com.transfer.flash.core.hubprotocol.rest.GroupMemberBody
import com.transfer.flash.core.hubprotocol.rest.HubDirectoryValues
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.JoinDecisionBody
import com.transfer.flash.core.hubprotocol.rest.JoinRequestBody
import com.transfer.flash.core.hubprotocol.rest.JoinRequestDto
import com.transfer.flash.core.hubprotocol.rest.JoinResponse
import com.transfer.flash.core.hubprotocol.rest.MeDto
import com.transfer.flash.core.hubprotocol.rest.MemberDto
import com.transfer.flash.core.hubprotocol.rest.OkResponse
import com.transfer.flash.core.hubprotocol.rest.UpdateGroupBody
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.auth.respondJson
import com.transfer.flash.server.auth.receiveBoundedText
import com.transfer.flash.server.auth.requirePrincipal
import com.transfer.flash.server.identity.HubIdentifiers.isValidEntityId
import com.transfer.flash.server.relay.HubPrincipalResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * REST routes of the organisation directory. Every route needs an authenticated principal (the bearer token resolved
 * by [principalResolver], or the one the `HubApiGate` already resolved). The caller's device id, department and role
 * ALWAYS come from that principal, never from a query parameter, header or body (SEC-03): the legacy `X-Device-Id` and
 * `department` inputs are gone.
 *
 * - `GET  /api/v1/directory/me`: the caller's own identity and memberships ([MeDto]).
 * - `GET  /api/v1/directory/groups`: groups visible to the caller (a hidden group exists only for its members).
 * - `GET  /api/v1/directory/members?departmentId=|groupId=`: members the caller may see, with their identity keys.
 * - `GET  /api/v1/directory/departments`: departments (member and group counts).
 * - `POST /api/v1/departments`, `POST /api/v1/groups`, `PUT|DELETE /api/v1/groups/{id}`: administration.
 * - `POST /api/v1/groups/members`: a group admin adds or removes a member.
 * - `POST /api/v1/groups/join-request`: ask to join (or join, for an open group).
 * - `GET  /api/v1/groups/join-requests?groupId=`, `POST .../decision`: a group admin handles the requests.
 *
 * Error text is a fixed sentence per status: a stranger probing ids can not tell a hidden group from a missing one.
 *
 * @param isOnline whether a device currently holds a relay connection (feeds `online` / `onlineCount`).
 */
fun Route.directoryRoutes(
    directory: OrganizationDirectory,
    principalResolver: HubPrincipalResolver,
    isOnline: (String) -> Boolean = directory::isOnline,
    audit: AuditSink = AuditSink.None,
    sealingKeys: SealingKeyLookup = SealingKeyLookup.None,
) {
    route(HubContract.PATH_API) {
        route("/directory") {
            get("/me") {
                val principal = call.requirePrincipal(principalResolver) ?: return@get
                val actor = principal.toActor()
                val groups = directory.listGroups().filter { actor.deviceId in it.memberDeviceIds || actor.deviceId in it.adminDeviceIds }
                val member = directory.getDepartmentMember(principal.deviceId)
                call.respondJson(
                    MeDto(
                        deviceId = principal.deviceId,
                        role = actor.role.name,
                        departmentId = principal.departmentId.orEmpty(),
                        displayName = member?.displayName.orEmpty(),
                        groupIds = groups.map { it.id },
                        adminOfDepartments = directory.listDepartments().filter { principal.deviceId in it.adminDeviceIds }.map { it.id },
                        certSerial = principal.certSerial,
                    ),
                )
            }

            get("/groups") {
                val principal = call.requirePrincipal(principalResolver) ?: return@get
                val actor = principal.toActor()
                call.respondJson(directory.listVisibleGroups(actor).map { it.toDto(actor, directory, isOnline) })
            }

            get("/members") {
                val principal = call.requirePrincipal(principalResolver) ?: return@get
                val actor = principal.toActor()
                val groupId = call.request.queryParameters["groupId"]
                if (groupId != null) {
                    val group = directory.getGroup(groupId)
                    if (group == null || !directory.canSee(group, actor) || !directory.canSeeMembers(group, actor)) {
                        call.respondNotFound()
                        return@get
                    }
                    val members = group.memberDeviceIds.mapNotNull { directory.getDepartmentMember(it) }
                    call.respondJson(members.withSealing(sealingKeys, isOnline))
                    return@get
                }
                val departmentId = call.request.queryParameters["departmentId"] ?: principal.departmentId
                if (departmentId.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_NEEDS_SCOPE)
                    return@get
                }
                val dept = directory.getDepartment(departmentId)
                val allowed = actor.isOrgAdmin || principal.departmentId == departmentId ||
                    (dept != null && principal.deviceId in dept.adminDeviceIds)
                if (!allowed) {
                    call.respondForbidden()
                    return@get
                }
                call.respondJson(directory.listDepartmentMembers(departmentId).withSealing(sealingKeys, isOnline))
            }

            get("/departments") {
                val principal = call.requirePrincipal(principalResolver) ?: return@get
                val actor = principal.toActor()
                val own = principal.departmentId.orEmpty()
                val visible = directory.listDepartments().filter { actor.isOrgAdmin || it.id == own || principal.deviceId in it.adminDeviceIds }
                call.respondJson(visible.map { it.toDto(directory) })
            }
        }

        // POST /api/v1/departments
        post("/departments") {
            val principal = call.requirePrincipal(principalResolver) ?: return@post
            val body = call.receiveBody<CreateDepartmentBody>() ?: return@post
            if (!isValidEntityId(body.id)) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_ID)
                return@post
            }
            call.guarded {
                val dept = directory.createDepartment(body.id, body.name, body.adminDeviceIds, principal.toActor())
                audit.record(principal.deviceId, "directory.department.create", "id=${dept.id}")
                call.respondJson(dept.toDto(directory), HttpStatusCode.Created)
            }
        }

        route("/groups") {
            // POST /api/v1/groups
            post {
                val principal = call.requirePrincipal(principalResolver) ?: return@post
                val body = call.receiveBody<CreateGroupBody>() ?: return@post
                val visibility = parseVisibility(body.visibility)
                val joinMode = parseJoinMode(body.joinMode)
                if (!isValidEntityId(body.id) || visibility == null || joinMode == null) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_ID)
                    return@post
                }
                call.guarded {
                    val actor = principal.toActor()
                    val group = directory.createGroup(body.id, body.name, body.departmentId, visibility, joinMode, body.adminDeviceIds, actor)
                    audit.record(principal.deviceId, "directory.group.create", "id=${group.id} department=${group.departmentId}")
                    call.respondJson(group.toDto(actor, directory, isOnline), HttpStatusCode.Created)
                }
            }

            // PUT /api/v1/groups/{id}
            put("/{id}") {
                val principal = call.requirePrincipal(principalResolver) ?: return@put
                val id = call.parameters["id"].orEmpty()
                val body = call.receiveBody<UpdateGroupBody>() ?: return@put
                val visibility = body.visibility?.let { parseVisibility(it) ?: return@put call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_ID) }
                val joinMode = body.joinMode?.let { parseJoinMode(it) ?: return@put call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_ID) }
                call.guarded {
                    val actor = principal.toActor()
                    hideFromStrangers(directory, id, actor)
                    val group = directory.updateGroup(id, body.name, visibility, joinMode, actor)
                    audit.record(principal.deviceId, "directory.group.update", "id=$id")
                    call.respondJson(group.toDto(actor, directory, isOnline))
                }
            }

            // DELETE /api/v1/groups/{id}
            delete("/{id}") {
                val principal = call.requirePrincipal(principalResolver) ?: return@delete
                val id = call.parameters["id"].orEmpty()
                call.guarded {
                    val actor = principal.toActor()
                    hideFromStrangers(directory, id, actor)
                    directory.deleteGroup(id, actor)
                    audit.record(principal.deviceId, "directory.group.delete", "id=$id")
                    call.respondJson(OkResponse())
                }
            }

            // POST /api/v1/groups/members
            post("/members") {
                val principal = call.requirePrincipal(principalResolver) ?: return@post
                val body = call.receiveBody<GroupMemberBody>() ?: return@post
                call.guarded {
                    val actor = principal.toActor()
                    hideFromStrangers(directory, body.groupId, actor)
                    val group = if (body.add) {
                        directory.addMemberToGroup(body.groupId, body.deviceId, actor)
                    } else {
                        directory.removeMemberFromGroup(body.groupId, body.deviceId, actor)
                    }
                    audit.record(principal.deviceId, if (body.add) "directory.group.member.add" else "directory.group.member.remove", "group=${group.id} device=${body.deviceId}")
                    call.respondJson(group.toDto(actor, directory, isOnline))
                }
            }

            // POST /api/v1/groups/join-request
            post("/join-request") {
                val principal = call.requirePrincipal(principalResolver) ?: return@post
                val body = call.receiveBody<JoinRequestBody>() ?: return@post
                if (body.groupId.isBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_ID)
                    return@post
                }
                when (val result = directory.requestJoinGroup(body.groupId, principal.deviceId, principal.departmentId, body.message)) {
                    is JoinRequestResult.Joined -> {
                        audit.record(principal.deviceId, "directory.group.join", "group=${result.group.id}")
                        call.respondJson(
                            JoinResponse(status = HubDirectoryValues.JOIN_STATUS_JOINED, groupId = result.group.id, name = result.group.name),
                            HttpStatusCode.OK,
                        )
                    }
                    is JoinRequestResult.Pending -> {
                        audit.record(principal.deviceId, "directory.group.join.request", "group=${body.groupId} request=${result.request.id}")
                        call.respondJson(
                            JoinResponse(status = HubDirectoryValues.JOIN_STATUS_PENDING, groupId = body.groupId, requestId = result.request.id),
                            HttpStatusCode.Accepted,
                        )
                    }
                    is JoinRequestResult.Rejected -> call.respondJson(
                        JoinResponse(status = HubDirectoryValues.JOIN_STATUS_REJECTED, groupId = body.groupId, reason = result.reason),
                        HttpStatusCode.Forbidden,
                    )
                }
            }

            // GET /api/v1/groups/join-requests?groupId=
            get("/join-requests") {
                val principal = call.requirePrincipal(principalResolver) ?: return@get
                val actor = principal.toActor()
                val groupId = call.request.queryParameters["groupId"].orEmpty()
                val group = directory.getGroup(groupId)
                if (group == null || !directory.canSee(group, actor)) {
                    call.respondNotFound()
                    return@get
                }
                if (!directory.canManage(group, actor)) {
                    call.respondForbidden()
                    return@get
                }
                call.respondJson(
                    directory.listPendingJoinRequests(groupId).map {
                        JoinRequestDto(
                            requestId = it.id,
                            groupId = it.groupId,
                            deviceId = it.deviceId,
                            displayName = directory.getDepartmentMember(it.deviceId)?.displayName.orEmpty(),
                            requestedAt = it.timestampMs,
                            message = it.message,
                        )
                    },
                )
            }

            // POST /api/v1/groups/join-requests/decision
            post("/join-requests/decision") {
                val principal = call.requirePrincipal(principalResolver) ?: return@post
                val body = call.receiveBody<JoinDecisionBody>() ?: return@post
                call.guarded {
                    val actor = principal.toActor()
                    val request = directory.getJoinRequest(body.requestId) ?: throw DirectoryNotFoundException("request")
                    hideFromStrangers(directory, request.groupId, actor)
                    val group = if (body.approve) {
                        directory.approveJoinRequest(body.requestId, actor)
                    } else {
                        directory.rejectJoinRequest(body.requestId, actor)
                        directory.getGroup(request.groupId)
                    }
                    audit.record(principal.deviceId, if (body.approve) "directory.join.approve" else "directory.join.reject", "request=${body.requestId} group=${request.groupId} device=${request.deviceId}")
                    // The contract (docs/protocol.md, ADR-124) answers a decision with a JoinResponse, the shape the
                    // client already decodes: JOINED for an approval, REJECTED for a refusal.
                    call.respondJson(
                        JoinResponse(
                            status = if (body.approve) HubDirectoryValues.JOIN_STATUS_JOINED else HubDirectoryValues.JOIN_STATUS_REJECTED,
                            groupId = request.groupId,
                            name = group?.name,
                            requestId = request.id,
                            reason = if (body.approve) null else body.reason?.take(256),
                        ),
                    )
                }
            }
        }
    }
}

/** The directory identity of an authenticated device: its role and department come from the hub-signed certificate. */
internal fun HubPrincipal.toActor(): DirectoryActor = DirectoryActor(
    deviceId = deviceId,
    departmentId = departmentId,
    role = when (com.transfer.flash.core.hubprotocol.certs.HubRoles.toDirectoryRole(role)) {
        "ORG_ADMIN" -> MemberRole.ORG_ADMIN
        "DEPT_ADMIN" -> MemberRole.DEPT_ADMIN
        else -> MemberRole.MEMBER
    },
)

/** A group a non-member may not see is reported as missing, exactly like an unknown id. */
private fun hideFromStrangers(directory: OrganizationDirectory, groupId: String, actor: DirectoryActor) {
    val group = directory.getGroup(groupId)
    if (group == null || !directory.canSee(group, actor)) throw DirectoryNotFoundException("group")
}

/** Runs [block], turning the directory's failures into the contract's fixed error responses. */
private suspend fun ApplicationCall.guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: DirectoryNotFoundException) {
        respondNotFound()
    } catch (_: DirectoryConflictException) {
        respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, MESSAGE_CONFLICT)
    } catch (_: SecurityException) {
        respondForbidden()
    } catch (_: IllegalStateException) {
        respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, MESSAGE_CONFLICT)
    } catch (_: IllegalArgumentException) {
        respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_REQUEST)
    }
}

private suspend fun ApplicationCall.respondNotFound() =
    respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, MESSAGE_NOT_FOUND)

private suspend fun ApplicationCall.respondForbidden() =
    respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, MESSAGE_FORBIDDEN)

/** Reads and decodes a bounded JSON body; responds 413 / 400 and returns null on failure. */
private suspend inline fun <reified T> ApplicationCall.receiveBody(): T? {
    val text = receiveBoundedText() ?: return null
    val value = HubRestCodec.decodeOrNull<T>(text)
    if (value == null) respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_REQUEST)
    return value
}

private fun parseVisibility(value: String): GroupVisibility? = GroupVisibility.entries.firstOrNull { it.name == value }

private fun parseJoinMode(value: String): JoinMode? = JoinMode.entries.firstOrNull { it.name == value }

private fun Department.toDto(directory: OrganizationDirectory) = DepartmentDto(
    id = id,
    name = name,
    adminDeviceIds = adminDeviceIds,
    memberCount = directory.memberCount(id),
    groupCount = directory.groupCount(id),
)

/**
 * The published sealing key of a device (ADR-124): PUBLIC material, served only inside the member DTOs of an
 * authenticated, in-scope caller (the routes above decide scope before they ever build a [MemberDto]).
 */
data class PublishedSealingKey(val publicKey: String, val signature: String)

fun interface SealingKeyLookup {
    /** The keys of [deviceIds]; devices without a key are absent. */
    fun lookup(deviceIds: Collection<String>): Map<String, PublishedSealingKey>

    companion object {
        val None: SealingKeyLookup = SealingKeyLookup { emptyMap() }
    }
}

private fun List<DepartmentMember>.withSealing(sealing: SealingKeyLookup, isOnline: (String) -> Boolean): List<MemberDto> {
    val keys = sealing.lookup(map { it.deviceId })
    return map { member ->
        MemberDto(
            deviceId = member.deviceId,
            departmentId = member.departmentId,
            displayName = member.displayName,
            role = member.role.name,
            online = isOnline(member.deviceId),
            subjectPublicKey = member.subjectPublicKey,
            sealingPublicKey = keys[member.deviceId]?.publicKey,
            sealingSignature = keys[member.deviceId]?.signature,
        )
    }
}

private fun GroupCharter.toDto(actor: DirectoryActor, directory: OrganizationDirectory, isOnline: (String) -> Boolean): GroupDto {
    val mayList = directory.canSeeMembers(this, actor)
    val manager = directory.canManage(this, actor)
    return GroupDto(
        id = id,
        name = name,
        departmentId = departmentId,
        visibility = visibility.name,
        joinMode = joinMode.name,
        adminDeviceIds = adminDeviceIds,
        memberDeviceIds = if (mayList) memberDeviceIds else emptyList(),
        memberCount = memberDeviceIds.size,
        onlineCount = memberDeviceIds.count(isOnline),
        pendingCount = if (manager) directory.pendingCount(id) else null,
    )
}

private const val MESSAGE_BAD_REQUEST = "The request could not be processed."
private const val MESSAGE_BAD_ID = "A name, id or value in the request is not valid."
private const val MESSAGE_NEEDS_SCOPE = "Name a department or a group."
private const val MESSAGE_NOT_FOUND = "Not found."
private const val MESSAGE_FORBIDDEN = "Not allowed."
private const val MESSAGE_CONFLICT = "The request conflicts with the current state."

/**
 * JSON string escaping used by the admin pages' hand-built fragments.
 * PHASE-4: the admin UI moves to DTOs and this goes away with it.
 */
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
