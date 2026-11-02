package io.kestra.plugin.looker.schedules;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.kestra.core.http.HttpRequest;
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
    title = "Run a Looker scheduled plan once",
    description = "Executes an existing scheduled plan by ID or executes an ad-hoc scheduled plan configuration immediately."
)
@Plugin(
    examples = {
        @Example(
            title = "Run an existing scheduled plan by ID",
            full = true,
            code = """
                id: looker_run_schedule
                namespace: company.team

                tasks:
                  - id: run_schedule
                    type: io.kestra.plugin.looker.schedules.RunOnce
                    baseUrl: https://company.cloud.looker.com
                    clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
                    clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
                    scheduledPlanId: "105"
                """
        )
    }
)
public class RunOnce extends AbstractLookerTask implements RunnableTask<RunOnce.Output> {
    @Schema(title = "The ID of the scheduled plan to run")
    @PluginProperty(group = "main")
    private Property<String> scheduledPlanId;

    @Schema(title = "Ad-hoc scheduled plan configuration to run once")
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> scheduledPlan;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rPlanId = this.scheduledPlanId != null ? runContext.render(this.scheduledPlanId).as(String.class).orElse(null) : null;
        var rPlan = this.scheduledPlan != null ? runContext.render(this.scheduledPlan).asMap(String.class, Object.class) : null;

        if ((rPlanId == null || rPlanId.isBlank()) && (rPlan == null || rPlan.isEmpty())) {
            throw new IllegalArgumentException("Either 'scheduledPlanId' or 'scheduledPlan' must be provided.");
        }

        try (var client = this.client(runContext)) {
            byte[] response;
            if (rPlanId != null && !rPlanId.isBlank()) {
                var endpoint = "/api/4.0/scheduled_plans/" + LookerApiClient.encodePathSegment(rPlanId) + "/run_once";
                HttpRequest.RequestBody body = rPlan != null && !rPlan.isEmpty() ? HttpRequest.JsonRequestBody.of(rPlan) : null;
                response = client.request("POST", endpoint, body);
            } else {
                var endpoint = "/api/4.0/scheduled_plans/run_once";
                response = client.request("POST", endpoint, HttpRequest.JsonRequestBody.of(rPlan));
            }

            var resultMap = parseResult(response);
            return Output.builder().result(resultMap).build();
        }
    }

    private static Map<String, Object> parseResult(byte[] response) {
        if (response == null || response.length == 0) {
            return Map.of();
        }
        try {
            return JacksonMapper.toMap(new String(response, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Looker scheduled plan response was not valid JSON.", e);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The result of the scheduled plan run")
        private final Map<String, Object> result;
    }
}
