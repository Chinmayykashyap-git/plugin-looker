package io.kestra.plugin.looker.looks;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.context.TestRunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.looker.queries.ResultFormat;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
@KestraTest
class LookerLookTasksTest {
    private static final String LOGIN_PATH = "/api/4.0/login";
    private static final String LOGOUT_PATH = "/api/4.0/logout";
    private static final String TOKEN = "look-test-token";

    @Inject
    private TestRunContextFactory runContextFactory;

    @Test
    void shouldRunSavedLookAndFetchRows(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(get(urlPathEqualTo("/api/4.0/looks/42/run/json"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("""
                [{"users.id":1,"users.name":"Alice"},{"users.id":2,"users.name":"Bob"}]
                """)));

        Run task = Run.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .lookId(Property.ofValue("42"))
            .limit(Property.ofValue("100"))
            .applyFormatting(Property.ofValue(true))
            .resultFormat(Property.ofValue(ResultFormat.JSON))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        FetchOutput output = task.run(context());

        assertThat(output.getSize()).isEqualTo(2L);
        assertThat(output.getRows()).hasSize(2);
        verify(getRequestedFor(urlPathEqualTo("/api/4.0/looks/42/run/json"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .withQueryParam("limit", equalTo("100"))
            .withQueryParam("apply_formatting", equalTo("true")));
        verify(0, getRequestedFor(urlPathEqualTo("/api/4.0/looks/42")));
    }

    @Test
    void shouldRunLookInlineWithMergedFilterOverrides(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(get(urlPathEqualTo("/api/4.0/looks/42"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("""
                {"id":"42","query":{"id":"9","slug":"abc","client_id":"c","can":{"run":true},"model":"shop","view":"orders",
                 "fields":["orders.id"],"filters":{"orders.status":"pending","orders.region":"EU"},"sorts":["orders.id"],"limit":"500",
                 "filter_expression":"matches_filter(${orders.region}, `EU,US`)",
                 "filter_config":{"orders.status":[{"type":"is","values":[{"constant":"pending"},{}]}]}}}
                """)));
        stubFor(post(urlPathEqualTo("/api/4.0/queries/run/json"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("[{\"orders.id\":1}]")));

        Run task = Run.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .lookId(Property.ofValue("42"))
            .limit(Property.ofValue("100"))
            .filters(Property.ofValue(Map.of("orders.status", "shipped")))
            .build();

        FetchOutput output = task.run(context());

        assertThat(output.getSize()).isEqualTo(1L);
        verify(0, getRequestedFor(urlPathEqualTo("/api/4.0/looks/42/run/json")));
        verify(postRequestedFor(urlPathEqualTo("/api/4.0/queries/run/json"))
            .withQueryParam("limit", equalTo("100"))
            .withRequestBody(equalToJson("""
                {"model":"shop","view":"orders","fields":["orders.id"],
                 "filters":{"orders.status":"shipped","orders.region":"EU"},"sorts":["orders.id"],"limit":"500",
                 "filter_expression":"matches_filter(${orders.region}, `EU,US`)","filter_config":null}
                """)));
    }

    @Test
    void shouldFailWhenLookHasNoQueryAndFiltersAreSet(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubAuthentication();
        stubFor(get(urlPathEqualTo("/api/4.0/looks/42"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"42\"}")));

        Run task = Run.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .lookId(Property.ofValue("42"))
            .filters(Property.ofValue(Map.of("orders.status", "shipped")))
            .build();

        assertThatThrownBy(() -> task.run(context()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("has no query");
    }

    @Test
    void shouldListSavedLooks(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(get(urlPathEqualTo("/api/4.0/looks/search"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("""
                [{"id":"1","title":"Monthly Sales"},{"id":"2","title":"User Cohorts"}]
                """)));

        List task = List.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .folderId(Property.ofValue("12"))
            .title(Property.ofValue("Monthly Sales"))
            .limit(Property.ofValue("10"))
            .fields(Property.ofValue(java.util.List.of("id", "title")))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        FetchOutput output = task.run(context());

        assertThat(output.getSize()).isEqualTo(2L);
        assertThat(output.getRows()).containsExactly(
            Map.of("id", "1", "title", "Monthly Sales"),
            Map.of("id", "2", "title", "User Cohorts")
        );
        verify(getRequestedFor(urlPathEqualTo("/api/4.0/looks/search"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .withQueryParam("folder_id", equalTo("12"))
            .withQueryParam("limit", equalTo("10"))
            .withQueryParam("fields", equalTo("id,title"))
            .withQueryParam("title", equalTo("Monthly Sales")));
    }

    private RunContext context() {
        return this.runContextFactory.of("looker-looks-test");
    }

    private static void stubAuthentication() {
        stubFor(post(urlPathEqualTo(LOGIN_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{\"access_token\":\"" + TOKEN + "\"}")));
        stubFor(delete(urlPathEqualTo(LOGOUT_PATH))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(204)));
    }
}
