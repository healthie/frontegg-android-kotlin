package com.frontegg.android.embedded

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the script builder that applies host-supplied theme/copy overrides to the
 * embedded login box.
 */
class LoginBoxCustomizationTest {

    // region no-op cases

    @Test
    fun `returns null when nothing is provided`() {
        assertNull(LoginBoxCustomization.script(null, null))
    }

    @Test
    fun `returns null when overrides are empty`() {
        assertNull(LoginBoxCustomization.script(emptyMap(), emptyMap()))
    }

    // endregion

    // region payload

    @Test
    fun `theme options are emitted under themeV2`() {
        val script = LoginBoxCustomization.script(
            mapOf("loginBox" to mapOf("palette" to mapOf("primary" to mapOf("main" to "#3F6655")))),
            null
        )

        assertNotNull(script)
        assertTrue(script!!.contains("\"themeV2\""))
        assertTrue(script.contains("#3F6655"))
        assertFalse(script.contains("\"localizations\""))
    }

    @Test
    fun `localizations are emitted under localizations`() {
        val script = LoginBoxCustomization.script(
            null,
            mapOf("en" to mapOf("loginBox" to mapOf("login" to mapOf("title" to "Sign-in"))))
        )

        assertNotNull(script)
        assertTrue(script!!.contains("\"localizations\""))
        assertTrue(script.contains("Sign-in"))
        assertFalse(script.contains("\"themeV2\""))
    }

    @Test
    fun `both overrides are emitted together`() {
        val script = LoginBoxCustomization.script(
            mapOf("loginBox" to mapOf("logo" to mapOf("image" to "https://example.com/logo.png"))),
            mapOf("en" to mapOf("loginBox" to mapOf("login" to mapOf("continue" to "Log In"))))
        )

        assertNotNull(script)
        assertTrue(script!!.contains("\"themeV2\""))
        assertTrue(script.contains("\"localizations\""))
        assertTrue(script.contains("example.com"))
        assertTrue(script.contains("Log In"))
    }

    // endregion

    // region script shape

    @Test
    fun `script targets the login box metadata request`() {
        val script = LoginBoxCustomization.script(
            mapOf("loginBox" to mapOf("themeName" to "modern")),
            null
        )

        assertNotNull(script)
        assertTrue(script!!.contains(LoginBoxCustomization.METADATA_PATH))
        assertTrue(script.contains("window.fetch"))
        // Guards against double-installing when the script is injected twice.
        assertTrue(script.contains("__fronteggLoginBoxOverridesInstalled"))
    }

    // endregion

    // region encoding

    @Test
    fun `javascript line terminators are escaped`() {
        // U+2028/U+2029 are valid inside JSON but terminate a line in JavaScript source,
        // which would break the emitted script.
        val script = LoginBoxCustomization.script(
            null,
            mapOf("en" to mapOf("note" to "a\u2028b\u2029c"))
        )

        assertNotNull(script)
        assertFalse(script!!.contains("\u2028"))
        assertFalse(script.contains("\u2029"))
        assertTrue(script.contains("\\u2028"))
        assertTrue(script.contains("\\u2029"))
    }

    @Test
    fun `emitted overrides parse back as json`() {
        val script = LoginBoxCustomization.script(
            mapOf("loginBox" to mapOf("palette" to mapOf("primary" to mapOf("main" to "#16284A")))),
            null
        )

        val json = script!!
            .substringAfter("var overrides = ")
            .substringBefore(";\n")

        val parsed = JSONObject(json)
        val main = parsed.getJSONObject("themeV2")
            .getJSONObject("loginBox")
            .getJSONObject("palette")
            .getJSONObject("primary")
            .getString("main")

        assertEquals("#16284A", main)
    }

    @Test
    fun `quotes in copy do not break the script`() {
        val script = LoginBoxCustomization.script(
            null,
            mapOf("en" to mapOf("loginBox" to mapOf("login" to mapOf("title" to "Don't \"stop\" now"))))
        )

        assertNotNull(script)
        // JSONObject escapes the double quotes; the apostrophe is safe because the payload
        // is embedded as an object literal, not a string.
        assertTrue(script!!.contains("Don't \\\"stop\\\" now"))
    }

    // endregion

    // region origin scoping

    @Test
    fun `auth origin drops path and query`() {
        assertEquals(
            "https://auth.example.com",
            LoginBoxCustomization.authOrigin("https://auth.example.com/oauth/account/login?x=1")
        )
    }

    @Test
    fun `auth origin preserves a non-default port`() {
        assertEquals(
            "https://auth.example.com:8443",
            LoginBoxCustomization.authOrigin("https://auth.example.com:8443")
        )
    }

    @Test
    fun `auth origin is null for unusable input`() {
        assertNull(LoginBoxCustomization.authOrigin(""))
        assertNull(LoginBoxCustomization.authOrigin("not a url"))
    }

    @Test
    fun `script reads a URL object request as well as a string`() {
        val script = LoginBoxCustomization.script(mapOf("loginBox" to mapOf("themeName" to "modern")), null)

        // Parity with iOS: fetch(new URL(...)) must resolve via .href, not just .url.
        assertTrue(script!!.contains("input.href"))
        assertTrue(script.contains("input.url"))
    }

    // endregion

    // MARK: sign-up redirect

    @Test
    fun `sign-up url alone is enough to build a script`() {
        val script = LoginBoxCustomization.script(null, null, "https://app.example.com/sign_up/select")

        assertNotNull(script)
        assertTrue(script!!.contains("https://app.example.com/sign_up/select"))
        assertTrue(script.contains("[data-test-id=\"redirect-to-signup\"]"))
    }

    @Test
    fun `sign-up url is null when absent`() {
        val script = LoginBoxCustomization.script(
            null,
            mapOf("en" to mapOf("loginBox" to mapOf("login" to mapOf("title" to "Sign-in"))))
        )

        assertNotNull(script)
        assertTrue(script!!.contains("var SIGN_UP_URL = null;"))
    }

    @Test
    fun `non-http schemes are rejected`() {
        listOf(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "myapp://sign_up"
        ).forEach {
            assertNull("expected $it to be rejected", LoginBoxCustomization.sanitizedSignUpUrl(it))
        }
    }

    @Test
    fun `relative and empty urls are rejected`() {
        assertNull(LoginBoxCustomization.sanitizedSignUpUrl("/users/sign_up/select"))
        assertNull(LoginBoxCustomization.sanitizedSignUpUrl(""))
        assertNull(LoginBoxCustomization.sanitizedSignUpUrl(null))
    }

    @Test
    fun `http and https are accepted`() {
        assertEquals(
            "https://app.example.com/x",
            LoginBoxCustomization.sanitizedSignUpUrl("https://app.example.com/x")
        )
        assertEquals(
            "http://localhost:3000/x",
            LoginBoxCustomization.sanitizedSignUpUrl("http://localhost:3000/x")
        )
    }

    @Test
    fun `a rejected url does not produce a sign-up only script`() {
        assertNull(LoginBoxCustomization.script(null, null, "javascript:alert(1)"))
    }

    @Test
    fun `overrides and redirect coexist`() {
        val script = LoginBoxCustomization.script(
            mapOf("loginBox" to mapOf("palette" to mapOf("primary" to mapOf("main" to "#3F6655")))),
            mapOf("en" to mapOf("loginBox" to mapOf("login" to mapOf("signUpLink" to "Sign up now")))),
            "https://app.example.com/sign_up"
        )

        assertNotNull(script)
        assertTrue(script!!.contains("#3F6655"))
        assertTrue(script.contains("Sign up now"))
        assertTrue(script.contains("https://app.example.com/sign_up"))
    }
}
