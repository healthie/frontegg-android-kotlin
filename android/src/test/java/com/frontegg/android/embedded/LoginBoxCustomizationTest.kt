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

    // region footer

    /** A footer with one usable row, for tests that only care that it is valid. */
    private fun footerPayload(
        url: String = "https://policies.google.com/privacy",
        hideBadge: Boolean = true
    ): Map<String, Any?> = mapOf(
        "hideCaptchaBadge" to hideBadge,
        "rows" to listOf(
            mapOf(
                "variant" to "fine",
                "segments" to listOf(
                    mapOf("text" to "Protected by reCAPTCHA — "),
                    mapOf("label" to "Privacy Policy", "url" to url)
                )
            )
        )
    )

    @Test
    fun `footer alone is enough to build a script`() {
        val script = LoginBoxCustomization.script(null, null, footerPayload())

        assertNotNull(script)
        assertTrue(script!!.contains("https://policies.google.com/privacy"))
        assertTrue(script.contains("[data-test-id=\"root-element\"]"))
    }

    @Test
    fun `footer is null when absent`() {
        val script = LoginBoxCustomization.script(
            null,
            mapOf("en" to mapOf("loginBox" to mapOf("login" to mapOf("title" to "Sign-in"))))
        )

        assertNotNull(script)
        assertTrue(script!!.contains("var FOOTER = null;"))
    }

    @Test
    fun `the badge is only hidden when asked`() {
        val hiding = LoginBoxCustomization.script(null, null, footerPayload(hideBadge = true))
        assertTrue(hiding!!.contains("\"hideCaptchaBadge\":true"))

        val notHiding = LoginBoxCustomization.script(null, null, footerPayload(hideBadge = false))
        assertTrue(notHiding!!.contains("\"hideCaptchaBadge\":false"))
    }

    /**
     * Hiding Google's badge is only permissible alongside a visible attribution, so the
     * rule ships with the footer and nowhere else.
     */
    @Test
    fun `the badge rule ships only with a footer`() {
        val copyOnly = LoginBoxCustomization.script(
            null,
            mapOf("en" to mapOf("loginBox" to mapOf("login" to mapOf("title" to "Sign-in"))))
        )

        assertNotNull(copyOnly)
        assertTrue(copyOnly!!.contains("var FOOTER = null;"))
        assertTrue(copyOnly.contains("if (!FOOTER) { return; }"))
    }

    /** Host copy must never be interpreted as markup. */
    @Test
    fun `footer copy is rendered as text not html`() {
        val script = LoginBoxCustomization.script(null, null, footerPayload())

        assertNotNull(script)
        assertTrue(script!!.contains("anchor.textContent = segment.label;"))
        assertTrue(script.contains("createTextNode(segment.text)"))
        assertFalse(script.contains(".innerHTML ="))
        assertFalse(script.contains("insertAdjacentHTML"))
    }

    /**
     * The footer follows the login screen only, matching the React SDK where `boxFooter`
     * is configured under `login`.
     */
    @Test
    fun `footer is scoped to the login screen`() {
        val script = LoginBoxCustomization.script(null, null, footerPayload())

        assertNotNull(script)
        assertTrue(script!!.contains("[data-test-id=\"login-page-title\"]"))
    }

    // endregion

    // region footer validation

    /**
     * A bad URL degrades the segment to plain text rather than dropping it: a legal
     * attribution missing a fragment reads as a bug, whereas an unlinked label still says
     * what it needs to say.
     */
    @Test
    fun `unsafe schemes degrade to plain text`() {
        listOf(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "otherapp://sign-up"
        ).forEach { url ->
            val sanitized = LoginBoxCustomization.sanitizedFooter(footerPayload(url = url))
            assertNotNull("expected $url to still produce a footer", sanitized)

            val segments = sanitized!!.getJSONArray("rows")
                .getJSONObject(0)
                .getJSONArray("segments")

            assertEquals("expected $url to keep both segments", 2, segments.length())
            assertEquals("Privacy Policy", segments.getJSONObject(1).getString("text"))
            assertFalse(segments.getJSONObject(1).has("url"))
        }
    }

    /**
     * The security guard must not be delegated. A caller supplying a predicate that says
     * yes to everything still cannot inject a script-executing scheme.
     */
    @Test
    fun `script schemes stay rejected even when the predicate accepts everything`() {
        listOf(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "blob:https://x/y",
            "about:blank",
            "vbscript:msgbox",
            "intent://x#Intent;end",
            "content://settings/secure"
        ).forEach {
            assertNull(
                "expected $it to be rejected",
                LoginBoxCustomization.sanitizedLinkUrl(it) { true }
            )
        }
    }

    @Test
    fun `http and https links are accepted`() {
        assertEquals(
            "https://app.example.com/x",
            LoginBoxCustomization.sanitizedLinkUrl("https://app.example.com/x")
        )
        assertEquals(
            "http://localhost:3000/x",
            LoginBoxCustomization.sanitizedLinkUrl("http://localhost:3000/x")
        )
    }

    @Test
    fun `relative and empty links are rejected`() {
        assertNull(LoginBoxCustomization.sanitizedLinkUrl("/users/sign_up/select"))
        assertNull(LoginBoxCustomization.sanitizedLinkUrl(""))
        assertNull(LoginBoxCustomization.sanitizedLinkUrl(null))
    }

    /**
     * The hand-off case: a host app pointing a footer link at its own scheme so the box
     * dismisses and the app presents sign-up itself. Rejected by default, so the
     * predicate is what admits it — nothing is accepted merely for being custom.
     */
    @Test
    fun `an app-registered scheme is accepted`() {
        assertEquals(
            "healthie://sign-up",
            LoginBoxCustomization.sanitizedLinkUrl("healthie://sign-up") { it == "healthie" }
        )
        assertNull(
            LoginBoxCustomization.sanitizedLinkUrl("otherapp://sign-up") { it == "healthie" }
        )
    }

    @Test
    fun `an empty footer produces nothing`() {
        assertNull(LoginBoxCustomization.sanitizedFooter(null))
        assertNull(LoginBoxCustomization.sanitizedFooter(emptyMap()))
        assertNull(LoginBoxCustomization.sanitizedFooter(mapOf("rows" to emptyList<Any>())))
        assertNull(
            LoginBoxCustomization.sanitizedFooter(
                mapOf(
                    "rows" to listOf(
                        mapOf(
                            "variant" to "body",
                            "segments" to listOf(mapOf("label" to ""), mapOf("text" to ""))
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `an unknown variant falls back to body`() {
        val sanitized = LoginBoxCustomization.sanitizedFooter(
            mapOf(
                "rows" to listOf(
                    mapOf("variant" to "enormous", "segments" to listOf(mapOf("text" to "hi")))
                )
            )
        )

        assertNotNull(sanitized)
        assertEquals("body", sanitized!!.getJSONArray("rows").getJSONObject(0).getString("variant"))
    }

    // endregion

    // region external link allowlist

    /**
     * Only `http(s)` links leave for a browser. An app-scheme link is a hand-off that
     * dismisses the box, and must not be short-circuited into "open externally, keep the
     * box mounted".
     */
    @Test
    fun `external urls cover only http links`() {
        val urls = LoginBoxCustomization.footerExternalUrls(
            mapOf(
                "rows" to listOf(
                    mapOf(
                        "variant" to "body",
                        "segments" to listOf(
                            mapOf("label" to "Privacy", "url" to "https://policies.google.com/privacy"),
                            mapOf("label" to "Terms", "url" to "http://example.com/terms"),
                            mapOf("label" to "Sign up", "url" to "healthie://sign-up"),
                            mapOf("text" to "no link here")
                        )
                    )
                )
            )
        ) { it == "healthie" }

        assertEquals(
            setOf("https://policies.google.com/privacy", "http://example.com/terms"),
            urls
        )
    }

    @Test
    fun `external urls are empty without a footer`() {
        assertTrue(LoginBoxCustomization.footerExternalUrls(null).isEmpty())
        assertTrue(
            LoginBoxCustomization.footerExternalUrls(mapOf("rows" to emptyList<Any>())).isEmpty()
        )
    }

    /**
     * A rejected URL must not linger in the allowlist, or the web client would hand a
     * browser a value the footer never rendered.
     */
    @Test
    fun `external urls exclude rejected links`() {
        assertTrue(
            LoginBoxCustomization.footerExternalUrls(
                footerPayload(url = "javascript:alert(1)")
            ).isEmpty()
        )
    }

    @Test
    fun `overrides and footer coexist`() {
        val script = LoginBoxCustomization.script(
            mapOf("loginBox" to mapOf("palette" to mapOf("primary" to mapOf("main" to "#3F6655")))),
            mapOf("en" to mapOf("loginBox" to mapOf("login" to mapOf("title" to "Sign-in")))),
            footerPayload()
        )

        assertNotNull(script)
        assertTrue(script!!.contains("#3F6655"))
        assertTrue(script.contains("Sign-in"))
        assertTrue(script.contains("https://policies.google.com/privacy"))
    }

    // endregion
}
