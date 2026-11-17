package io.spechub.server.web

import io.spechub.server.service.ChangelogService
import io.spechub.server.service.RepoService
import io.spechub.server.service.SubscriptionService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.NotificationRecord
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.SubscriptionRecord
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/*
 * I7 —— 变更订阅：消费团队声明依赖某接口（repo + method + path 稳定寻址）→ 结构变更时收到通知。
 * - POST   /api/subscriptions           订阅 {repoId, method, path, team, notifyUrl?}（登录即可；幂等 upsert）
 * - GET    /api/subscriptions?repoId=   订阅列表
 * - DELETE /api/subscriptions/{id}      取消订阅（204）
 * - GET    /api/subscriptions/notifications?team=&limit=   变更通知（摄取期由 Ingestor 写入）
 * 通知触发：Ingestor 比对 specHash 判定结构变化（幂等续跑同内容不通知），见 §6.4 决策 5 消费侧默认读 release。
 */
@RestController
@RequestMapping("/api/subscriptions")
class SubscriptionController(
    private val projection: ProjectionStore,
    private val changelogService: ChangelogService,
    private val repoService: RepoService,
    private val subscriptionService: SubscriptionService,
    private val auth: AuthService,
) {
    data class SubscribeRequest(
        val repoId: Long,
        @field:NotBlank val method: String,
        @field:NotBlank val path: String,
        @field:NotBlank val team: String,
        val notifyUrl: String = "",
    )

    @PostMapping
    fun subscribe(
        @RequestBody @Valid body: SubscribeRequest,
        request: HttpServletRequest,
    ): SubscriptionRecord {
        auth.requireAuthenticated(request, "订阅")
        repoService.getRepo(body.repoId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo ${body.repoId} 不存在")
        // api_id 尽力关联（method+path 命中当前投影则填，便于详情展示；非 FK）
        val apiId =
            projection
                .listApis(body.repoId, method = body.method.uppercase(), keyword = body.path.removePrefix("/"))
                .firstOrNull { it.path == body.path }
                ?.id
        val sub =
            subscriptionService.subscribe(
                body.repoId,
                apiId,
                body.method.uppercase(),
                body.path,
                body.team.trim(),
                body.notifyUrl.trim(),
            )
        changelogService.add(
            body.repoId,
            apiId,
            auth.currentActor(request),
            "subscription",
            "${body.team} 订阅 ${body.method.uppercase()} ${body.path}",
        )
        return sub
    }

    @GetMapping
    fun list(
        @RequestParam repoId: Long? = null,
    ): List<SubscriptionRecord> = subscriptionService.list(repoId)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unsubscribe(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ) {
        auth.requireAuthenticated(request, "取消订阅")
        val sub = subscriptionService.get(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "订阅 $id 不存在")
        subscriptionService.unsubscribe(id)
        changelogService.add(sub.repoId, sub.apiId, auth.currentActor(request), "subscription.delete", "取消订阅：${sub.team} ${sub.method} ${sub.path}")
    }

    @GetMapping("/notifications")
    fun notifications(
        @RequestParam team: String? = null,
        @RequestParam limit: Int = 50,
    ): List<NotificationRecord> = subscriptionService.listNotifications(team, limit)
}
