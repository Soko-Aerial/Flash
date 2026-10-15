@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.enterprise.EnterpriseDirectoryGroup
import com.transfer.flash.core.enterprise.OrgSyncOutcome
import com.transfer.flash.core.persistence.db.dao.OrgDirectoryDao
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the Hub tab read model needs from the HOST (UI-058, Plan A4 H6). The repository already owns everything else it needs
 * (credentials, resolver, presence sets, announcements); the engine owns the directory DAO and the sync coordinator, so it
 * hands them over in this one object instead of the repository learning about the engine.
 */
public class HubTabSources(
    /** The Plan B directory snapshot. Read-only here: the facade never writes it. */
    public val directoryDao: OrgDirectoryDao,
    /**
     * Runs the organisation directory sync now and says how it ended (`null` = it could not start, for example no coordinator).
     * [force] bypasses the coordinator's own minimum interval; the facade applies its 15 s rule before calling.
     */
    public val requestSync: (suspend (force: Boolean) -> OrgSyncOutcome?)? = null,
    /** True while the device clock looks wrong (the sync's skew detector). */
    public val clockWrong: Flow<Boolean> = flowOf(false),
    /** Device ids the hub reports as online. `null` while the host has no presence source: reachability then never claims HUB. */
    public val hubPresence: Flow<Set<String>>? = null,
    /** All groups the caller may see on the hub (not only the own department). `null` without a directory client. */
    public val fetchGroups: (suspend (EnrollmentCredentials) -> FlashResult<List<EnterpriseDirectoryGroup>>)? = null,
)
