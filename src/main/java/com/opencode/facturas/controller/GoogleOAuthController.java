package com.opencode.facturas.controller;

import com.opencode.facturas.service.GoogleOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/sheets/oauth")
public class GoogleOAuthController {
    private static final String COOKIE = "facturas_google_oauth";
    private final GoogleOAuthService oauth;
    public GoogleOAuthController(GoogleOAuthService oauth) { this.oauth = oauth; }

    @PostMapping(value = "/connect", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> connect(HttpServletRequest request) {
        requireOrigin(request);
        var begin = oauth.begin();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,
                cookie(begin.browserCookie(), 600)).body(Map.of("authorizationUrl", begin.authorizationUrl()));
    }

    @PostMapping(value = "/picker", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> picker(HttpServletRequest request) {
        requireOrigin(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(oauth.pickerSession());
    }

    @PostMapping(value = "/disconnect", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> disconnect(HttpServletRequest request) {
        requireOrigin(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE, cookie("", 0))
                .body(Map.of("message", oauth.disconnect()));
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error,
            @CookieValue(name = COOKIE, required = false) String browser) {
        boolean success = false;
        try { oauth.complete(state, browser, code, error); success = true; }
        catch (IllegalArgumentException | IllegalStateException ignored) { /* Do not echo codes, state or upstream errors. */ }
        String message = success ? "Google conectado. Podés cerrar esta ventana y volver a la aplicación."
                : "No se completó la conexión con Google. Cerrá esta ventana y volvé a intentarlo desde la aplicación.";
        String nonce = java.util.UUID.randomUUID().toString();
        // Static outcome only. Never embed untrusted query strings or tokens in this document.
        String html = "<!doctype html><html lang=\"es\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<title>Conexión con Google</title><body><p>" + message + "</p><script nonce=\"" + nonce + "\">"
                + "history.replaceState(null,'',location.pathname);if(window.opener){window.opener.postMessage({type:'facturas-google-oauth',success:"
                + success + "},location.origin);window.close();}</script></body></html>";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE, cookie("", 0))
                .header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce + "'; frame-ancestors 'none'; base-uri 'none'")
                .body(html);
    }

    private void requireOrigin(HttpServletRequest request) {
        if (!oauth.origin().equals(request.getHeader("Origin"))) {
            throw new IllegalArgumentException("Abrí la aplicación desde la dirección local configurada para conectar Google.");
        }
    }
    private static String cookie(String value, long seconds) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).sameSite("Lax")
                .path(GoogleOAuthService.CALLBACK_PATH).maxAge(Duration.ofSeconds(seconds)).build().toString();
    }
}
