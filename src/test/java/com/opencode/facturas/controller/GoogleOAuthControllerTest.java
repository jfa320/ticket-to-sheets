package com.opencode.facturas.controller;

import com.opencode.facturas.service.GoogleOAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class GoogleOAuthControllerTest {
    final GoogleOAuthService oauth = mock(GoogleOAuthService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        when(oauth.origin()).thenReturn("http://localhost:8080");
        mvc = MockMvcBuilders.standaloneSetup(new GoogleOAuthController(oauth))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void startBindsHttpOnlyCookieAndRejectsCrossOriginAndFormRequests() throws Exception {
        when(oauth.begin()).thenReturn(new GoogleOAuthService.Begin("https://accounts.google.com/o/oauth2/v2/auth", "synthetic-browser"));
        mvc.perform(post("/api/sheets/oauth/connect").header("Origin", "http://localhost:8080")
                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().httpOnly("facturas_google_oauth", true))
                .andExpect(cookie().path("facturas_google_oauth", GoogleOAuthService.CALLBACK_PATH))
                .andExpect(jsonPath("$.browserCookie").doesNotExist());
        mvc.perform(post("/api/sheets/oauth/connect").header("Origin", "https://foreign.example")
                .contentType("application/json").content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sheets/oauth/disconnect").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sheets/oauth/connect").header("Origin", "http://localhost:8080")
                .contentType("application/x-www-form-urlencoded").content("x=y")).andExpect(status().isUnsupportedMediaType());
        verify(oauth).begin(); verify(oauth, never()).disconnect();
    }
    @Test void callbackClearsCookieAndNeverReflectsUntrustedInput() throws Exception {
        doThrow(new IllegalArgumentException("sensitive-secret")).when(oauth).complete(any(), any(), any(), any());
        var result = mvc.perform(get(GoogleOAuthService.CALLBACK_PATH).param("state", "<script>evil</script>")
                .param("code", "private-code").cookie(new Cookie("facturas_google_oauth", "browser")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(cookie().maxAge("facturas_google_oauth", 0)).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("success:false", "location.origin", "history.replaceState")
                .doesNotContain("private-code", "sensitive-secret", "<script>evil");
    }
    @Test void onlyPickerReceivesShortLivedAccessAndResponsesAreNotCached() throws Exception {
        when(oauth.pickerSession()).thenReturn(new GoogleOAuthService.PickerSession("short-lived", "public-key", "123"));
        mvc.perform(post("/api/sheets/oauth/picker").header("Origin", "http://localhost:8080")
                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").value("short-lived"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist()).andExpect(jsonPath("$.clientSecret").doesNotExist());
    }
}
