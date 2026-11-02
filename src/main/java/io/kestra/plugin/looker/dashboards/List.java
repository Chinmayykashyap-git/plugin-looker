package io.kestra.plugin.looker.dashboards;

import java.util.LinkedHashMap;
import java.util.Map;

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
import io.kestra.plugin.looker.queries.ResultFormat;

import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "List dashboards in Looker",
    description = "Retrieves a list of dashboards from the Looker API."
)
@Plugin(
    examples = {
        @Example(
            title = "List all dashboards in a folder",
            full = true,
            code = """
                id: looker_list_dashboards
                namespace: company.team

                tasks:
                  - id: list_dashboards
                    type: io.kestra.plugin.looker.dashboards.List
                    baseUrl: https://company.cloud.looker.com
                    clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
                    clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
                    folderId: "5"
                    title: "Executive Overview"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractLookerTask implements RunnableTask<FetchOutput> {
    @Schema(title = "Filter dashboards by folder/space ID")
    @PluginProperty(group = "main")
    private Property<String> folderId;

    @Schema(title = "Fields to include in each dashboard record")
    @PluginProperty(group = "main")
    private Property<java.util.List<String>> fields;

    @Schema(title = "Max number of dashboards to return")
    @PluginProperty(group = "main")
    private Property<String> limit;

    @Schema(
        title = "Filter dashboards by title",
        description = """
            The title must match the whole title, case-insensitively. Use `%` as a wildcard, for example `%Sales%` to find "Sales Overview"; `Sales` alone does not match it."""
    )
    @PluginProperty(group = "main")
    private Property<String> title;

    @Schema(title = "How to expose or store the results")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var mode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        Map<String, Object> queryParams = new LinkedHashMap<>();
        if (this.folderId != null) {
            runContext.render(this.folderId).as(String.class).ifPresent(f -> {
                queryParams.put("folder_id", f);
            });
        }
        if (this.fields != null) {
            var rFields = runContext.render(this.fields).asList(String.class);
            if (!rFields.isEmpty()) {
                queryParams.put("fields", String.join(",", rFields));
            }
        }
        if (this.limit != null) {
            runContext.render(this.limit).as(String.class).ifPresent(l -> queryParams.put("limit", l));
        }
        if (this.title != null) {
            runContext.render(this.title).as(String.class).ifPresent(t -> queryParams.put("title", t));
        }

        // The plain list endpoint only accepts `fields`; the search endpoint honors title, folder_id and limit
        var endpoint = "/api/4.0/dashboards/search";

        try (var client = this.client(runContext)) {
            byte[] response = client.request("GET", endpoint, queryParams, null);
            return LookerQueryResults.output(runContext, mode, ResultFormat.JSON, response, endpoint);
        }
    }
}
