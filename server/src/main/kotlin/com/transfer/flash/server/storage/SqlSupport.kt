package com.transfer.flash.server.storage

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * Tiny JDBC helpers shared by the identity, directory, admin and audit stores. They keep every SQL statement
 * parameterised (never string-built from input) and keep result handling in one place.
 */

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { i, p ->
        val idx = i + 1
        when (p) {
            null -> setNull(idx, Types.NULL)
            is String -> setString(idx, p)
            is Long -> setLong(idx, p)
            is Int -> setInt(idx, p)
            is Boolean -> setInt(idx, if (p) 1 else 0)
            is ByteArray -> setBytes(idx, p)
            else -> error("unsupported SQL parameter type ${p::class.java.name}")
        }
    }
}

/** Runs a query and maps every row. */
internal fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(params)
        st.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out += map(rs)
            out
        }
    }

/** Runs a query and maps the first row, or returns null. */
internal fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { st ->
        st.bind(params)
        st.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

/** Runs INSERT / UPDATE / DELETE and returns the affected row count. */
internal fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { st ->
        st.bind(params)
        st.executeUpdate()
    }

internal fun Connection.scalarLong(sql: String, vararg params: Any?): Long =
    queryOne(sql, *params) { it.getLong(1) } ?: 0L

/** A nullable Long column (JDBC's getLong returns 0 for NULL). */
internal fun ResultSet.longOrNull(column: String): Long? {
    val v = getLong(column)
    return if (wasNull()) null else v
}

internal fun ResultSet.stringOrNull(column: String): String? = getString(column)

/** Stores a set of short strings in one column: newline separated (identifiers never contain control characters). */
internal fun encodeSet(values: Set<String>): String = values.sorted().joinToString("\n")

internal fun decodeSet(text: String?): Set<String> =
    if (text.isNullOrEmpty()) emptySet() else text.split('\n').filter { it.isNotEmpty() }.toSet()

/** Order-preserving list variant of [encodeSet] (group admin lists keep the order they were given). */
internal fun encodeList(values: List<String>): String = values.joinToString("\n")

internal fun decodeList(text: String?): List<String> =
    if (text.isNullOrEmpty()) emptyList() else text.split('\n').filter { it.isNotEmpty() }
