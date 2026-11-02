package io.kestra.plugin.looker.queries;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

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
    title = "Run a SQL Runner query in Looker",
    description = "Creates a SQL Runner query and runs it through the Looker API 4.0."
)
@Plugin(
    examples = {
        @Example(
            title = "Run a SQL Runner query",
            full = true,
            code = """
                id: looker_sql_report
                namespace: company.team

                tasks:
                  - id: run_sql
                    type: io.kestra.plugin.looker.queries.SqlRun
                    baseUrl: https://company.cloud.looker.com
                    clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
                    clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
                    connectionName: warehouse
                    sql: SELECT COUNT(*) FROM orders
                    fetchType: FETCH
                """
        )
    }
)
public class SqlRun extends AbstractLookerTask implements RunnableTask<FetchOutput> {
    @NotNull
    @Schema(title = "The Looker connection used to execute the SQL")
    @PluginProperty(group = "main")
    private Property<String> connectionName;

    @NotNull
    @Schema(title = "The SQL statement to execute")
    @PluginProperty(group = "main")
    private Property<String> sql;

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
        var rConnectionName = required(runContext.render(this.connectionName).as(String.class).orElse(null), "connectionName");
        var rSql = required(runContext.render(this.sql).as(String.class).orElse(null), "sql");
        var format = runContext.render(this.resultFormat).as(ResultFormat.class).orElse(ResultFormat.JSON);
        var mode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        Map<String, Object> sqlQuery = new LinkedHashMap<>();
        sqlQuery.put("connection_name", rConnectionName);
        sqlQuery.put("sql", rSql);

        try (var client = this.client(runContext)) {
            byte[] createResponse = client.request(
                "POST",
                "/api/4.0/sql_queries",
                HttpRequest.JsonRequestBody.of(sqlQuery)
            );
            var slug = slug(createResponse);
            var endpoint = "/api/4.0/sql_queries/" + LookerApiClient.encodePathSegment(slug) + "/run/" +
                format.name().toLowerCase(Locale.ROOT);
            byte[] result = client.request("POST", endpoint, null);
            return LookerQueryResults.output(runContext, mode, format, result, endpoint);
        }
    }

    private static String slug(byte[] response) {
        JsonNode json;
        try {
            json = JacksonMapper.ofJson().readTree(response);
        } catch (IOException e) {
            throw new IllegalStateException("Looker SQL query creation response was not valid JSON.", e);
        }

        JsonNode value = json == null ? null : json.get("slug");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Looker SQL query creation response did not contain a valid slug.");
        }
        return value.asText();
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Looker SQL query property '%s' must not be empty.".formatted(property));
        }
        return value;
    }
}
