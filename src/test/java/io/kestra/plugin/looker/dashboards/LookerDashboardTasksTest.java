package io.kestra.plugin.looker.dashboards;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.VerificationException;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.context.TestRunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
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

@WireMockTest
@KestraTest
class LookerDashboardTasksTest {
    private static final String LOGIN_PATH = "/api/4.0/login";
    private static final String LOGOUT_PATH = "/api/4.0/logout";
    private static final String TOKEN = "dashboard-test-token";

    @Inject
    private TestRunContextFactory runContextFactory;

    @Test
    void shouldListDashboards(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(get(urlPathEqualTo("/api/4.0/dashboards/search"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("""
                [{"id":"10","title":"Executive Overview"},{"id":"11","title":"Marketing KPI"}]
                """)));

        List task = List.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .folderId(Property.ofValue("5"))
            .fields(Property.ofValue(java.util.List.of("id", "title")))
            .limit(Property.ofValue("20"))
            .title(Property.ofValue("Executive Overview"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        FetchOutput output = task.run(context());

        assertThat(output.getSize()).isEqualTo(2L);
        assertThat(output.getRows()).containsExactly(
            Map.of("id", "10", "title", "Executive Overview"),
            Map.of("id", "11", "title", "Marketing KPI")
        );
        verify(getRequestedFor(urlPathEqualTo("/api/4.0/dashboards/search"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .withQueryParam("folder_id", equalTo("5"))
            .withQueryParam("limit", equalTo("20"))
            .withQueryParam("fields", equalTo("id,title"))
            .withQueryParam("title", equalTo("Executive Overview")));
    }

    @Test
    void shouldRenderDashboardToPdfAndStoreFile(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(post(urlPathEqualTo("/api/4.0/render_tasks/dashboards/10/pdf"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"render-task-123\",\"status\":\"enqueued\"}")));

        stubFor(get(urlPathEqualTo("/api/4.0/render_tasks/render-task-123"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"render-task-123\",\"status\":\"success\"}")));

        byte[] fakePdfBytes = "%PDF-1.4 test dashboard pdf content".getBytes(StandardCharsets.UTF_8);
        stubFor(get(urlPathEqualTo("/api/4.0/render_tasks/render-task-123/results"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody(fakePdfBytes)));

        Render task = Render.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .dashboardId(Property.ofValue("10"))
            .format(Property.ofValue(RenderFormat.PDF))
            .width(Property.ofValue(1280))
            .height(Property.ofValue(720))
            .pdfPaperSize(Property.ofValue("a4"))
            .pdfLandscape(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        RunContext runContext = context();
        Render.Output output = task.run(runContext);

        assertThat(output.getTaskId()).isEqualTo("render-task-123");
        assertThat(output.getStatus()).isEqualTo("success");
        assertThat(output.getUri()).isNotNull();

        try (InputStream is = runContext.storage().getFile(output.getUri())) {
            assertThat(is.readAllBytes()).isEqualTo(fakePdfBytes);
        }
    }

    @Test
    void shouldRenderLookWhenDashboardIdIsBlank(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(post(urlPathEqualTo("/api/4.0/render_tasks/looks/7/pdf"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"render-task-7\",\"status\":\"enqueued\"}")));
        stubFor(get(urlPathEqualTo("/api/4.0/render_tasks/render-task-7"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"render-task-7\",\"status\":\"success\"}")));
        stubFor(get(urlPathEqualTo("/api/4.0/render_tasks/render-task-7/results"))
            .willReturn(aResponse().withStatus(200).withBody("%PDF-1.4 look")));

        Render task = Render.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .dashboardId(Property.ofValue(""))
            .lookId(Property.ofValue("7"))
            .format(Property.ofValue(RenderFormat.PDF))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        Render.Output output = task.run(context());

        assertThat(output.getTaskId()).isEqualTo("render-task-7");
        verify(postRequestedFor(urlPathEqualTo("/api/4.0/render_tasks/looks/7/pdf")));
    }

    @Test
    void shouldFailWhenRenderTaskErrors(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(post(urlPathEqualTo("/api/4.0/render_tasks/dashboards/10/png"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"render-task-err\",\"status\":\"enqueued\"}")));

        stubFor(get(urlPathEqualTo("/api/4.0/render_tasks/render-task-err"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"render-task-err\",\"status\":\"failure\"}")));

        Render task = Render.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .dashboardId(Property.ofValue("10"))
            .format(Property.ofValue(RenderFormat.PNG))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        assertThatThrownBy(() -> task.run(context()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("failure");
    }

    @Test
    void shouldRenderLookToPngAndStoreFile(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(post(urlPathEqualTo("/api/4.0/render_tasks/looks/42/png"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"look-render-123\",\"status\":\"enqueued\"}")));
        stubFor(get(urlPathEqualTo("/api/4.0/render_tasks/look-render-123"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"look-render-123\",\"status\":\"success\"}")));

        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        stubFor(get(urlPathEqualTo("/api/4.0/render_tasks/look-render-123/results"))
            .willReturn(aResponse().withStatus(200).withBody(pngBytes)));

        Render task = Render.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .lookId(Property.ofValue("42"))
            .format(Property.ofValue(RenderFormat.PNG))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        RunContext runContext = context();
        Render.Output output = task.run(runContext);

        assertThat(output.getUri()).isNotNull();
        verify(postRequestedFor(urlPathEqualTo("/api/4.0/render_tasks/looks/42/png"))
            .withHeader("Authorization", equalTo("token " + TOKEN)));
        try (InputStream stored = runContext.storage().getFile(output.getUri())) {
            assertThat(stored.readAllBytes()).isEqualTo(pngBytes);
        }
    }

    @Test
    void shouldStopPollingWhenRenderIsKilled(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(post(urlPathEqualTo("/api/4.0/render_tasks/dashboards/10/pdf"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"render-task-kill\",\"status\":\"enqueued\"}")));
        stubFor(get(urlPathEqualTo("/api/4.0/render_tasks/render-task-kill"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"render-task-kill\",\"status\":\"running\"}")));

        Render task = Render.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .dashboardId(Property.ofValue("10"))
            .pollInterval(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> task.run(context()));
            awaitStatusRequest();
            task.kill();

            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
            verify(1, getRequestedFor(urlPathEqualTo("/api/4.0/render_tasks/render-task-kill")));
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitStatusRequest() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                verify(1, getRequestedFor(urlPathEqualTo("/api/4.0/render_tasks/render-task-kill")));
                return;
            } catch (VerificationException e) {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Looker render status endpoint was not polled.");
    }

    private RunContext context() {
        return this.runContextFactory.of("looker-dashboard-test");
    }

    private static void stubAuthentication() {
        stubFor(post(urlPathEqualTo(LOGIN_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{\"access_token\":\"" + TOKEN + "\"}")));
        stubFor(delete(urlPathEqualTo(LOGOUT_PATH))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(204)));
    }
}
