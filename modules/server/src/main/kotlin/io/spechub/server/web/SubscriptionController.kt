package io.spechub.server.web

import io.spechub.spec.index.NotificationRecord
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.index.SubscriptionRecord
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import jakarta.servlet.http.HttpServletRequest
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
    private val index: SpecIndexStore,
    private val auth: AuthService,
) {

    data class SubscribeRequest(
        val repoId: Long,
        val method: String,
        val path: String,
        val team: String,
        val notifyUrl: String = "",
    )

    @PostMapping
    fun subscribe(@RequestBody body: SubscribeRequest, request: HttpServletRequest): SubscriptionRecord {
        auth.requireAuthenticated(request, "订阅")
        if (body.team.isBlank() || body.method.isBlank() || body.path.isBlank()) {
            throw ApiException(ApiErrorCode.BAD_REQUEST, "team / method / path 不能为空")
        }
        index.getRepo(body.repoId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo ${body.repoId} 不存在")
        // api_id 尽力关联（method+path 命中当前投影则填，便于详情展示；非 FK）
        val apiId = index.listApis(body.repoId, method = body.method.uppercase(), keyword = body.path.removePrefix("/"))
            .firstOrNull { it.path == body.path }?.id
        val sub = index.upsertSubscription(
            body.repoId, apiId, body.method.uppercase(), body.path, body.team.trim(), body.notifyUrl.trim(),
        )
        index.addChangelog(body.repoId, apiId, currentActor(request), "subscription",
            "${body.team} 订阅 ${body.method.uppercase()} ${body.path}")
        return sub
    }

    @GetMapping
    fun list(@RequestParam repoId: Long? = null): List<SubscriptionRecord> = index.listSubscriptions(repoId)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unsubscribe(@PathVariable id: Long, request: HttpServletRequest) {
        auth.requireAuthenticated(request, "取消订阅")
        val sub = index.getSubscription(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "订阅 $id 不存在")
        index.deleteSubscription(id)
        index.addChangelog(sub.repoId, sub.apiId, currentActor(request), "subscription.delete", "取消订阅：${sub.team} ${sub.method} ${sub.path}")
    }

    @GetMapping("/notifications")
    fun notifications(@RequestParam team: String? = null, @RequestParam limit: Int = 50): List<NotificationRecord> =
        index.listNotifications(team, limit)

    private fun currentActor(request: HttpServletRequest): String =
        request.getAttribute(AuthService.REQ_USERNAME) as? String ?: "anonymous"
}