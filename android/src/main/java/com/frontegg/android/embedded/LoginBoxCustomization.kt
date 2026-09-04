package com.frontegg.android.embedded

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.frontegg.android.services.FronteggInnerStorage
import org.json.JSONObject
import java.net.URI

/**
 * Runtime theme and copy overrides for the embedded login box.
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
 * Android counterpart of iOS `LoginBoxCustomization` (WKUserScript at `.atDocumentStart`).
 */
object LoginBoxCustomization {
    private val TAG = LoginBoxCustomization::class.java.simpleName

    /** Substring identifying the login box's own metadata request. */
    const val METADATA_PATH = "/frontegg/metadata?entityName=adminBox"

    /**
     * Registers the overrides script, if the host set any. No-op (with a warning) on legacy
     * WebViews without [WebViewFeature.DOCUMENT_START_SCRIPT] — the same capability gate
     * [StepUpWebDriver] and the Admin Portal bridge use.
     */
    fun install(webView: WebView, storage: FronteggInnerStorage = FronteggInnerStorage()) {
        val script = script(
            storage.loginBoxThemeOptions,
            storage.loginBoxLocalizations,
            storage.loginBoxSignUpUrl
        ) ?: return

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
     * Accepts only absolute `http(s)` URLs.
     *
     * The value reaches `location.assign`, so anything else — `javascript:` above all —
     * is dropped rather than injected.
     */
    internal fun sanitizedSignUpUrl(signUpUrl: String?): String? {
        if (signUpUrl.isNullOrEmpty()) return null
        val uri = try {
            URI(signUpUrl)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return signUpUrl
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
        signUpUrl: String? = null
    ): String? {
        val overrides = JSONObject()

        if (!themeOptions.isNullOrEmpty()) {
            overrides.put("themeV2", JSONObject(themeOptions))
        }
        if (!localizations.isNullOrEmpty()) {
            overrides.put("localizations", JSONObject(localizations))
        }

        val redirectUrl = sanitizedSignUpUrl(signUpUrl)

        // Either concern alone is worth injecting for.
        if (overrides.length() == 0 && redirectUrl == null) {
            return null
        }

        // U+2028/U+2029 are valid inside JSON but terminate a line in JavaScript source.
        val json = overrides.toString()
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

        // JSONObject.quote produces a complete JS/JSON string literal, including the
        // surrounding quotes and all interior escaping.
        val signUpUrlLiteral = redirectUrl?.let {
            JSONObject.quote(it)
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")
        } ?: "null"

        return """
        (function () {
          if (window.__fronteggLoginBoxOverridesInstalled) { return; }
          var originalFetch = window.fetch;
          if (typeof originalFetch !== 'function') { return; }
          window.__fronteggLoginBoxOverridesInstalled = true;

          var overrides = $json;
          var SIGN_UP_URL = $signUpUrlLiteral;
          var METADATA_PATH = '$METADATA_PATH';
          var SIGN_UP_SELECTOR = '[data-test-id="redirect-to-signup"]';

          // The box renders its sign-up link as
          // `<... data-test-id="redirect-to-signup" onClick=goToSignup>`, inside a
          // `[data-test-id="sign-up-message"]` container. Both ids are part of the
          // box's test contract, unlike its generated class names.
          //
          // The listener sits on `document` in the capture phase because the box lives
          // in an open shadow root: `click` and `keydown` are composed, so they cross
          // the boundary and `composedPath()` still exposes the real target. Capture
          // also runs before the box's own handler, which stops its internal navigation.
          function isSignUpTrigger(event) {
            if (!SIGN_UP_URL) { return false; }
            var path = typeof event.composedPath === 'function' ? event.composedPath() : [];
            for (var i = 0; i < path.length; i++) {
              var node = path[i];
              if (!node || node.nodeType !== 1) { continue; }
              if (typeof node.matches === 'function' && node.matches(SIGN_UP_SELECTOR)) { return true; }
              if (typeof node.closest === 'function' && node.closest(SIGN_UP_SELECTOR)) { return true; }
            }
            return false;
          }

          function redirectToSignUp(event) {
            if (!isSignUpTrigger(event)) { return; }
            event.preventDefault();
            event.stopPropagation();
            if (typeof event.stopImmediatePropagation === 'function') {
              event.stopImmediatePropagation();
            }
            window.location.assign(SIGN_UP_URL);
          }

          if (SIGN_UP_URL) {
            document.addEventListener('click', redirectToSignUp, true);
            // The link is a focusable non-button with its own onKeyDown, so keyboard
            // and switch-access users reach it this way rather than by click.
            document.addEventListener('keydown', function (event) {
              if (event.key === 'Enter' || event.key === ' ' || event.key === 'Spacebar') {
                redirectToSignUp(event);
              }
            }, true);
          }

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
        })();
        """.trimIndent()
    }
}
