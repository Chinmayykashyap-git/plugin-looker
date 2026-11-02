package io.kestra.plugin.looker;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

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
public abstract class AbstractLookerTask extends Task {
    @NotNull
    @Schema(title = "The base URL of the Looker instance")
    @PluginProperty(group = "connection")
    private Property<String> baseUrl;

    @NotNull
    @Schema(title = "The Looker API client ID")
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> clientId;

    @NotNull
    @Schema(title = "The Looker API client secret")
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> clientSecret;

    protected LookerApiClient client(RunContext runContext) throws Exception {
        String renderedBaseUrl = runContext.render(this.baseUrl).as(String.class).orElseThrow(() -> new IllegalArgumentException("baseUrl is required"));
        String renderedClientId = runContext.render(this.clientId).as(String.class).orElseThrow(() -> new IllegalArgumentException("clientId is required"));
        String renderedClientSecret = runContext.render(this.clientSecret).as(String.class).orElseThrow(() -> new IllegalArgumentException("clientSecret is required"));

        return new LookerApiClient(runContext, renderedBaseUrl, renderedClientId, renderedClientSecret);
    }
}
