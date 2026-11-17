package io.spechub.server.web

import io.spechub.server.service.SubscriptionService
import io.spechub.spec.index.NotificationRecord
import io.spechub.spec.index.SpecIndexStore
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
 *
 * R4：订阅/取消的业务编排（apiId 尽力关联 + changelog）下沉至 `SubscriptionService`；
 * 本类只保留 HTTP 映射、鉴权入口与薄查询。
 */
@RestController
@RequestMapping("/api/subscriptions")
class SubscriptionController(
    private val index: SpecIndexStore,
    private val auth: AuthService,
    private val subscriptionService: SubscriptionService,
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
        return subscriptionService.subscribe(
            repoId = body.repoId,
            method = body.method,
            path = body.path,
            team = body.team,
            notifyUrl = body.notifyUrl,
            actor = auth.currentActor(request),
        )
    }

    @GetMapping
    fun list(
        @RequestParam repoId: Long? = null,
    ): List<SubscriptionRecord> = index.listSubscriptions(repoId)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unsubscribe(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ) {
        auth.requireAuthenticated(request, "取消订阅")
        subscriptionService.unsubscribe(id, auth.currentActor(request))
    }

    @GetMapping("/notifications")
    fun notifications(
        @RequestParam team: String? = null,
        @RequestParam limit: Int = 50,
    ): List<NotificationRecord> = index.listNotifications(team, limit)
}
