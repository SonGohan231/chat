package pl.somaskan.questgpt2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun socketOpenNeverMeansMicrophoneReady() {
        val gate = SessionGate()
        assertFalse(gate.acknowledge("session.created"))
        assertFalse(gate.ready)
        assertTrue(gate.acknowledge("session.updated"))
        assertFalse(gate.acknowledge("session.updated"))
        gate.close()
        assertFalse(gate.acknowledge("session.updated"))
        assertFalse(gate.ready)
    }
    @Test fun staleBlankMissingAndFutureFramesCannotBeShared() {
        assertFalse(Protocol.fresh(null, 10000))
        assertFalse(Protocol.fresh(Frame("data:image/jpeg;base64,AA",4999,1),10000))
        assertFalse(Protocol.fresh(Frame("data:image/jpeg;base64,AA",10001,1),10000))
        assertFalse(Protocol.fresh(Frame("data:image/jpeg;base64,AA",9999,1,true),10000))
        assertTrue(Protocol.fresh(Frame("data:image/jpeg;base64,AA",9999,1),10000))
    }
    @Test fun realResponsesRestEnvelopeWithReasoningParses() {
        val json="""{"status":"completed","output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"Widzę czerwony przycisk."}]}]}"""
        assertEquals("Widzę czerwony przycisk.",Protocol.output(json))
    }
    @Test fun refusalsAreShownRatherThanAnEmptySuccess() {
        assertEquals("Nie mogę tego zrobić.",Protocol.output("""{"output":[{"type":"message","content":[{"type":"refusal","refusal":"Nie mogę tego zrobić."}]}]}"""))
    }
    @Test fun incompleteResponseWithoutTextIsActionable() {
        val result=runCatching{Protocol.output("""{"status":"incomplete","output":[{"type":"reasoning"}]}""")}
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("limit"))
    }
    @Test fun requestKeepsDialogueButDoesNotReplayLocalGuideOrPartialAudio() {
        val history=listOf(Message("1","guide","configuration"),Message("2","user","Poprzednie pytanie"),Message("3","assistant","niedokończone",false))
        val request=Protocol.responseBody("test-model",history,"A ten obraz?",listOf("data:image/jpeg;base64,AQ=="))
        assertFalse(request.getBoolean("store"))
        assertFalse(request.has("previous_response_id"))
        val input=request.getJSONArray("input")
        assertEquals(2,input.length())
        assertEquals("Poprzednie pytanie",input.getJSONObject(0).getString("content"))
        assertEquals("input_image",input.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
    }
    @Test fun realtimeUsesGaPcmAndClientControlledReplies() {
        val session=Protocol.session("test-model").getJSONObject("session")
        assertFalse(session.has("modalities"))
        assertFalse(session.has("input_audio_format"))
        assertEquals("audio",session.getJSONArray("output_modalities").getString(0))
        val input=session.getJSONObject("audio").getJSONObject("input")
        assertEquals(24000,input.getJSONObject("format").getInt("rate"))
        assertFalse(input.getJSONObject("turn_detection").getBoolean("create_response"))
        val image=Protocol.item("screen",listOf("data:image/jpeg;base64,AQ==")).getJSONObject("item").getJSONArray("content").getJSONObject(1)
        assertFalse(image.has("detail"))
    }
    @Test fun providerErrorsCannotLeakKeysToUi() {
        val message=Protocol.error(403,JSONObject().put("error",JSONObject().put("message","rejected sk-proj-ABC123_secret")).toString())
        assertFalse(message.contains("sk-proj"))
        assertTrue(Protocol.error(429,"""{"error":{"code":"insufficient_quota"}}""").contains("środków"))
    }
}
