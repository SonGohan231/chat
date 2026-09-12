package pl.somaskan.questgptfree

import java.net.URI

/** Restricts native capabilities; this is not an authentication or site-access bypass. */
object WebPolicy {
    const val HOME = "https://chatgpt.com/"
    private val chatHosts = setOf("chatgpt.com", "www.chatgpt.com")
    private val navigationHosts = chatHosts + setOf("auth.openai.com", "auth.chatgpt.com",
        "auth0.openai.com", "accounts.google.com", "appleid.apple.com", "login.microsoftonline.com", "login.live.com")
    fun host(url: String?): String? = runCatching {
        val u = URI(url ?: "")
        if(u.scheme != "https" || u.rawUserInfo != null || (u.port != -1 && u.port != 443)) null
        else u.host?.lowercase()
    }.getOrNull()
    fun chat(url: String?) = host(url) in chatHosts
    fun inside(url: String?) = host(url) in navigationHosts
    fun nativeCapability(origin: String?, topLevel: String?) = chat(origin) && chat(topLevel)
    fun external(url: String?) = host(url) != null
}
