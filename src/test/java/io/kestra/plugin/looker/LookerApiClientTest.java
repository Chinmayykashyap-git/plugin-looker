package io.kestra.plugin.looker;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.context.TestRunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContext;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@WireMockTest
@KestraTest
class LookerApiClientTest {
    private static final String LOGIN_PATH = "/api/4.0/login";
    private static final String LOGOUT_PATH = "/api/4.0/logout";
    private static final String ACCESS_TOKEN = "test-access-token";

    @Inject
    private TestRunContextFactory runContextFactory;

    @Test
    void shouldEncodePathSegments() {
        assertThat(LookerApiClient.encodePathSegment("../x")).isEqualTo("..%2Fx");
        assertThat(LookerApiClient.encodePathSegment("a?x=#b c")).isEqualTo("a%3Fx%3D%23b%20c");
        assertThat(LookerApiClient.encodePathSegment("look_1-2.v~")).isEqualTo("look_1-2.v~");
        assertThatThrownBy(() -> LookerApiClient.encodePathSegment("..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LookerApiClient.encodePathSegment(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldLoginWithFormCredentials(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo(LOGIN_PATH))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                {"access_token":"test-access-token"}
                """)));
        stubLogout();

        try (LookerApiClient ignored = client(wireMockRuntimeInfo)) {
            verify(postRequestedFor(urlPathEqualTo(LOGIN_PATH))
                .withRequestBody(containing("client_id=test-client"))
                .withRequestBody(containing("client_secret=test-secret")));
        }
    }

    @Test
    void shouldSendAuthenticatedRequest(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubLogin();
        stubFor(get(urlPathEqualTo("/api/4.0/ping"))
            .withHeader("Authorization", equalTo("token " + ACCESS_TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        stubLogout();

        try (LookerApiClient client = client(wireMockRuntimeInfo)) {
            assertThat(client.request("GET", "/api/4.0/ping", null))
                .asString()
                .isEqualTo("{\"ok\":true}");
        }

        verify(getRequestedFor(urlPathEqualTo("/api/4.0/ping"))
            .withHeader("Authorization", equalTo("token " + ACCESS_TOKEN)));
    }

    @Test
    void shouldLogoutAfterClientIsClosed(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubLogin();
        stubLogout();

        client(wireMockRuntimeInfo).close();

        verify(deleteRequestedFor(urlPathEqualTo(LOGOUT_PATH))
            .withHeader("Authorization", equalTo("token " + ACCESS_TOKEN)));
    }

    @Test
    void shouldRejectFailedLogin(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubFor(post(urlPathEqualTo(LOGIN_PATH))
            .willReturn(aResponse().withStatus(401).withBody("credentials rejected")));

        Throwable failure = catchThrowable(() -> client(wireMockRuntimeInfo));
        assertThat(failure)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("HTTP status 401");
        assertThat(failure.getMessage()).doesNotContain("test-secret");
    }

    @Test
    void shouldRejectNonSuccessfulApiResponse(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubLogin();
        stubFor(get(urlPathEqualTo("/api/4.0/failure"))
            .willReturn(aResponse().withStatus(503).withBody("upstream unavailable")));
        stubLogout();

        try (LookerApiClient client = client(wireMockRuntimeInfo)) {
            Throwable failure = catchThrowable(() -> client.request("GET", "/api/4.0/failure", null));
            assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP status 503");
            assertThat(failure.getMessage()).doesNotContain("upstream unavailable");
        }
    }

    @Test
    void shouldRejectMalformedLoginResponse(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubFor(post(urlPathEqualTo(LOGIN_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{")));

        assertThatThrownBy(() -> client(wireMockRuntimeInfo))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not valid JSON");
    }

    @Test
    void shouldRejectEmptyLoginResponse(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubFor(post(urlPathEqualTo(LOGIN_PATH))
            .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client(wireMockRuntimeInfo))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("response was empty");
    }

    private LookerApiClient client(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        RunContext runContext = this.runContextFactory.of("looker-test");
        return new LookerApiClient(runContext, wireMockRuntimeInfo.getHttpBaseUrl(), "test-client", "test-secret");
    }

    private static void stubLogin() {
        stubFor(post(urlPathEqualTo(LOGIN_PATH))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                {"access_token":"test-access-token"}
                """)));
    }

    private static void stubLogout() {
        stubFor(delete(urlPathEqualTo(LOGOUT_PATH))
            .withHeader("Authorization", equalTo("token " + ACCESS_TOKEN))
            .willReturn(aResponse().withStatus(204)));
    }
}
