package pl.somaskan.questgpt

object QuestEndpoints {
    const val PUBLIC_BACKEND = "https://questgpt-openai-backend-d16j9e.v2.appdeploy.ai"

    fun resolveBackend(configured: String): String {
        val clean = configured.trim().trimEnd('/')
        return if (clean.startsWith("https://") && !clean.contains("10.0.2.2")) clean else PUBLIC_BACKEND
    }
}
