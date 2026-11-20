package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface AuditEventRepository : CrudRepository<AuditEventEntity, Long>
