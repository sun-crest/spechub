package io.spechub.server.web

import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.SpecIndexStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 全仓归一经接口查询（MCP `search_api` / CLI `status` 的 HTTP 后端） */
@RestController
@RequestMapping("/api")
class ApiQueryController(private val index: SpecIndexStore) {

    @GetMapping("/apis")
    fun search(
        @RequestParam q: String? = null,
        @RequestParam method: String? = null,
    ): List<ApiRecord> = index.listAllApis(q, method?.uppercase())
}