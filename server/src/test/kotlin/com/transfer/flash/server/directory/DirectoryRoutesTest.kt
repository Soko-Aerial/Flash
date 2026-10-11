package com.transfer.flash.server.directory

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectoryRoutesTest {

    @Test
    fun testDirectoryRoutesEndpoints() = testApplication {
        val dir = OrganizationDirectory()
        dir.createDepartment("dept-gh", "Ghana Armed Forces", listOf("admin-01"), isOrgAdmin = true)
        dir.addDepartmentMember("dept-gh", "member-01", "Sergeant Mensah")

        dir.createGroup(
            id = "grp-public",
            name = "Public Info",
            departmentId = "dept-gh",
            visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.OPEN_IN_SCOPE,
            callerDeviceId = "admin-01"
        )

        dir.createGroup(
            id = "grp-secret",
            name = "Secret Intel",
            departmentId = "dept-gh",
            visibility = GroupVisibility.HIDDEN,
            joinMode = JoinMode.ADMIN_ONLY,
            callerDeviceId = "admin-01"
        )

        routing {
            directoryRoutes(dir)
        }

        // 1. GET /api/v1/directory/groups with missing deviceId -> 400 Bad Request
        val errRes = client.get("/api/v1/directory/groups")
        assertEquals(HttpStatusCode.BadRequest, errRes.status)

        // 2. GET /api/v1/directory/groups with deviceId and department -> 200 OK
        val groupsRes = client.get("/api/v1/directory/groups?deviceId=member-01&department=dept-gh")
        assertEquals(HttpStatusCode.OK, groupsRes.status)
        val groupsBody = groupsRes.bodyAsText()
        assertTrue(groupsBody.contains("grp-public"))
        // Hidden group must NOT be in the response for non-members (D-E11)
        assertTrue(!groupsBody.contains("grp-secret"))

        // 3. GET /api/v1/directory/members -> 200 OK
        val membersRes = client.get("/api/v1/directory/members?departmentId=dept-gh")
        assertEquals(HttpStatusCode.OK, membersRes.status)
        val membersBody = membersRes.bodyAsText()
        assertTrue(membersBody.contains("member-01"))
        assertTrue(membersBody.contains("Sergeant Mensah"))

        // 4. POST /api/v1/groups/join-request for OPEN_IN_SCOPE -> 200 JOINED
        val joinRes = client.post("/api/v1/groups/join-request") {
            contentType(ContentType.Application.Json)
            setBody("""{"groupId":"grp-public","deviceId":"member-02","departmentId":"dept-gh"}""")
        }
        assertEquals(HttpStatusCode.OK, joinRes.status)
        assertTrue(joinRes.bodyAsText().contains("JOINED"))

        // 5. POST /api/v1/groups/join-request for HIDDEN group -> 403 Forbidden (D-E11)
        val hiddenJoinRes = client.post("/api/v1/groups/join-request") {
            contentType(ContentType.Application.Json)
            setBody("""{"groupId":"grp-secret","deviceId":"member-02","departmentId":"dept-gh"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, hiddenJoinRes.status)
        assertTrue(hiddenJoinRes.bodyAsText().contains("REJECTED"))

        // 6. GET /api/v1/directory/departments -> 200 OK
        val deptsRes = client.get("/api/v1/directory/departments")
        assertEquals(HttpStatusCode.OK, deptsRes.status)
        assertTrue(deptsRes.bodyAsText().contains("dept-gh"))
    }
}
