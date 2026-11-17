package io.spechub.server.service

import io.spechub.server.repository.OrgEntity
import io.spechub.server.repository.OrgMembershipEntity
import io.spechub.server.repository.OrgMembershipRepository
import io.spechub.server.repository.OrgRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 组织域 Service（阶段 A 单组织；SaaS 多组织复用同一模型）。
 *
 * - 默认组织由 bootstrap 创建；`defaultOrg()` 对空库/测试做幂等兜底。
 * - `org_membership` 是用户 × 组织关系，与项目级 `membership` 分开。
 */
@Service
class OrgService(
    private val orgs: OrgRepository,
    private val memberships: OrgMembershipRepository,
) {
    companion object {
        const val DEFAULT_SLUG = "default"
        const val ROLE_ADMIN = "org_admin"
        const val ROLE_MEMBER = "org_member"
        const val STATUS_ACTIVE = "active"
    }

    /** 默认组织（不存在则创建；阶段 A 唯一组织）。 */
    fun defaultOrg(): OrgEntity =
        orgs.findBySlug(DEFAULT_SLUG)
            ?: orgs.save(OrgEntity(name = DEFAULT_SLUG, slug = DEFAULT_SLUG, createdAt = Instant.now()))

    fun defaultOrgId(): Long = checkNotNull(defaultOrg().id) { "org 创建后应回填 id" }

    fun findBySlug(slug: String): OrgEntity? = orgs.findBySlug(slug)

    fun membership(
        userId: Long,
        orgId: Long,
    ): OrgMembershipEntity? = memberships.findByUserIdAndOrgId(userId, orgId)

    fun orgRole(
        userId: Long,
        orgId: Long,
    ): String? = membership(userId, orgId)?.orgRole

    fun isOrgAdmin(
        userId: Long,
        orgId: Long,
    ): Boolean = orgRole(userId, orgId) == ROLE_ADMIN

    fun membersOf(orgId: Long): List<OrgMembershipEntity> = memberships.findAllByOrgIdOrderByUserIdAsc(orgId)

    /** 幂等写入组织成员关系（bootstrap admin / 新用户默认 org_member）。 */
    @Transactional
    fun ensureMembership(
        userId: Long,
        orgId: Long,
        role: String = ROLE_MEMBER,
    ): OrgMembershipEntity {
        val existing = memberships.findByUserIdAndOrgId(userId, orgId)
        return if (existing != null) {
            if (existing.orgRole == role && existing.status == STATUS_ACTIVE) {
                existing
            } else {
                memberships.save(existing.copy(orgRole = role, status = STATUS_ACTIVE))
            }
        } else {
            memberships.save(
                OrgMembershipEntity(
                    userId = userId,
                    orgId = orgId,
                    orgRole = role,
                    status = STATUS_ACTIVE,
                    createdAt = Instant.now(),
                ),
            )
        }
    }

    /** 删除用户前清理组织关系（FK RESTRICT）。 */
    @Transactional
    fun removeUser(userId: Long) {
        memberships.deleteAllByUserId(userId)
    }
}
