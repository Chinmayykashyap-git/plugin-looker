package io.kestra.plugin.looker.projects;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.looker.AbstractLookerTask;
import io.kestra.plugin.looker.LookerApiClient;

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
    title = "Deploy a Looker project to production",
    description = "Deploys a LookML project or specific Git branch/ref to production using the Looker API."
)
@Plugin(
    examples = {
        @Example(
            title = "Deploy the default production branch of a LookML project",
            full = true,
            code = """
                id: looker_deploy_project
                namespace: company.team

                tasks:
                  - id: deploy
                    type: io.kestra.plugin.looker.projects.Deploy
                    baseUrl: https://company.cloud.looker.com
                    clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
                    clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
                    projectId: "analytics_project"
                """
        ),
        @Example(
            title = "Deploy a specific branch to production",
            full = true,
            code = """
                id: looker_deploy_branch
                namespace: company.team

                tasks:
                  - id: deploy_branch
                    type: io.kestra.plugin.looker.projects.Deploy
                    baseUrl: https://company.cloud.looker.com
                    clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
                    clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
                    projectId: "analytics_project"
                    branch: "release-v2"
                """
        )
    }
)
public class Deploy extends AbstractLookerTask implements RunnableTask<Deploy.Output> {
    @NotNull
    @Schema(title = "The LookML project ID to deploy")
    @PluginProperty(group = "main")
    private Property<String> projectId;

    @Schema(title = "Git branch to deploy to production")
    @PluginProperty(group = "main")
    private Property<String> branch;

    @Schema(title = "Git ref (commit SHA or tag) to deploy to production")
    @PluginProperty(group = "main")
    private Property<String> ref;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String renderedProjectId = runContext.render(this.projectId).as(String.class).orElseThrow(() -> new IllegalArgumentException("projectId is required"));
        String renderedBranch = this.branch != null ? runContext.render(this.branch).as(String.class).orElse(null) : null;
        String renderedRef = this.ref != null ? runContext.render(this.ref).as(String.class).orElse(null) : null;

        Map<String, Object> queryParams = new LinkedHashMap<>();
        if (renderedBranch != null && !renderedBranch.isBlank()) {
            queryParams.put("branch", renderedBranch);
        }
        if (renderedRef != null && !renderedRef.isBlank()) {
            queryParams.put("ref", renderedRef);
        }

        String endpoint;
        if (!queryParams.isEmpty()) {
            endpoint = "/api/4.0/projects/" + LookerApiClient.encodePathSegment(renderedProjectId) + "/deploy_ref_to_production";
        } else {
            endpoint = "/api/4.0/projects/" + LookerApiClient.encodePathSegment(renderedProjectId) + "/deploy_to_production";
        }

        try (var client = this.client(runContext)) {
            byte[] response = client.request("POST", endpoint, queryParams, null);
            String result = response != null && response.length > 0 ? new String(response, StandardCharsets.UTF_8) : "deployed";
            return Output.builder().result(result).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The deployment result returned by Looker")
        private final String result;
    }
}

