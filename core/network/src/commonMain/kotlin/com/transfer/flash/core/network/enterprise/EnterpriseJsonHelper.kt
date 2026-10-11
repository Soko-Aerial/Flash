package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.security.enterprise.DelegationCert
import com.transfer.flash.core.security.enterprise.DeviceCert

@FlashInternalApi
public object EnterpriseJsonHelper {

    public fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    public fun extractField(json: String, fieldName: String): String? {
        val regex = Regex(""""$fieldName"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
        val match = regex.find(json)
        if (match != null) {
            return match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
        val numRegex = Regex(""""$fieldName"\s*:\s*(-?\d+|true|false)""")
        val numMatch = numRegex.find(json)
        return numMatch?.groupValues?.get(1)
    }

    public fun parseDeviceCert(json: String): DeviceCert? {
        val block = if (json.contains("\"deviceCert\"")) {
            val start = json.indexOf("\"deviceCert\"")
            val open = json.indexOf('{', start)
            val close = json.indexOf('}', open)
            if (open != -1 && close != -1) json.substring(open, close + 1) else json
        } else {
            json
        }

        val deviceId = extractField(block, "deviceId") ?: return null
        val subjectPublicKey = extractField(block, "subjectPublicKey") ?: return null
        val role = extractField(block, "role") ?: DeviceCert.ROLE_MEMBER
        val department = extractField(block, "department")
        val validFrom = extractField(block, "validFrom")?.toLongOrNull() ?: return null
        val validTo = extractField(block, "validTo")?.toLongOrNull() ?: return null
        val serial = extractField(block, "serial")?.toLongOrNull() ?: return null
        val issuerPublicKey = extractField(block, "issuerPublicKey") ?: return null
        val issuerSignature = extractField(block, "issuerSignature") ?: return null

        return DeviceCert(
            deviceId = deviceId,
            subjectPublicKey = subjectPublicKey,
            role = role,
            department = department,
            validFrom = validFrom,
            validTo = validTo,
            serial = serial,
            issuerPublicKey = issuerPublicKey,
            issuerSignature = issuerSignature,
        )
    }

    public fun parseDelegationCert(block: String): DelegationCert? {
        val rootPublicKey = extractField(block, "rootPublicKey") ?: return null
        val delegatePublicKey = extractField(block, "delegatePublicKey") ?: return null
        val validFrom = extractField(block, "validFrom")?.toLongOrNull() ?: return null
        val validTo = extractField(block, "validTo")?.toLongOrNull() ?: return null
        val maxDevices = extractField(block, "maxDevices")?.toIntOrNull() ?: 100
        val serial = extractField(block, "serial")?.toLongOrNull() ?: return null
        val signature = extractField(block, "signature") ?: return null

        val scopes = mutableSetOf<String>()
        val scopesMatch = Regex(""""scopes"\s*:\s*\[(.*?)\]""").find(block)
        if (scopesMatch != null) {
            val items = scopesMatch.groupValues[1].split(",")
            for (item in items) {
                val clean = item.trim().trim('"')
                if (clean.isNotEmpty()) scopes.add(clean)
            }
        }
        if (scopes.isEmpty()) scopes.add(DelegationCert.SCOPE_ENROL_MEMBER)

        return DelegationCert(
            rootPublicKey = rootPublicKey,
            delegatePublicKey = delegatePublicKey,
            scopes = scopes,
            validFrom = validFrom,
            validTo = validTo,
            maxDevices = maxDevices,
            serial = serial,
            signature = signature,
        )
    }

    public fun parseDelegationChain(json: String): List<DelegationCert> {
        val chain = mutableListOf<DelegationCert>()
        val startIdx = json.indexOf("\"delegationChain\"")
        if (startIdx == -1) return chain

        val arrayStart = json.indexOf('[', startIdx)
        val arrayEnd = json.indexOf(']', arrayStart)
        if (arrayStart == -1 || arrayEnd == -1) return chain

        val arrayContent = json.substring(arrayStart + 1, arrayEnd)
        val blocks = arrayContent.split("},{")
        for (b in blocks) {
            val formatted = if (b.startsWith("{")) b else "{$b"
            val block = if (formatted.endsWith("}")) formatted else "$formatted}"
            val cert = parseDelegationCert(block)
            if (cert != null) chain.add(cert)
        }
        return chain
    }

    public fun parseDirectoryGroups(json: String): List<EnterpriseDirectoryGroup> {
        val groups = mutableListOf<EnterpriseDirectoryGroup>()
        val blocks = splitJsonObjects(json)
        for (block in blocks) {
            val id = extractField(block, "groupId") ?: extractField(block, "id") ?: continue
            val name = extractField(block, "name") ?: "Group $id"
            val dept = extractField(block, "department") ?: ""
            val vis = extractField(block, "visibility") ?: "PUBLIC"
            val desc = extractField(block, "description") ?: ""
            val reqApproval = extractField(block, "requiresApproval") == "true"
            val count = extractField(block, "memberCount")?.toIntOrNull() ?: 0

            groups.add(
                EnterpriseDirectoryGroup(
                    groupId = id,
                    name = name,
                    department = dept,
                    visibility = vis,
                    description = desc,
                    requiresApproval = reqApproval,
                    memberCount = count,
                )
            )
        }
        return groups
    }

    public fun parseDirectoryMembers(json: String): List<EnterpriseDirectoryMember> {
        val members = mutableListOf<EnterpriseDirectoryMember>()
        val blocks = splitJsonObjects(json)
        for (block in blocks) {
            val deviceId = extractField(block, "deviceId") ?: continue
            val name = extractField(block, "friendlyName") ?: extractField(block, "name") ?: deviceId
            val dept = extractField(block, "department") ?: ""
            val role = extractField(block, "role") ?: "member"
            val online = extractField(block, "online") == "true"

            members.add(
                EnterpriseDirectoryMember(
                    deviceId = deviceId,
                    friendlyName = name,
                    department = dept,
                    role = role,
                    online = online,
                )
            )
        }
        return members
    }

    public fun parseDepartments(json: String): List<EnterpriseDepartment> {
        val depts = mutableListOf<EnterpriseDepartment>()
        val blocks = splitJsonObjects(json)
        for (block in blocks) {
            val id = extractField(block, "departmentId") ?: extractField(block, "id") ?: continue
            val name = extractField(block, "name") ?: id
            val desc = extractField(block, "description") ?: ""
            val memCount = extractField(block, "memberCount")?.toIntOrNull() ?: 0
            val grpCount = extractField(block, "groupCount")?.toIntOrNull() ?: 0

            depts.add(
                EnterpriseDepartment(
                    departmentId = id,
                    name = name,
                    description = desc,
                    memberCount = memCount,
                    groupCount = grpCount,
                )
            )
        }
        return depts
    }

    private fun splitJsonObjects(json: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in json.indices) {
            val c = json[i]
            if (c == '{') {
                if (depth == 0) start = i
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0 && start != -1) {
                    objects.add(json.substring(start, i + 1))
                    start = -1
                }
            }
        }
        return objects
    }
}
