package com.transfer.flash.core.engine

import okio.Path

internal actual fun createReceiveReservation(path: Path): Boolean = path.toFile().createNewFile()
