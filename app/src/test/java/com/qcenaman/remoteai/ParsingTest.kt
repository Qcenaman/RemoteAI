package com.qcenaman.remoteai

import com.qcenaman.remoteai.net.baseUrl
import com.qcenaman.remoteai.net.parseApprovals
import com.qcenaman.remoteai.net.parseChat
import com.qcenaman.remoteai.net.parseModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    @Test
    fun chatOutputSplitsReasoningToolsAndMessage() {
        val r = parseChat(
            """{"model_instance_id":"x","output":[
              {"type":"reasoning","content":"thinking"},
              {"type":"tool_call","tool":"pc","arguments":{"c":"sys"},"output":"CPU 8 cores","provider_info":{"type":"plugin","plugin_id":"mcp/contextsaver"}},
              {"type":"message","content":"Your PC has 8 cores."}],
              "stats":{},"response_id":"resp_9"}"""
        )
        assertEquals("Your PC has 8 cores.", r.text)
        assertEquals("thinking", r.reasoning)
        assertEquals("pc", r.toolCalls.single().tool)
        assertEquals("""{"c":"sys"}""", r.toolCalls.single().arguments)
        assertEquals("resp_9", r.responseId)
        assertTrue(r.approvals.isEmpty())
    }

    @Test
    fun approvalsNullMeansNone() {
        assertTrue(parseApprovals("""{"pending":null}""").isEmpty())
        assertEquals("ab12cd34", parseApprovals("""{"pending":[{"id":"ab12cd34","what":"x","status":"pending"}]}""").single().id)
    }

    @Test
    fun modelsKeepOnlyChatModels() {
        val ms = parseModels("""{"data":[{"id":"a","type":"llm","state":"loaded"},{"id":"e","type":"embeddings","state":"loaded"},{"id":"b","type":"vlm","state":"not-loaded"}]}""")
        assertEquals(listOf("a" to true, "b" to false), ms.map { it.id to it.loaded })
    }

    @Test
    fun baseUrlAcceptsIpNameAndScheme() {
        assertEquals("http://100.109.168.97:1234", baseUrl(" 100.109.168.97 ", 1234))
        assertEquals("http://desktop.tail1234.ts.net:1235", baseUrl("http://desktop.tail1234.ts.net/", 1235))
        assertEquals("http://[fd7a:115c:a1e0::1]:1234", baseUrl("fd7a:115c:a1e0::1", 1234))
    }
}

class ProvisionTest {
    @Test
    fun fillsOnlyBlankTokens() {
        val s = com.qcenaman.remoteai.data.Settings(lmToken = "keep")
        val f = fillEmptyTokens(s, "new", " ap ", null)
        assertEquals("keep", f.lmToken)
        assertEquals("ap", f.approvalToken)
        assertEquals("", f.tokenSaverToken)
        assertEquals(s.host, f.host)
    }
}
