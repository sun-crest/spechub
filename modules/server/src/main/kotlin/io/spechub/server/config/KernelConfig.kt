package io.spechub.server.config

import io.spechub.spec.gstore.GitStore
import io.spechub.spec.index.IndexDb
import io.spechub.spec.index.PlatformStore
import io.spechub.spec.index.ProjectionStore
import io.spechub.server.ingest.Ingestor
import io.spechub.server.service.ReleaseService
import io.spechub.server.service.RepoService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.nio.file.Files
import java.nio.file.Path

/*
 * E1/E8 —— 内核组件装配 + 启动初始化：
 * - kernel 各 bean（零框架依赖，此处只是接线）；
 * - 启动时确保目录与 DB 存在（E8：目录、DB；admin 种子见 ApplicationInit）。
 * - @EnableScheduling 供 E7 轮询兜底。
 */

@Configuration
@EnableConfigurationProperties(SpecHubProperties::class)
@EnableScheduling
class KernelConfig(private val props: SpecHubProperties) {

    @Bean
    fun gitStore(): GitStore = GitStore()

    @Bean
    fun indexDb(): IndexDb {
        // 启动即初始化：目录 + SQLite 迁移（§6.4 决策 7：索引可重建，损坏非事故）
        // 统一绝对路径：规避测试/部署 worker cwd 差异导致的相对路径目录分叉
        Files.createDirectories(props.gitSourcePath().toAbsolutePath())
        Files.createDirectories(props.sqlitePath().toAbsolutePath())
        Files.createDirectories(props.artifactsPath().toAbsolutePath())
        val db = IndexDb(props.sqlitePath().toAbsolutePath().resolve("spechub-index.db"))
        db.migrate()
        return db
    }

    /** 管理域 DAO（Phase 2 迁入独立管理库；当前与投影同库） */
    @Bean
    fun platformStore(): PlatformStore = PlatformStore(indexDb())

    /** 投影域 DAO（SQLite，可重建） */
    @Bean
    fun projectionStore(): ProjectionStore = ProjectionStore(indexDb())

    @Bean
    fun ingestor(repoService: RepoService, releaseService: ReleaseService): Ingestor = Ingestor(
        gitStore(),
        projectionStore(),
        platformStore(),
        { repoId, status, error -> repoService.updateSyncStatus(repoId, status, error) },
        { repoId, projection -> releaseService.replay(repoId, projection) },
        props,
    )

    /** Mock 规则引擎（零 Spring 内核模块，此处接线注入投影与管理域） */
    @Bean
    fun mockEngine(): io.spechub.spec.mock.MockEngine =
        io.spechub.spec.mock.MockEngine(projectionStore(), platformStore())
}