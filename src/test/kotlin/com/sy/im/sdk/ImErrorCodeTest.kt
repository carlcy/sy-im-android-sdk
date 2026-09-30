package com.sy.im.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImErrorCodeTest {
    @Test
    fun codesMatchBackendErrcode() {
        assertEquals(3001, ImErrorCode.IM_NOT_ENABLED)
        assertEquals(3003, ImErrorCode.QUOTA_MAU)
        assertEquals(3004, ImErrorCode.QUOTA_MESSAGES)
        assertEquals(4003, ImErrorCode.TRIAL_RETIRED)
        assertEquals(4005, ImErrorCode.SENSITIVE_REJECTED)
        assertEquals(4006, ImErrorCode.CONTENT_REJECTED)
        assertEquals(4031, ImErrorCode.CREDENTIAL_SUSPENDED)
        assertEquals(4032, ImErrorCode.CREDENTIAL_REVOKED)
        assertEquals(4033, ImErrorCode.CREDENTIAL_EXPIRED)
        assertEquals(4290, ImErrorCode.RATE_LIMITED)
    }

    private fun failure(http: Int, body: String): ImControlPlaneException {
        try {
            ImControlPlane.checkResponse(http, ImControlPlane.parseBody(body), body)
        } catch (e: ImControlPlaneException) {
            return e
        }
        fail("expected ImControlPlaneException")
        throw IllegalStateException()
    }

    @Test
    fun successPasses() {
        ImControlPlane.checkResponse(200, JSONObject("""{"code":0,"data":{}}"""), "")
        ImControlPlane.checkResponse(200, JSONObject(), "")
    }

    @Test
    fun businessCodeIsSurfaced() {
        val e = failure(200, """{"code":4006,"msg":"内容审核拒绝"}""")
        assertEquals(4006, e.code)
        assertEquals(200, e.httpStatus)
        assertEquals("内容审核拒绝", e.message)
        assertTrue(e.isContentRejected)
        assertFalse(e.isCredentialBlocked)

        val c = failure(200, """{"code":4032,"msg":"访问凭证已吊销"}""")
        assertTrue(c.isCredentialBlocked)
    }

    @Test
    fun httpErrorWithoutBodyUsesStatus() {
        val e = failure(429, "")
        assertEquals(429, e.code)
        assertEquals(429, e.httpStatus)
        val r = failure(429, """{"code":4290,"msg":"too many"}""")
        assertEquals(ImErrorCode.RATE_LIMITED, r.code)
        val h = failure(502, "<html>bad gateway</html>")
        assertEquals(502, h.code)
    }
}
