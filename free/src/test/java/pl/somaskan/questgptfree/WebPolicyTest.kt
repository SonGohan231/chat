package pl.somaskan.questgptfree
import org.junit.Assert.*
import org.junit.Test
class WebPolicyTest {
    @Test fun microphoneAndFilesRequireExactTrustedHttpsOrigin() {
        assertTrue(WebPolicy.nativeCapability("https://chatgpt.com/","https://chatgpt.com/c/test"))
        for(url in listOf("https://chatgpt.com.evil.test/","https://evilchatgpt.com/","https://evil.test/?chatgpt.com",
            "http://chatgpt.com/","https://chatgpt.com@evil.test/","https://evil@chatgpt.com/","https://chatgpt.com:444/",
            "file:///chatgpt.com","javascript:alert(1)","content://chatgpt.com/foo")) {
            assertFalse(url,WebPolicy.nativeCapability(url,WebPolicy.HOME))
            assertFalse(url,WebPolicy.nativeCapability(WebPolicy.HOME,url))
        }
    }
    @Test fun loginNavigationDoesNotGrantNativeCapabilities() {
        assertTrue(WebPolicy.inside("https://accounts.google.com/signin"))
        assertTrue(WebPolicy.inside("https://auth.openai.com/log-in"))
        assertFalse(WebPolicy.nativeCapability("https://accounts.google.com/",WebPolicy.HOME))
        assertFalse(WebPolicy.inside("https://unrelated.test/"))
        assertFalse(WebPolicy.external("intent://example"))
    }
}
