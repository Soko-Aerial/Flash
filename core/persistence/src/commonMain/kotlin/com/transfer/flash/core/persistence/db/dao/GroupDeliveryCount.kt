package com.transfer.flash.core.persistence.db.dao

/**
 * Per-message group-delivery aggregate. Room maps the grouped `messageId`/`deliveredTo`/
 * `deliveredTotal` columns from [GroupDeliveryDao.observeDeliveryCounts] by name.
 */
public data class GroupDeliveryCount(
    val messageId: String,
    val deliveredTo: Int,
    val deliveredTotal: Int,
)
