package io.spechub.spec.index

import java.sql.Connection

/*
 * Store 共享 JDBC 小助手（PlatformStore / ProjectionStore 共用）。
 *
 * 统一显式 try/finally，绕开 kotlin use 泛型在嵌套推断的不确定性；
 * sqlite-jdbc 的 INTEGER 列经 getObject 返回 Integer，一律按 Number 转 Long。
 */

internal fun Map<String, Any?>.long(key: String): Long = (this[key] as Number).toLong()

internal fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()

internal fun Connection.exec(
    sql: String,
    vararg params: Any?,
) {
    val st = this.prepareStatement(sql)
    try {
        params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeUpdate()
    } finally {
        st.close()
    }
}

internal fun Connection.insert(
    sql: String,
    vararg params: Any?,
): Long {
    val st = this.prepareStatement(sql)
    try {
        params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeUpdate()
        return st.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
    } finally {
        st.close()
    }
}

internal fun Connection.queryObj(
    sql: String,
    vararg params: Any?,
): Any? {
    val st = this.prepareStatement(sql)
    try {
        params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        val rs = st.executeQuery()
        try {
            return if (rs.next()) rs.getObject(1) else null
        } finally {
            rs.close()
        }
    } finally {
        st.close()
    }
}

internal inline fun <T> Connection.select(
    sql: String,
    vararg params: Any?,
    map: (Map<String, Any?>) -> T,
): List<T> {
    val st = this.prepareStatement(sql)
    try {
        params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        val rs = st.executeQuery()
        try {
            val meta = rs.metaData
            val cols = meta.columnCount
            val result = mutableListOf<T>()
            while (rs.next()) {
                val row = HashMap<String, Any?>(cols)
                for (i in 1..cols) {
                    row[meta.getColumnLabel(i)] = rs.getObject(i)
                }
                result.add(map(row))
            }
            return result
        } finally {
            rs.close()
        }
    } finally {
        st.close()
    }
}
