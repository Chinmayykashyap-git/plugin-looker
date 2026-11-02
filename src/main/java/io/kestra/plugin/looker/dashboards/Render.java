package io.kestra.plugin.looker.dashboards;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.WorkerJobLifecycle;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.looker.AbstractLookerTask;
import io.kestra.plugin.looker.LookerApiClient;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Render a Looker dashboard to PDF or image",
    description = "Enqueues a dashboard render task in Looker, polls until rendering finishes, and stores the rendered file in Kestra storage."
)
@Plugin(
    examples = {
        @Example(
            title = "Render a dashboard to PDF",
            full = true,
            code = """
                id: looker_render_dashboard
                namespace: company.team

                tasks:
                  - id: render_pdf
                    type: io.kestra.plugin.looker.dashboards.Render
                    baseUrl: https://company.cloud.looker.com
                    clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
                    clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
                    dashboardId: "12"
                    format: PDF
                    pdfPaperSize: "a4"
                    pdfLandscape: true
                """
        )
    }
)
public class Render extends AbstractLookerTask implements RunnableTask<Render.Output>, WorkerJobLifecycle {
    @Schema(title = "The ID of the dashboard to render")
    @PluginProperty(group = "main")
    private Property<String> dashboardId;

    @Schema(title = "The ID of the saved Look to render instead of a dashboard")
    @PluginProperty(group = "main")
    private Property<String> lookId;

    @Schema(title = "Output file format")
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<RenderFormat> format = Property.ofValue(RenderFormat.PDF);

    @Schema(title = "Width of the rendered canvas in pixels")
    @PluginProperty(group = "processing")
    private Property<Integer> width;

    @Schema(title = "Height of the rendered canvas in pixels")
    @PluginProperty(group = "processing")
    private Property<Integer> height;

    @Schema(title = "Paper size for PDF format (e.g. letter, legal, a4)")
    @PluginProperty(group = "processing")
    private Property<String> pdfPaperSize;

    @Schema(title = "Whether to render PDF in landscape orientation")
    @PluginProperty(group = "processing")
    private Property<Boolean> pdfLandscape;

    @Schema(title = "Dashboard filter expression to apply")
    @PluginProperty(group = "processing")
    private Property<String> dashboardFilters;

    @Schema(title = "Polling interval while waiting for render completion")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Duration> pollInterval = Property.ofValue(Duration.ofSeconds(1));

    @Schema(title = "Maximum duration to wait for render completion")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Duration> timeout = Property.ofValue(Duration.ofMinutes(5));

    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final transient AtomicBoolean killed = new AtomicBoolean(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rDashboardId = this.dashboardId != null ? runContext.render(this.dashboardId).as(String.class).orElse(null) : null;
        var rLookId = this.lookId != null ? runContext.render(this.lookId).as(String.class).orElse(null) : null;
        if ((rDashboardId == null || rDashboardId.isBlank()) == (rLookId == null || rLookId.isBlank())) {
            throw new IllegalArgumentException("Exactly one of 'dashboardId' or 'lookId' must be provided.");
        }
        RenderFormat renderFormat = runContext.render(this.format).as(RenderFormat.class).orElse(RenderFormat.PDF);
        Duration interval = runContext.render(this.pollInterval).as(Duration.class).orElse(Duration.ofSeconds(1));
        Duration maxTimeout = runContext.render(this.timeout).as(Duration.class).orElse(Duration.ofMinutes(5));

        Map<String, Object> body = new LinkedHashMap<>();
        if (this.width != null) {
            runContext.render(this.width).as(Integer.class).ifPresent(w -> body.put("width", w));
        }
        if (this.height != null) {
            runContext.render(this.height).as(Integer.class).ifPresent(h -> body.put("height", h));
        }
        if (this.pdfPaperSize != null) {
            runContext.render(this.pdfPaperSize).as(String.class).ifPresent(s -> body.put("pdf_paper_size", s));
        }
        if (this.pdfLandscape != null) {
            runContext.render(this.pdfLandscape).as(Boolean.class).ifPresent(l -> body.put("pdf_landscape", l));
        }
        if (this.dashboardFilters != null) {
            runContext.render(this.dashboardFilters).as(String.class).ifPresent(f -> body.put("dashboard_filters", f));
        }

        var formatStr = renderFormat.name().toLowerCase(Locale.ROOT);
        var useDashboard = rDashboardId != null && !rDashboardId.isBlank();
        var renderType = useDashboard ? "dashboards" : "looks";
        var renderId = useDashboard ? rDashboardId : rLookId;
        var createEndpoint = "/api/4.0/render_tasks/" + renderType + "/" + LookerApiClient.encodePathSegment(renderId) + "/" + formatStr;

        try (var client = this.client(runContext)) {
            byte[] createResponse = client.request("POST", createEndpoint, HttpRequest.JsonRequestBody.of(body));
            var taskId = parseTaskId(createResponse);

            Instant deadline = Instant.now().plus(maxTimeout);
            var taskStatusEndpoint = "/api/4.0/render_tasks/" + LookerApiClient.encodePathSegment(taskId);

            while (!this.killed.get()) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Looker dashboard render task '%s' timed out after %s.".formatted(taskId, maxTimeout));
                }

                byte[] statusResponse = client.request("GET", taskStatusEndpoint, null);
                var status = parseTaskStatus(statusResponse);

                if ("success".equalsIgnoreCase(status)) {
                    break;
                }
                if ("failure".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status)) {
                    throw new IllegalStateException("Looker dashboard render task '%s' ended with status '%s'.".formatted(taskId, status));
                }

                this.waitForNextPoll(interval, deadline);
            }

            if (this.killed.get()) {
                throw new IllegalStateException("Looker dashboard render task was killed.");
            }

            byte[] resultBytes = client.request("GET", "/api/4.0/render_tasks/" + LookerApiClient.encodePathSegment(taskId) + "/results", null);
            var extension = switch (renderFormat) {
                case PDF -> ".pdf";
                case PNG -> ".png";
                case JPG -> ".jpg";
            };

            Path tempFile = runContext.workingDir().createTempFile(resultBytes, extension);
            URI uri = runContext.storage().putFile(tempFile.toFile());

            return Output.builder()
                .uri(uri)
                .taskId(taskId)
                .status("success")
                .build();
        }
    }

    @Override
    public void kill() {
        this.killed.set(true);
    }

    private void waitForNextPoll(Duration interval, Instant deadline) {
        long intervalMillis = Math.max(100, interval.toMillis());
        long deadlineMillis = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.min(intervalMillis, deadlineMillis));
        long nextPoll = System.nanoTime() + remainingNanos;

        try {
            while (!this.killed.get() && (remainingNanos = nextPoll - System.nanoTime()) > 0) {
                TimeUnit.NANOSECONDS.sleep(Math.min(
                    remainingNanos,
                    TimeUnit.MILLISECONDS.toNanos(100)
                ));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Looker render task polling interrupted.", e);
        }
    }

    private static String parseTaskId(byte[] response) {
        JsonNode json;
        try {
            json = JacksonMapper.ofJson().readTree(response);
        } catch (IOException e) {
            throw new IllegalStateException("Looker render task creation response was not valid JSON.", e);
        }
        JsonNode id = json == null ? null : json.get("id");
        if (id == null || !id.isTextual() || id.asText().isBlank()) {
            throw new IllegalStateException("Looker render task creation response did not contain a valid task id.");
        }
        return id.asText();
    }

    private static String parseTaskStatus(byte[] response) {
        JsonNode json;
        try {
            json = JacksonMapper.ofJson().readTree(response);
        } catch (IOException e) {
            throw new IllegalStateException("Looker render task status response was not valid JSON.", e);
        }
        JsonNode status = json == null ? null : json.get("status");
        if (status == null || !status.isTextual()) {
            return "unknown";
        }
        return status.asText();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI of the rendered file in Kestra internal storage")
        private final URI uri;

        @Schema(title = "The render task ID from Looker")
        private final String taskId;

        @Schema(title = "The final status of the render task")
        private final String status;
    }
}
