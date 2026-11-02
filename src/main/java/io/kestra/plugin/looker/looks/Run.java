package io.kestra.plugin.looker.looks;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.looker.AbstractLookerTask;
import io.kestra.plugin.looker.LookerApiClient;
import io.kestra.plugin.looker.LookerQueryResults;
import io.kestra.plugin.looker.queries.ResultFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "Run a saved Look in Looker",
    description = "Executes a saved Look by ID and returns or stores the results."
)
@Plugin(
    examples = {
        @Example(
            title = "Run a saved Look and fetch the rows",
            full = true,
            code = """
                id: looker_run_look
                namespace: company.team

                tasks:
                  - id: run_look
                    type: io.kestra.plugin.looker.looks.Run
                    baseUrl: https://company.cloud.looker.com
                    clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
                    clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
                    lookId: "42"
                    filters:
                      orders.status: shipped
                    fetchType: FETCH
                """
        )
    }
)
public class Run extends AbstractLookerTask implements RunnableTask<FetchOutput> {
    @NotNull
    @Schema(title = "The ID of the Look to run")
    @PluginProperty(group = "main")
    private Property<String> lookId;

    @Schema(title = "The format returned by Looker")
    @PluginProperty(group = "processing")
    @lombok.Builder.Default
    private Property<ResultFormat> resultFormat = Property.ofValue(ResultFormat.JSON);

    @Schema(title = "How to expose or store the query results")
    @PluginProperty(group = "processing")
    @lombok.Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Row limit to apply to the Look run")
    @PluginProperty(group = "main")
    private Property<String> limit;

    @Schema(title = "Whether to apply visualization formatting to the result")
    @PluginProperty(group = "processing")
    private Property<Boolean> applyFormatting;

    @Schema(title = "Filter overrides keyed by Looker field name")
    @PluginProperty(group = "main")
    private Property<Map<String, String>> filters;

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        String renderedLookId = runContext.render(this.lookId).as(String.class).orElseThrow(() -> new IllegalArgumentException("lookId is required"));
        ResultFormat format = runContext.render(this.resultFormat).as(ResultFormat.class).orElse(ResultFormat.JSON);
        FetchType mode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        Map<String, Object> queryParams = new LinkedHashMap<>();
        if (this.limit != null) {
            runContext.render(this.limit).as(String.class).ifPresent(l -> queryParams.put("limit", l));
        }
        if (this.applyFormatting != null) {
            runContext.render(this.applyFormatting).as(Boolean.class).ifPresent(f -> queryParams.put("apply_formatting", f));
        }
        Map<String, String> renderedFilters = runContext.render(this.filters).asMap(String.class, String.class);
        if (!renderedFilters.isEmpty()) {
            StringJoiner filter = new StringJoiner("&");
            renderedFilters.forEach((field, value) -> filter.add(
                URLEncoder.encode(field, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
            ));
            queryParams.put("filter", filter.toString());
        }

        String endpoint = "/api/4.0/looks/" + LookerApiClient.encodePathSegment(renderedLookId) + "/run/" + format.name().toLowerCase(Locale.ROOT);

        try (var client = this.client(runContext)) {
            byte[] response = client.request("GET", endpoint, queryParams, null);
            return LookerQueryResults.output(runContext, mode, format, response, endpoint);
        }
    }
}
