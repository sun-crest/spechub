package io.spechub.server.store

import io.spechub.spec.index.IndexDb
import io.spechub.spec.index.SpecIndexStore
import io.spechub.server.config.SpecHubProperties
import jakarta.annotation.PreDestroy
import java.nio.file.Files
import org.springframework.stereotype.Service

/** 索引库生命周期：打开/建表（E8 初始化）/关闭。单例持有，供摄取与查询共用。 */
@Service
class IndexService(private val props: SpecHubProperties) {

    private val db: IndexDb = openDb()

    val store: SpecIndexStore = SpecIndexStore(db)

    private fun openDb(): IndexDb {
        Files.createDirectories(props.gitsourceDir)
        Files.createDirectories(props.indexDbFile.parent)
        return IndexDb.open(props.indexDbFile)
    }

    @PreDestroy
    fun close() {
        db.close()
    }
}