package io.spechub.server.service

import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.index.SubscriptionRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/*
 * R4 —— 订阅域业务编排（规范 §1.2）。
 *
 * - api_id「尽力关联」规则（method+path 命中当前投影则填，仅展示快照、非 FK）在此收口；
 * - 订阅 / 取消订阅的 changelog 统一在此落；
 * - Controller 只保留 HTTP 映射与鉴权入口（登录即可，见 AuthService.requireAuthenticated）。
 */
@Service
class SubscriptionService(
    private val index: SpecIndexStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 订阅（幂等 upsert）+ apiId 尽力关联 + changelog */
    fun subscribe(
        repoId: Long,
        method: String,
        path: String,
        team: String,
        notifyUrl: String,
        actor: String,
    ): SubscriptionRecord {
        index.getRepo(repoId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $repoId 不存在")
        val normalizedMethod = method.uppercase()
        val normalizedTeam = team.trim()
        // api_id 尽力关联（method+path 命中当前投影则填，便于详情展示；非 FK）
        val apiId =
            index
                .listApis(repoId, method = normalizedMethod, keyword = path.removePrefix("/"))
                .firstOrNull { it.path == path }
                ?.id
        val sub =
            index.upsertSubscriptionWithChangelog(
                repoId = repoId,
                apiId = apiId,
                method = normalizedMethod,
                path = path,
                team = normalizedTeam,
                notifyUrl = notifyUrl.trim(),
                actor = actor,
                action = "subscription",
                detail = "$team 订阅 $normalizedMethod $path",
            )
        log.info("订阅 repo={} {} {} team={} actor={}", repoId, normalizedMethod, path, normalizedTeam, actor)
        return sub
    }

    /** 取消订阅（204 语义由 Controller 声明）+ changelog */
    fun unsubscribe(
        id: Long,
        actor: String,
    ) {
        val sub = index.getSubscription(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "订阅 $id 不存在")
        index.deleteSubscriptionWithChangelog(
            id = id,
            repoId = sub.repoId,
            apiId = sub.apiId,
            actor = actor,
            action = "subscription.delete",
            detail = "取消订阅：${sub.team} ${sub.method} ${sub.path}",
        )
        log.info("取消订阅 id={} repo={} {} {} team={} actor={}", id, sub.repoId, sub.method, sub.path, sub.team, actor)
    }
}
