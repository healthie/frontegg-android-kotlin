package com.frontegg.android.embedded

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.frontegg.android.services.FronteggInnerStorage
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Runtime theme, copy and footer overrides for the embedded login box.
 *
 * Login-box appearance is configured per Frontegg environment, which cannot express
 * appearance that is only known at runtime — for example a multi-brand app that resolves
 * each brand's logo and colours from its own backend, where there is no per-application
 * axis to configure.
 *
 * The login box resolves its own appearance by `fetch`ing
 * `/frontegg/metadata?entityName=adminBox` and reading `rows[0].configuration`. Rather
 * than styling the rendered DOM — whose class names are generated and change between
 * login-box releases — this installs a document-start script that wraps `window.fetch`,
 * waits for that response, and deep-merges the host's values into the configuration
 * before the box parses it. Every other request passes through untouched.
 *
 * The footer is the one exception, and deliberately so. The box's configuration has no
 * slot for content below the card — the React SDK exposes a `boxFooter` render prop for
 * exactly this, which has no equivalent when the box is served into a WebView. It is
 * therefore built in the DOM, but from a *structured* payload (text and label/URL pairs)
 * rather than host-supplied HTML, and anchored on `[data-test-id="root-element"]`, a test
 * id that is part of the box's test contract rather than its generated styling. No host
 * string is ever interpreted as markup.
 *
 * Android counterpart of iOS `LoginBoxCustomization` (WKUserScript at `.atDocumentStart`).
 */
object LoginBoxCustomization {
    private val TAG = LoginBoxCustomization::class.java.simpleName

    /** Substring identifying the login box's own metadata request. */
    const val METADATA_PATH = "/frontegg/metadata?entityName=adminBox"

    /** Schemes that can execute script or read local data; never admissible. */
    private val DENIED_SCHEMES = setOf(
        "javascript", "data", "file", "blob", "about", "vbscript", "intent", "content"
    )

    /**
     * Registers the overrides script, if the host set any. No-op (with a warning) on legacy
     * WebViews without [WebViewFeature.DOCUMENT_START_SCRIPT] — the same capability gate
     * [StepUpWebDriver] and the Admin Portal bridge use.
     */
    fun install(webView: WebView, storage: FronteggInnerStorage = FronteggInnerStorage()) {
        val script = script(
            storage.loginBoxThemeOptions,
            storage.loginBoxLocalizations,
            storage.loginBoxFooter
        ) { scheme -> hostAppHandles(webView.context, scheme) } ?: return

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported; login box overrides not installed")
            return
        }

        // Scope to the auth origin so third-party frames (captcha, social providers) keep
        // an untouched `fetch` and never receive the host's branding payload. iOS achieves
        // the same with `forMainFrameOnly: true`.
        val origin = authOrigin(storage.baseUrl)
        if (origin == null) {
            Log.w(TAG, "baseUrl has no usable origin; login box overrides not installed")
            return
        }

        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
    }

    /**
     * Normalizes a host-supplied footer payload, dropping anything unsafe, or `null` when
     * nothing usable survives.
     *
     * A segment whose URL fails the scheme check degrades to plain text rather than being
     * dropped: the footer's usual job is a legal attribution, and a sentence missing a
     * fragment reads as a bug, whereas an unlinked label still says what it needs to say.
     *
     * [handlesScheme] is injected rather than resolved here because, unlike iOS
     * (`CFBundleURLTypes`), Android exposes no way to ENUMERATE an app's own registered
     * schemes — only to ask whether anything handles a given one. See [hostAppHandles].
     */
    internal fun sanitizedFooter(
        footer: Map<String, Any?>?,
        handlesScheme: (String) -> Boolean = { false }
    ): JSONObject? {
        @Suppress("UNCHECKED_CAST")
        val rows = footer?.get("rows") as? List<Map<String, Any?>> ?: return null
        if (rows.isEmpty()) return null

        val sanitizedRows = JSONArray()

        for (row in rows) {
            @Suppress("UNCHECKED_CAST")
            val segments = row["segments"] as? List<Map<String, Any?>> ?: continue

            val sanitizedSegments = JSONArray()
            for (segment in segments) {
                val text = segment["text"] as? String
                if (!text.isNullOrEmpty()) {
                    sanitizedSegments.put(JSONObject().put("text", text))
                    continue
                }

                val label = segment["label"] as? String
                if (label.isNullOrEmpty()) continue

                val safeUrl = sanitizedLinkUrl(segment["url"] as? String, handlesScheme)
                if (safeUrl != null) {
                    sanitizedSegments.put(
                        JSONObject().put("label", label).put("url", safeUrl)
                    )
                } else {
                    sanitizedSegments.put(JSONObject().put("text", label))
                }
            }

            if (sanitizedSegments.length() == 0) continue

            val variant = if (row["variant"] as? String == "fine") "fine" else "body"
            sanitizedRows.put(
                JSONObject().put("variant", variant).put("segments", sanitizedSegments)
            )
        }

        if (sanitizedRows.length() == 0) return null

        return JSONObject()
            .put("hideCaptchaBadge", footer["hideCaptchaBadge"] as? Boolean ?: false)
            .put("rows", sanitizedRows)
    }

    /**
     * Accepts an absolute `http(s)` URL, or a URL on a scheme the host app itself
     * declares an intent filter for.
     *
     * The value becomes an `href`, so anything else — `javascript:` above all — is
     * dropped rather than injected. A host app is trusted, but this value can originate
     * in remote configuration on its side, and the cost of the check is nothing.
     *
     * The app-scheme case is what makes a hand-off possible: a host that wants its
     * sign-up flow presented in its own browser rather than inside this WebView points a
     * footer link at its own scheme, and [FronteggWebClient] opens it and dismisses
     * the box.
     */
    internal fun sanitizedLinkUrl(
        url: String?,
        handlesScheme: (String) -> Boolean = { false }
    ): String? {
        if (url.isNullOrEmpty()) return null
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null

        if (scheme == "http" || scheme == "https") {
            if (uri.host.isNullOrEmpty()) return null
            return url
        }

        // Denied ahead of the predicate so the guard that actually matters does not
        // depend on it: `handlesScheme` is supplied by the caller, and a script-executing
        // scheme must be impossible to admit even through a wrong one.
        if (scheme in DENIED_SCHEMES) return null

        return if (handlesScheme(scheme)) url else null
    }

    /**
     * The `http(s)` footer URLs, which must be opened outside the login box.
     *
     * The box's WebView has no navigation chrome, so letting an attribution link load in
     * place strands the user with no way back. [FronteggWebClient] consults this
     * exact-match set and hands those URLs to a browser instead — an allowlist rather
     * than a general "off-origin" rule, because the box legitimately navigates to social
     * identity providers.
     */
    internal fun footerExternalUrls(
        footer: Map<String, Any?>?,
        handlesScheme: (String) -> Boolean = { false }
    ): Set<String> {
        val sanitized = sanitizedFooter(footer, handlesScheme) ?: return emptySet()
        val rows = sanitized.optJSONArray("rows") ?: return emptySet()

        val urls = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val segments = rows.optJSONObject(i)?.optJSONArray("segments") ?: continue
            for (j in 0 until segments.length()) {
                val url = segments.optJSONObject(j)?.optString("url").orEmpty()
                if (url.isEmpty()) continue
                val scheme = try {
                    URI(url).scheme?.lowercase()
                } catch (e: Exception) {
                    null
                }
                if (scheme == "http" || scheme == "https") urls.add(url)
            }
        }
        return urls
    }

    /**
     * Whether the HOST APP — not some other installed app — declares a browsable VIEW
     * filter for [scheme].
     *
     * Restricted to the host's own package deliberately: any app can claim a scheme, and
     * accepting a third party's would let remote configuration bounce the user out to it.
     */
    internal fun hostAppHandles(context: Context, scheme: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            context.packageManager
                .queryIntentActivities(intent, 0)
                .any { it.activityInfo?.packageName == context.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "could not resolve handlers for scheme '$scheme'", e)
            false
        }
    }

    /** `scheme://host[:port]` for [baseUrl], or null when it cannot be parsed. */
    internal fun authOrigin(baseUrl: String): String? {
        val uri = try {
            URI(baseUrl)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        return if (uri.port != -1) "$scheme://$host:${uri.port}" else "$scheme://$host"
    }

    /**
     * Builds the document-start script, or `null` when there is nothing to override so
     * callers can skip injecting entirely.
     */
    fun script(
        themeOptions: Map<String, Any?>?,
        localizations: Map<String, Any?>?,
        footer: Map<String, Any?>? = null,
        handlesScheme: (String) -> Boolean = { false }
    ): String? {
        val overrides = JSONObject()

        if (!themeOptions.isNullOrEmpty()) {
            overrides.put("themeV2", JSONObject(themeOptions))
        }
        if (!localizations.isNullOrEmpty()) {
            overrides.put("localizations", JSONObject(localizations))
        }

        val sanitizedFooter = sanitizedFooter(footer, handlesScheme)
        if (!footer.isNullOrEmpty() && sanitizedFooter == null) {
            Log.w(TAG, "loginBoxFooter was set but contains no usable rows; no footer rendered")
        }

        // Either concern alone is worth injecting for.
        if (overrides.length() == 0 && sanitizedFooter == null) {
            return null
        }

        // U+2028/U+2029 are valid inside JSON but terminate a line in JavaScript source.
        fun jsSafe(json: String) = json
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

        val overridesJson = jsSafe(overrides.toString())
        val footerJson = sanitizedFooter?.let { jsSafe(it.toString()) } ?: "null"

        return """
        (function () {
          if (window.__fronteggLoginBoxOverridesInstalled) { return; }
          var originalFetch = window.fetch;
          if (typeof originalFetch !== 'function') { return; }
          window.__fronteggLoginBoxOverridesInstalled = true;

          var overrides = $overridesJson;
          var FOOTER = $footerJson;
          var METADATA_PATH = '$METADATA_PATH';
          var FOOTER_ID = 'frontegg-login-box-footer';
          var ROOT_SELECTOR = '[data-test-id="root-element"]';
          // The footer follows the login screen only, mirroring the React SDK where
          // `boxFooter` is configured under `login` and the other screens
          // (forgot-password, MFA, signup) carry their own. Keyed on the login title's
          // test id, which is present on that screen and no other.
          var LOGIN_MARKER = '[data-test-id="login-page-title"]';

          function isPlainObject(value) {
            return value !== null && typeof value === 'object' && !Array.isArray(value);
          }

          // Host values win on conflict; nested objects merge rather than replace so
          // untouched keys keep whatever the environment already configured.
          function deepMerge(target, source) {
            Object.keys(source).forEach(function (key) {
              var incoming = source[key];
              if (isPlainObject(incoming) && isPlainObject(target[key])) {
                deepMerge(target[key], incoming);
              } else {
                target[key] = incoming;
              }
            });
            return target;
          }

          function requestUrl(input) {
            if (typeof input === 'string') { return input; }
            if (input && typeof input.url === 'string') { return input.url; }
            if (input && typeof input.href === 'string') { return input.href; }
            return '';
          }

          window.fetch = function (input, init) {
            var pending = originalFetch.apply(this, arguments);
            if (requestUrl(input).indexOf(METADATA_PATH) === -1) { return pending; }

            return pending.then(function (response) {
              if (!response || !response.ok) { return response; }

              return response.clone().json().then(function (body) {
                var configuration =
                  body && body.rows && body.rows[0] && body.rows[0].configuration;
                if (!isPlainObject(configuration)) { return response; }

                deepMerge(configuration, overrides);

                return new Response(JSON.stringify(body), {
                  status: response.status,
                  statusText: response.statusText,
                  headers: { 'Content-Type': 'application/json' }
                });
              }).catch(function () {
                // Malformed or already-consumed body: leave the box on the
                // environment's own configuration rather than failing the request.
                return response;
              });
            });
          };

          if (!FOOTER) { return; }

          // ---- footer --------------------------------------------------------
          // Google's badge is rendered into the light DOM at body level, so a
          // document-level stylesheet reaches it even though the box itself lives in a
          // shadow root. Hiding it is only permitted alongside the visible attribution
          // the host supplies in `rows`.
          function hideCaptchaBadge() {
            if (!FOOTER.hideCaptchaBadge) { return; }
            if (document.getElementById(FOOTER_ID + '-badge-style')) { return; }
            var head = document.head || document.documentElement;
            if (!head) { return; }
            var style = document.createElement('style');
            style.id = FOOTER_ID + '-badge-style';
            style.textContent = '.grecaptcha-badge{visibility:hidden!important;}';
            head.appendChild(style);
          }

          function boxShadowRoot() {
            var found = null;
            var all = document.querySelectorAll('*');
            for (var i = 0; i < all.length; i++) {
              var host = all[i];
              if (host.shadowRoot && host.shadowRoot.querySelector(ROOT_SELECTOR)) {
                found = host.shadowRoot;
                break;
              }
            }
            return found;
          }

          // The box nests several full-height centring wrappers inside [root-element]
          // before the card. Descend while a wrapper has exactly one child that still
          // fills it; the first child that does NOT fill its parent is the card, so we
          // stop on the card's parent and append there. The footer then flows directly
          // under the card, and that column's justify-content:center re-centres the pair.
          //
          // Deliberately geometric rather than structural: it reads the layout the box
          // actually produced instead of hard-coding a depth, so an added or removed
          // wrapper does not silently move the footer inside the card.
          function insertionPoint(shadowRoot) {
            var node = shadowRoot.querySelector(ROOT_SELECTOR);
            if (!node) { return null; }
            var guard = 0;
            while (node.childElementCount === 1 && guard++ < 10) {
              var child = node.firstElementChild;
              var parentHeight = node.getBoundingClientRect().height;
              var childHeight = child.getBoundingClientRect().height;
              if (!(parentHeight > 0 && childHeight >= 0.9 * parentHeight)) { break; }
              node = child;
            }
            return node;
          }

          function buildRow(row) {
            var line = document.createElement('div');
            var fine = row.variant === 'fine';
            line.style.cssText = [
              'text-align:center',
              'margin-top:' + (fine ? '24px' : '16px'),
              'font-size:' + (fine ? '9px' : '14px'),
              'line-height:1.3',
              'color:' + (fine ? 'rgba(0,0,0,0.6)' : 'rgba(0,0,0,0.87)'),
              'font-family:inherit'
            ].join(';');

            (row.segments || []).forEach(function (segment) {
              if (segment.url) {
                var anchor = document.createElement('a');
                // textContent, never markup: host copy is never parsed as HTML.
                anchor.textContent = segment.label;
                anchor.setAttribute('href', segment.url);
                anchor.style.cssText =
                  'font-size:inherit;line-height:inherit;color:#2e74c7;text-decoration:none';
                line.appendChild(anchor);
              } else if (segment.text) {
                line.appendChild(document.createTextNode(segment.text));
              }
            });

            return line;
          }

          function renderFooter() {
            var shadowRoot = boxShadowRoot();
            if (!shadowRoot) { return; }

            var existing = shadowRoot.querySelector('#' + FOOTER_ID);
            var onLoginScreen = !!shadowRoot.querySelector(LOGIN_MARKER);

            if (!onLoginScreen) {
              if (existing) { existing.remove(); }
              return;
            }
            // Still mounted where we put it: nothing to do. React re-rendering the card
            // can detach it, which is what the observer below is for.
            if (existing && existing.isConnected) { return; }

            var target = insertionPoint(shadowRoot);
            if (!target) { return; }

            var wrapper = document.createElement('div');
            wrapper.id = FOOTER_ID;
            wrapper.style.cssText = 'width:100%;display:block;flex:0 0 auto';
            (FOOTER.rows || []).forEach(function (row) {
              wrapper.appendChild(buildRow(row));
            });
            target.appendChild(wrapper);

            hideCaptchaBadge();
          }

          // This script runs at document start, so the box does not exist yet, and its
          // screen changes happen inside a shadow root — which a MutationObserver on
          // `document` does not see. So: poll until the shadow root appears, then observe
          // it directly, keeping a slow poll as a backstop in case the box is re-created
          // wholesale.
          var observed = null;
          function attach() {
            renderFooter();
            var shadowRoot = boxShadowRoot();
            if (!shadowRoot || observed === shadowRoot) { return; }
            if (typeof MutationObserver !== 'function') { return; }
            observed = shadowRoot;
            new MutationObserver(function () {
              renderFooter();
            }).observe(shadowRoot, { childList: true, subtree: true });
          }

          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', attach);
          } else {
            attach();
          }
          setInterval(attach, 500);
        })();
        """.trimIndent()
    }
}
