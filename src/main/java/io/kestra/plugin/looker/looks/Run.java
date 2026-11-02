package io.kestra.plugin.looker.looks;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.looker.AbstractLookerTask;
import io.kestra.plugin.looker.LookerApiClient;
import io.kestra.plugin.looker.LookerQueryResults;
import io.kestra.plugin.looker.queries.ResultFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "Run a saved Look in Looker",
    description = "Executes a saved Look by ID and returns or stores the results. Looker's run-a-Look endpoint has no filter parameter, so when `filters` is set the task fetches the Look, merges the overrides into its query `filters` and runs the merged query inline. Overrides replace the entries of the Look's `filters` map for the same field; other filters of the Look are kept. A `filter_expression` saved on the Look (used by Looker for \"or\" and custom filters) is kept as is and still applies. The Look's `filter_config` is cleared, as Looker requires for a modified query."
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
    // Read-only (or client-scoped) fields of the Looker Query schema, meaningless when posted back
    private static final Set<String> READ_ONLY_QUERY_FIELDS = Set.of("id", "slug", "client_id", "share_url", "expanded_share_url", "url", "has_table_calculations", "can");

    @NotNull
    @Schema(title = "The ID of the Look to run")
    @PluginProperty(group = "main")
    private Property<String> lookId;

    @Schema(title = "The format returned by Looker")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<ResultFormat> resultFormat = Property.ofValue(ResultFormat.JSON);

    @Schema(title = "How to expose or store the query results")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Row limit to apply to the Look run")
    @PluginProperty(group = "main")
    private Property<String> limit;

    @Schema(title = "Whether to apply visualization formatting to the result")
    @PluginProperty(group = "processing")
    private Property<Boolean> applyFormatting;

    @Schema(
        title = "Filter overrides keyed by Looker field name",
        description = "Overrides replace the entries of the Look's `filters` map for the same field. A `filter_expression` saved on the Look is not replaced and is still ANDed with the overrides. When empty, the saved Look is run as is."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, String>> filters;

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var rLookId = runContext.render(this.lookId).as(String.class).orElseThrow(() -> new IllegalArgumentException("lookId is required"));
        var format = runContext.render(this.resultFormat).as(ResultFormat.class).orElse(ResultFormat.JSON);
        var mode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        Map<String, Object> queryParams = new LinkedHashMap<>();
        if (this.limit != null) {
            runContext.render(this.limit).as(String.class).ifPresent(l -> queryParams.put("limit", l));
        }
        if (this.applyFormatting != null) {
            runContext.render(this.applyFormatting).as(Boolean.class).ifPresent(f -> queryParams.put("apply_formatting", f));
        }
        var rFilters = runContext.render(this.filters).asMap(String.class, String.class);
        var resultSuffix = format.name().toLowerCase(Locale.ROOT);
        var lookPath = "/api/4.0/looks/" + LookerApiClient.encodePathSegment(rLookId);

        try (var client = this.client(runContext)) {
            String endpoint;
            byte[] response;
            if (rFilters.isEmpty()) {
                endpoint = lookPath + "/run/" + resultSuffix;
                response = client.request("GET", endpoint, queryParams, null);
            } else {
                byte[] look = client.request("GET", lookPath, null);
                endpoint = "/api/4.0/queries/run/" + resultSuffix;
                response = client.request("POST", endpoint, queryParams, jsonBodyKeepingNulls(mergedQuery(look, rFilters, rLookId)));
            }
            return LookerQueryResults.output(runContext, mode, format, response, endpoint);
        }
    }

    // The shared Kestra JSON mapper drops null values, but filter_config must be sent as an explicit null
    private static HttpRequest.RequestBody jsonBodyKeepingNulls(Map<String, Object> body) throws Exception {
        return HttpRequest.StringRequestBody.builder()
            .contentType("application/json")
            .charset(StandardCharsets.UTF_8)
            .content(new ObjectMapper().writeValueAsString(body))
            .build();
    }

    private static Map<String, Object> mergedQuery(byte[] look, Map<String, String> overrides, String lookId) throws Exception {
        JsonNode query = JacksonMapper.ofJson().readTree(look).get("query");
        if (query == null || !query.isObject()) {
            throw new IllegalStateException("Looker Look '%s' has no query, filter overrides cannot be applied.".formatted(lookId));
        }

        Map<String, Object> body = new LinkedHashMap<>(JacksonMapper.toMap(query));
        body.keySet().removeAll(READ_ONLY_QUERY_FIELDS);
        // Looker requires filter_config to be null when a query is modified, any other value can cause unexpected filtering
        body.put("filter_config", null);

        Map<String, Object> mergedFilters = new LinkedHashMap<>();
        if (body.get("filters") instanceof Map<?, ?> existing) {
            existing.forEach((k, v) -> mergedFilters.put(String.valueOf(k), v));
        }
        mergedFilters.putAll(overrides);
        body.put("filters", mergedFilters);
        return body;
    }
}
