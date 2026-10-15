package com.transfer.flash.ui.hub

/**
 * Every sentence of the Hub tab (UI-058), in one place shared by the Android and the desktop host (the project keeps its
 * UI copy in Kotlin objects of `:ui:chat`, like `FlashHubStatusMath`). Exact texts come from `docs/ui/flash-hub-tab.md`,
 * Plan A3 section 9 (`org_ann_*`) and Plan B section 9 (`org_trust_*`, `org_start_*`); do not paraphrase them.
 */
public object FlashHubCopy {
    // --- Tab and top bar ---------------------------------------------------------------------------------
    public const val TAB_LABEL: String = "Hub"
    public const val SEARCH: String = "Search"
    public const val CLOSE_SEARCH: String = "Close search"
    public const val SEARCH_HINT: String = "Search the organisation"
    public const val TRANSFERS_MOVED_TIP: String = "Transfers moved to the top bar."
    public const val ROUTE_NOT_ENROLLED: String = "This device is not part of an organisation."
    public const val TIP_DISMISS: String = "Got it"

    // --- Segments ----------------------------------------------------------------------------------------
    public const val SEGMENT_NEWS: String = "News"
    public const val SEGMENT_PEOPLE: String = "People"
    public const val SEGMENT_DEPARTMENTS: String = "Departments"
    public const val SEGMENT_GROUPS: String = "Groups"

    // --- Status strip ------------------------------------------------------------------------------------
    public const val STRIP_UPDATING: String = "Updating…"
    public const val STRIP_WHY: String = "Why?"
    public const val STRIP_LEASE_EXPIRED: String = "Reconnect to the company network to keep colleague trust up to date"
    public const val STRIP_CLOCK_WRONG: String = "Your device clock looks wrong. Colleague trust may fail until it is fixed."
    public const val STRIP_UNSUPPORTED: String = "This company server does not support offline colleague chat yet."
    public const val STRIP_UPDATE_FAILED: String = "Could not update"
    public const val WHY_TITLE: String = "Colleague trust"
    public const val WHY_BODY: String =
        "Flash checks colleagues against a list your company server signs. The list stays valid for a limited time. " +
            "Connect to the company network to renew it; until then colleagues you are not paired with cannot start a chat."
    public const val WHY_CLOSE: String = "Close"

    // --- Empty and error states --------------------------------------------------------------------------
    public const val NEVER_SYNCED_TITLE: String = "Connect to the company network once to load your colleagues"
    public const val NEVER_SYNCED_BODY: String = "Once your colleagues are loaded they stay available here without a connection."
    public const val RETRY: String = "Retry"
    public const val REFRESHING: String = "Updating…"
    public const val NO_PEOPLE: String = "No colleagues to show yet."
    public const val NO_PEOPLE_IN_DEPARTMENT: String = "No colleagues in this department."
    public const val NO_DEPARTMENTS: String = "No departments to show yet."
    public const val NO_GROUPS: String = "No organisation groups yet."
    public const val NO_GROUPS_BODY: String = "Groups your administrators create for your departments appear here."
    public const val GROUPS_NOT_LOADED: String = "Could not load the organisation groups."
    public const val GROUPS_NOT_LOADED_BODY: String = "Check your connection and try again."
    public const val SEARCH_PEOPLE_TITLE: String = "People"
    public const val SEARCH_DEPARTMENTS_TITLE: String = "Departments"
    public const val SEARCH_NEWS_TITLE: String = "News"
    public const val SEARCH_GROUPS_TITLE: String = "Groups"
    public const val SEARCH_SEE_ALL_PEOPLE: String = "See all people"
    public fun searchNothing(query: String): String = "Nothing matches \"$query\"."
    public const val SEARCH_PROMPT: String = "Type a name, a department or a word from an announcement."
    public const val DEPARTMENT_HIDDEN: String = "This department is not visible to you"
    public const val REVOKED_TITLE: String = "Your organisation removed this device"
    public const val REVOKED_BODY: String =
        "Organisation news, people and groups are no longer available. Chats with people you paired with directly are not affected."
    public const val SIGN_OUT_ORG: String = "Sign out of organisation"
    public const val LOADING: String = "Loading"

    // --- News (Plan A3 section 9) ------------------------------------------------------------------------
    public const val NEWS_TITLE: String = "News"
    public const val ANN_SCOPE_COMPANY: String = "Whole company"
    public const val ANN_HONESTY: String = "Announcements are stored as plain text on your company's server. Administrators can read them."
    public const val ANN_POST: String = "Post announcement"
    public const val ANN_NOT_ALLOWED: String = "You can only post announcements for your own department."
    public const val ANN_OFFLINE: String = "You are offline. Your draft is saved. Connect to the company network and try again."
    public const val ANN_REMOVED: String = "This announcement was removed."
    public const val ANN_CONFLICT: String = "Someone changed this announcement while you were editing. Review their version."
    public const val ANN_PIN_LIMIT: String = "You can pin up to 3 announcements here. Unpin one first."
    public const val ANN_UNSUPPORTED: String = "This company server does not support announcements yet."
    public const val ANN_EMPTY: String = "No announcements yet."
    public const val ANN_POST_FAILED: String = "Could not post. Try again"
    public const val ANN_SCOPE_DEPARTMENT: String = "Department"
    public const val ANN_ROLE_ADMIN: String = "Admin"
    public const val ANN_ROLE_HEAD: String = "Department head"
    public const val ANN_PINNED: String = "Pinned"
    public const val ANN_EDITED: String = "edited"
    public const val ANN_READ_MORE: String = "Read more"
    public const val ANN_UNREAD: String = "Unread"
    public const val ANN_NEW_FAB: String = "New announcement"
    public const val ANN_EDIT: String = "Edit"
    public const val ANN_DELETE: String = "Delete"
    public const val ANN_PIN: String = "Pin"
    public const val ANN_UNPIN: String = "Unpin"
    public const val ANN_COPY: String = "Copy text"
    public const val ANN_DELETE_CONFIRM_TITLE: String = "Delete this announcement?"
    public const val ANN_DELETE_CONFIRM_BODY: String = "Everyone in its audience will no longer see it."
    public const val ANN_CANCEL: String = "Cancel"
    public const val ANN_SAVE: String = "Save changes"
    public const val ANN_TITLE_HINT: String = "Title"
    public const val ANN_BODY_HINT: String = "Write the announcement"
    public const val ANN_AUDIENCE: String = "Audience"
    public const val ANN_PIN_TOGGLE: String = "Pin to the top"
    public const val ANN_COMPOSER_TITLE_NEW: String = "New announcement"
    public const val ANN_COMPOSER_TITLE_EDIT: String = "Edit announcement"
    public const val ANN_LINK_OPEN: String = "Open"
    public const val ANN_LINK_CANCEL: String = "Cancel"
    public const val ANN_READER_BACK: String = "Back to news"
    public const val ANN_TIP_PIN_LIMIT_SCOPE: String = "Unpin one first."
    public const val ANN_SECTION_PINNED: String = "Pinned"
    public const val ANN_SECTION_LATEST: String = "Latest"
    public const val ANN_MEMBERS_CANT_POST: String =
        "Announcements come from your administrators and department heads. You will see them here as soon as they are posted."
    public const val ANN_COMPOSE_TITLE: String = "Share an update"
    public const val ANN_COMPOSE_SUB_ORG: String = "Reach the whole company or one department"
    public const val ANN_COMPOSE_SUB_DEPT: String = "Reach the people in your department"
    public const val ANN_EMPTY_POSTER: String = "Be the first to share an update with your colleagues."
    public const val ANN_EMPTY_MEMBER: String = "Updates from your administrators and department heads will appear here."
    public const val ANN_EMPTY_HEADLINE: String = "No announcements yet"
    public fun annVisibleTo(audience: String): String = "Visible to: $audience"

    // --- Overview card -----------------------------------------------------------------------------------
    public const val OVERVIEW_ROLE_ADMIN: String = "Administrator"
    public const val OVERVIEW_ROLE_HEAD: String = "Department head"
    public const val OVERVIEW_ROLE_MEMBER: String = "Member"
    public const val OVERVIEW_CAN_POST: String = "Can post announcements"
    public const val OVERVIEW_CONNECTED: String = "Connected"
    public const val OVERVIEW_OFFLINE: String = "Offline"
    public fun plural(count: Int, one: String, many: String): String = if (count == 1) "$count $one" else "$count $many"

    public fun annRate(seconds: Int): String = "You are posting too fast. Try again in $seconds seconds."
    public fun annTooLong(maxChars: Int): String = "This announcement is too long. Shorten it to $maxChars characters."
    public fun annLinkConfirm(url: String): String = "Open this link in your browser? $url"
    public fun annNotification(count: Int): String = if (count == 1) "New announcement" else "$count new announcements"
    public fun annCounter(used: Int, max: Int): String = "$used / $max"

    // --- People ------------------------------------------------------------------------------------------
    public const val PEOPLE_FILTER_ALL: String = "All"
    public const val PEOPLE_FILTER_MINE: String = "My department"
    public const val PEOPLE_YOU: String = "You"
    public const val PEOPLE_PAIRED: String = "Paired"
    public const val ROLE_ADMIN: String = "Admin"
    public const val ROLE_HEAD: String = "Head"
    public const val ONLINE: String = "Online"
    public const val OFFLINE: String = "Offline"
    public const val REACH_LAN: String = "On your Wi-Fi now"
    public const val REACH_HUB: String = "Reachable through the company server"
    public const val REACH_OFFLINE: String = "Offline"
    public const val REACH_UNKNOWN: String = "Availability unknown"
    public const val PERSON_MESSAGE: String = "Message"
    public const val PERSON_MESSAGE_SELF: String = "That is you"
    public const val PERSON_MESSAGING: String = "Opening the conversation"
    public const val PERSON_CALL: String = "Call"
    public const val PERSON_PAIR: String = "Pair with this colleague"
    public const val PERSON_CLOSE: String = "Close"
    public const val PERSON_UNPAIRED_FOOTER: String =
        "Not paired with you. Messages go through the company server and can be read there unless you turn on private messages in Settings → Hub."
    public const val PERSON_HONESTY: String =
        "Colleagues are verified by your company's server. Pair with someone when you need to be sure it is really them."
    public const val PERSON_DEPARTMENT_UNKNOWN: String = "No department"
    public const val PERSON_UNKNOWN: String = "This person is not in your company directory."
    public const val MESSAGE_FAILED: String = "Something went wrong talking to your organisation. Try again."

    public fun noOneNamed(query: String): String = "No one named “$query”"
    public fun idSuffixLine(suffix: String): String = "ID ending $suffix"

    // --- Departments -------------------------------------------------------------------------------------
    public const val DEPT_YOURS: String = "Your department"
    public const val DEPT_HEADS: String = "Heads"
    public const val DEPT_MEMBERS: String = "Members"
    public const val DEPT_GROUPS: String = "Groups"
    public const val DEPT_NEWS: String = "News"
    public const val DEPT_NO_HEADS: String = "No head named yet"
    public const val DEPT_NO_GROUPS: String = "No groups for this department."
    public const val DEPT_NO_NEWS: String = "No announcements for this department."
    public const val DEPT_NO_MEMBERS: String = "No colleagues in this department."
    public const val DEPT_BACK: String = "Back to departments"

    // --- Groups ------------------------------------------------------------------------------------------
    public const val GROUPS_MANAGE: String = "Review group join requests"
    public const val GROUPS_YOURS: String = "Yours"
    public const val GROUPS_OTHER: String = "Other"
    public const val GROUPS_OPEN: String = "Open"
    public const val GROUPS_LIMITATION_TITLE: String = "Group messages"

    // --- Accessibility -----------------------------------------------------------------------------------
    public fun tabBadgeDescription(unread: Int): String = when (unread) {
        0 -> "Hub"
        1 -> "Hub, 1 unread announcement"
        else -> "Hub, $unread unread announcements"
    }
}
