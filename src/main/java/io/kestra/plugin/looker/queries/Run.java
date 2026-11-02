package io.kestra.plugin.looker.queries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.looker.AbstractLookerTask;
import io.kestra.plugin.looker.LookerQueryResults;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.Builder;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run an inline Looker query",
    description = "Runs a Looker API 4.0 query and returns rows or stores the original result."
)
@Plugin(
    examples = {
        @Example(
            title = "Run an inline query and store the rows",
            full = true,
            code = """
                id: looker_orders_report
                namespace: company.team

                tasks:
                  - id: run_query
                    type: io.kestra.plugin.looker.queries.Run
                    baseUrl: https://company.cloud.looker.com
                    clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
                    clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
                    model: ecommerce
                    view: orders
                    fields:
                      - orders.created_date
                      - orders.total_revenue
                    filters:
                      orders.created_date: 7 days
                    limit: "500"
                    fetchType: STORE
                """
        )
    }
)
public class Run extends AbstractLookerTask implements RunnableTask<FetchOutput> {
    @NotNull
    @Schema(title = "The Looker model to query")
    @PluginProperty(group = "main")
    private Property<String> model;

    @NotNull
    @Schema(title = "The Looker view to query")
    @PluginProperty(group = "main")
    private Property<String> view;

    @NotNull
    @Schema(title = "The fields to include in the result")
    @PluginProperty(group = "main")
    private Property<List<String>> fields;

    @Schema(title = "Filter expressions keyed by Looker field name")
    @PluginProperty(group = "main")
    private Property<Map<String, String>> filters;

    @Schema(title = "Sort expressions to apply to the query")
    @PluginProperty(group = "main")
    private Property<List<String>> sorts;

    @Schema(title = "The maximum number of rows to return")
    @PluginProperty(group = "main")
    private Property<String> limit;

    @Schema(title = "The format returned by Looker")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<ResultFormat> resultFormat = Property.ofValue(ResultFormat.JSON);

    @Schema(title = "How to expose or store the query results")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var rModel = required(runContext.render(this.model).as(String.class).orElse(null), "model");
        var rView = required(runContext.render(this.view).as(String.class).orElse(null), "view");
        var rFields = runContext.render(this.fields).asList(String.class);
        if (rFields.isEmpty()) {
            throw new IllegalArgumentException("At least one Looker query field must be specified.");
        }

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("model", rModel);
        query.put("view", rView);
        query.put("fields", rFields);

        var rFilters = runContext.render(this.filters).asMap(String.class, String.class);
        if (!rFilters.isEmpty()) {
            query.put("filters", rFilters);
        }

        var rSorts = runContext.render(this.sorts).asList(String.class);
        if (!rSorts.isEmpty()) {
            query.put("sorts", rSorts);
        }

        var rLimit = runContext.render(this.limit).as(String.class).orElse(null);
        if (rLimit != null) {
            query.put("limit", rLimit);
        }

        var format = runContext.render(this.resultFormat).as(ResultFormat.class).orElse(ResultFormat.JSON);
        var mode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var endpoint = "/api/4.0/queries/run/" + format.name().toLowerCase(Locale.ROOT);

        try (var client = this.client(runContext)) {
            byte[] response = client.request(
                "POST",
                endpoint,
                HttpRequest.JsonRequestBody.of(query)
            );
            return LookerQueryResults.output(runContext, mode, format, response, endpoint);
        }
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Looker query property '%s' must not be empty.".formatted(property));
        }
        return value;
    }
}
