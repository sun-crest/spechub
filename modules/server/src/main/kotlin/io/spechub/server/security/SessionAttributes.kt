package io.spechub.server.security

/** Spring Session 属性：登录设备信息（C4 活动会话列表读取；只存非敏感快照）。 */
object SessionAttributes {
    const val IP = "spechub.session.ip"
    const val USER_AGENT = "spechub.session.userAgent"
    const val LOGIN_AT = "spechub.session.loginAt"
    const val AUTH_METHOD = "spechub.session.authMethod"
    const val PRINCIPAL_NAME = "spechub.session.principalName"
}
