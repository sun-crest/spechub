package io.spechub.server.web

import io.spechub.server.service.ChangelogService
import io.spechub.server.service.SubscriptionService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.NotificationRecord
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.Stage
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
 * 阶段 B（B4）—— 变更订阅：消费团队声明依赖某接口（repo + method + path 稳定寻址）→ 结构变更时收到通知。
 * - POST   /api/subscriptions           订阅 {repoId, method, path, teamId, notifyUrl?}（team lead / org_admin；幂等 upsert）
 * - GET    /api/subscriptions?repoId=&teamId=   订阅列表
 * - DELETE /api/subscriptions/{id}      取消订阅（team lead / org_admin；204）
 * - GET    /api/subscriptions/notifications?teamId=&limit=   变更通知（摄取期由 Ingestor 写入）
 *
 * B4 不兼容变更：`team: string` 已移除，必须传 `teamId`（旧字段返回 400）。
 * 订阅写权限收紧为 team lead / org_admin；repo 可见性校验保持不变（ADR §4.3）。
 */
@RestController
@RequestMapping("/api/subscriptions")
class SubscriptionController(
    private val projection: ProjectionStore,
    private val changelogService: ChangelogService,
    private val subscriptionService: SubscriptionService,
    private val auth: AuthService,
) {
    data class SubscribeRequest(
        val repoId: Long,
        @field:NotBlank val method: String,
        @field:NotBlank val path: String,
        val teamId: Long? = null,
        val notifyUrl: String = "",
    )

    @PostMapping
    fun subscribe(
        @RequestBody @Valid body: SubscribeRequest,
        request: HttpServletRequest,
    ): SubscriptionRecord {
        val teamId =
            body.teamId
                ?: throw ApiException(ApiErrorCode.VALIDATION, "teamId 必填（旧 team 字段已移除）")
        auth.requireRepoVisible(request, body.repoId, Stage.RELEASE)
        auth.requireTeamLead(request, teamId, "订阅")
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
                notifyUrl = body.notifyUrl.trim(),
                teamId = teamId,
            )
        changelogService.add(
            body.repoId,
            apiId,
            auth.currentActor(request),
            "subscription",
            "${sub.teamName} 订阅 ${body.method.uppercase()} ${body.path}",
        )
        return sub
    }

    @GetMapping
    fun list(
        request: HttpServletRequest,
        @RequestParam repoId: Long? = null,
        @RequestParam teamId: Long? = null,
    ): List<SubscriptionRecord> {
        if (repoId != null) {
            auth.requireRepoVisible(request, repoId, Stage.RELEASE)
            return subscriptionService.list(repoId, teamId)
        }
        return subscriptionService.list(auth.visibleRepoIds(request, Stage.RELEASE), teamId)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unsubscribe(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ) {
        val sub = subscriptionService.get(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "订阅 $id 不存在")
        auth.requireRepoVisible(request, sub.repoId, Stage.RELEASE)
        val teamId = sub.teamId ?: throw ApiException(ApiErrorCode.CONFLICT, "订阅缺少团队归属")
        auth.requireTeamLead(request, teamId, "取消订阅")
        subscriptionService.unsubscribe(id)
        changelogService.add(sub.repoId, sub.apiId, auth.currentActor(request), "subscription.delete", "取消订阅：${sub.teamName} ${sub.method} ${sub.path}")
    }

    @GetMapping("/notifications")
    fun notifications(
        request: HttpServletRequest,
        @RequestParam teamId: Long? = null,
        @RequestParam limit: Int = 50,
    ): List<NotificationRecord> {
        if (request.getParameter("team") != null) {
            throw ApiException(ApiErrorCode.BAD_REQUEST, "team 参数已移除，请使用 teamId")
        }
        return subscriptionService.listNotifications(teamId, auth.visibleRepoIds(request, Stage.RELEASE), limit)
    }
}
