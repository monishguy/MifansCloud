package com.monishguy.mifanscloud.data.auth

/**
 * 一次有效的 serviceToken 会话。
 *
 * @param serviceToken 小米云请求链使用的会话令牌
 * @param obtainedAt   换取时刻（毫秒时间戳，用于 10 分钟刷新判定）
 */
data class SessionToken(
    val serviceToken: String,
    val obtainedAt: Long,
)
