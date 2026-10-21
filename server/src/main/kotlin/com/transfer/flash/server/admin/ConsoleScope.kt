package com.transfer.flash.server.admin

import com.transfer.flash.server.auth.respondError
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

/**
 * Console scope (Plan A1, ADR-136): what departments an admin-console session may
 * act on. Re-read from the database on EVERY request (never cached in the
 * session), so a demotion, department change or disable applies immediately.
 */
sealed interface ConsoleScope {
    /** An organisation administrator: everything. */
    data object Org : ConsoleScope

    /** A department head: only [ids] (may be empty → "No departments"). */
    data class Departments(val ids: Set<String>) : ConsoleScope
}

/** Reads the scope of [session] from the database (unknown role, missing or disabled user → empty scope). */
fun AdminApiDeps.scopeOf(session: AdminSession): ConsoleScope {
    val admin = admins.getAdmin(session.username) ?: return ConsoleScope.Departments(emptySet())
    if (admin.disabled) return ConsoleScope.Departments(emptySet())
    return when (admin.role) {
        AdminUser.ROLE_ORG_ADMIN -> ConsoleScope.Org
        AdminUser.ROLE_DEPT_HEAD -> ConsoleScope.Departments(admins.headDepartments(admin.username))
        else -> ConsoleScope.Departments(emptySet())
    }
}

/** True when [departmentId] is inside [this] scope. A null/blank department is never in a head's scope. */
fun ConsoleScope.allows(departmentId: String?): Boolean = when (this) {
    ConsoleScope.Org -> true
    is ConsoleScope.Departments -> !departmentId.isNullOrBlank() && departmentId in ids
}

/** Responds 403 and returns false unless [scope] is [ConsoleScope.Org]. */
suspend fun ApplicationCall.requireOrg(scope: ConsoleScope): Boolean {
    if (scope is ConsoleScope.Org) return true
    respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, MSG_FORBIDDEN)
    return false
}

/** Route policy: ORG = org admin only; OWN = head allowed inside own departments; SELF = own account/flows. */
enum class AdminRoutePolicy { ORG, OWN, SELF }

/**
 * The DECLARED policy of every admin console route. This table is documentation checked by tests, NOT an enforcement point:
 * authorization itself is applied by each handler (`call.requireOrg(scope)`, the department filters).
 *
 * Two tests keep the declaration honest (review F2): `ConsoleScopeTest.everyAdminRouteHasADeclaredPolicy` fails when a route
 * has no entry here, and `DepartmentHeadScopeTest.everyRouteDeclaredOrgRefusesADepartmentHead` calls every route marked
 * [AdminRoutePolicy.ORG] with a department-head session and requires 403. A new ORG route therefore needs both a row here and
 * a `requireOrg` in its handler. `OWN` and `SELF` rows are not exercised by that matrix. Keys are `METHOD path-template`
 * exactly as `routeTable()` spells them.
 */
object AdminRoutePolicies {
    val ALL: Map<String, AdminRoutePolicy> = mapOf(
        // Session and own account.
        "GET /admin/api/v1/session" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/login" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/logout" to AdminRoutePolicy.SELF,
        "GET /admin/api/v1/sessions" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/sessions/{id}/revoke" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/admins/me/password" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/admins/me/recovery-codes" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/admins/me/console-key" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/admins/me/device" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/admins/me/approver" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/admins/me/approver/remove" to AdminRoutePolicy.SELF,
        "GET /admin/api/v1/stepup/{id}" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/stepup/challenge" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/stepup/verify" to AdminRoutePolicy.SELF,
        // Administrator accounts (org admin only).
        "GET /admin/api/v1/admins" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/admins" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/admins/{username}/departments" to AdminRoutePolicy.ORG,
        "PUT /admin/api/v1/admins/{username}/departments" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/admins/{username}/disable" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/admins/{username}/enable" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/admins/{username}/password" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/admins/{username}/totp-reset" to AdminRoutePolicy.ORG,
        // Organisation structure.
        "GET /admin/api/v1/departments" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/departments" to AdminRoutePolicy.ORG,
        "PUT /admin/api/v1/departments/{id}" to AdminRoutePolicy.ORG,
        "DELETE /admin/api/v1/departments/{id}" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/groups" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/groups" to AdminRoutePolicy.OWN,
        "PUT /admin/api/v1/groups/{id}" to AdminRoutePolicy.OWN,
        "DELETE /admin/api/v1/groups/{id}" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/groups/{id}/members" to AdminRoutePolicy.OWN,
        "DELETE /admin/api/v1/groups/{id}/members/{deviceId}" to AdminRoutePolicy.OWN,
        "GET /admin/api/v1/join-requests" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/join-requests/{id}/approve" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/join-requests/{id}/reject" to AdminRoutePolicy.OWN,
        // Devices and invites.
        "GET /admin/api/v1/devices" to AdminRoutePolicy.OWN,
        "GET /admin/api/v1/devices/{id}" to AdminRoutePolicy.OWN,
        "PUT /admin/api/v1/devices/{id}" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/devices/{id}/revoke" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/devices/{id}/unrevoke" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/invites" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/invites" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/invites/bulk" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/invites/{id}/revoke" to AdminRoutePolicy.OWN,
        // Announcements (Plan A3, ADR-138).
        "GET /admin/api/v1/announcements" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/announcements" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/announcements/preview" to AdminRoutePolicy.OWN,
        "GET /admin/api/v1/announcements/{id}" to AdminRoutePolicy.OWN,
        "PUT /admin/api/v1/announcements/{id}" to AdminRoutePolicy.OWN,
        "DELETE /admin/api/v1/announcements/{id}" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/announcements/{id}/pin" to AdminRoutePolicy.OWN,
        "POST /admin/api/v1/announcements/{id}/unpin" to AdminRoutePolicy.OWN,
        // Sealed files (ADR-215): the counts and bytes the hub is holding; no name, no recipient, no content.
        "GET /admin/api/v1/files/stats" to AdminRoutePolicy.ORG,
        // Hub trust and operations (org admin only).
        "GET /admin/api/v1/overview" to AdminRoutePolicy.OWN,
        "GET /admin/api/v1/metrics" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/audit" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/audit/head" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/audit/verify" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/audit/export" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/delegation" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/delegation" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/delegation/reload" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/revocations" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/revocations/install" to AdminRoutePolicy.ORG,
        "POST /admin/api/v1/revocations/serial" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/settings" to AdminRoutePolicy.ORG,
        "PUT /admin/api/v1/settings" to AdminRoutePolicy.ORG,
        "GET /admin/api/v1/setup/status" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/setup/begin" to AdminRoutePolicy.SELF,
        "POST /admin/api/v1/setup/complete" to AdminRoutePolicy.SELF,
    )

    /** Routes the policy table does not name (setup/login handled above are named; anything else fails the test). */
    fun policyFor(method: String, template: String): AdminRoutePolicy? = ALL["$method $template"]
}
