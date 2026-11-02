package io.kestra.plugin.looker.projects;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.context.TestRunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
@KestraTest
class LookerProjectTasksTest {
    private static final String LOGIN_PATH = "/api/4.0/login";
    private static final String LOGOUT_PATH = "/api/4.0/logout";
    private static final String TOKEN = "project-test-token";

    @Inject
    private TestRunContextFactory runContextFactory;

    @Test
    void shouldDeployProjectToProduction(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(post(urlPathEqualTo("/api/4.0/projects/analytics_project/deploy_to_production"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"success\",\"deployment_id\":\"dep-1\"}")));

        Deploy task = Deploy.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .projectId(Property.ofValue("analytics_project"))
            .build();

        Deploy.Output output = task.run(context());

        assertThat(output.getResult()).contains("deployment_id");
        verify(postRequestedFor(urlPathEqualTo("/api/4.0/projects/analytics_project/deploy_to_production"))
            .withHeader("Authorization", equalTo("token " + TOKEN)));
    }

    @Test
    void shouldDeployBranchToProduction(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(post(urlPathEqualTo("/api/4.0/projects/analytics_project/deploy_ref_to_production"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"success\",\"branch\":\"release-1\"}")));

        Deploy task = Deploy.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .projectId(Property.ofValue("analytics_project"))
            .branch(Property.ofValue("release-1"))
            .build();

        Deploy.Output output = task.run(context());

        assertThat(output.getResult()).contains("release-1");
        verify(postRequestedFor(urlEqualTo("/api/4.0/projects/analytics_project/deploy_ref_to_production?branch=release-1"))
            .withHeader("Authorization", equalTo("token " + TOKEN)));
    }

    @Test
    void shouldEncodeProjectIdPathSegment(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(post(urlPathEqualTo("/api/4.0/projects/..%2Fx/deploy_to_production"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));
        stubFor(post(urlPathEqualTo("/api/4.0/projects/p%3Fx%3D/deploy_to_production"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        for (String id : new String[]{"../x", "p?x="}) {
            Deploy.builder()
                .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
                .clientId(Property.ofValue("client-id"))
                .clientSecret(Property.ofValue("client-secret"))
                .projectId(Property.ofValue(id))
                .build()
                .run(context());
        }

        verify(postRequestedFor(urlEqualTo("/api/4.0/projects/..%2Fx/deploy_to_production")));
        verify(postRequestedFor(urlEqualTo("/api/4.0/projects/p%3Fx%3D/deploy_to_production")));
    }

    private RunContext context() {
        return this.runContextFactory.of("looker-project-test");
    }

    private static void stubAuthentication() {
        stubFor(post(urlPathEqualTo(LOGIN_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{\"access_token\":\"" + TOKEN + "\"}")));
        stubFor(delete(urlPathEqualTo(LOGOUT_PATH))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(204)));
    }
}

