package io.kestra.plugin.looker.looks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
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
    title = "Trigger a flow on new Looker Look rows",
    description = "Periodically executes a saved Look in Looker and triggers executions when new rows are detected using namespace KV watermarking."
)
@Plugin(
    examples = {
        @Example(
            title = "Poll a saved Look every 5 minutes and trigger on new rows",
            full = true,
            code = """
                id: looker_look_trigger
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Found {{ trigger.size }} new rows"

                triggers:
                  - id: watch_look
                    type: io.kestra.plugin.looker.looks.Trigger
                    baseUrl: https://company.cloud.looker.com
                    clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
                    clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
                    lookId: "42"
                    watermarkField: "orders.created_at"
                    interval: PT5M
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<FetchOutput> {
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

    @Builder.Default
    @Schema(title = "The interval between polls")
    @PluginProperty(group = "execution")
    private Duration interval = Duration.ofSeconds(60);

    @NotNull
    @Schema(title = "The ID of the Look to execute")
    @PluginProperty(group = "main")
    private Property<String> lookId;

    @Schema(title = "Field name in the Look result used as watermark for tracking new rows")
    @PluginProperty(group = "processing")
    private Property<String> watermarkField;

    @Schema(title = "Row limit to apply to the Look run")
    @PluginProperty(group = "main")
    private Property<String> limit;

    @Schema(title = "Whether to apply visualization formatting to the result")
    @PluginProperty(group = "processing")
    private Property<Boolean> applyFormatting;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var renderedBaseUrl = runContext.render(this.baseUrl).as(String.class).orElseThrow(() -> new IllegalArgumentException("baseUrl is required"));
        var renderedClientId = runContext.render(this.clientId).as(String.class).orElseThrow(() -> new IllegalArgumentException("clientId is required"));
        var renderedClientSecret = runContext.render(this.clientSecret).as(String.class).orElseThrow(() -> new IllegalArgumentException("clientSecret is required"));
        var renderedLookId = runContext.render(this.lookId).as(String.class).orElseThrow(() -> new IllegalArgumentException("lookId is required"));
        var renderedWatermarkField = this.watermarkField != null ? runContext.render(this.watermarkField).as(String.class).orElse(null) : null;

        Map<String, Object> queryParams = new LinkedHashMap<>();
        if (this.limit != null) {
            runContext.render(this.limit).as(String.class).ifPresent(l -> queryParams.put("limit", l));
        }
        if (this.applyFormatting != null) {
            runContext.render(this.applyFormatting).as(Boolean.class).ifPresent(f -> queryParams.put("apply_formatting", f));
        }

        byte[] response;
        try (var client = new LookerApiClient(runContext, renderedBaseUrl, renderedClientId, renderedClientSecret)) {
            response = client.request("GET", "/api/4.0/looks/" + LookerApiClient.encodePathSegment(renderedLookId) + "/run/json", queryParams, null);
        }

        List<Object> rawRows = JacksonMapper.toList(new String(response, StandardCharsets.UTF_8));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : rawRows) {
            if (row instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typedMap = (Map<String, Object>) map;
                rows.add(typedMap);
            }
        }

        var kv = runContext.namespaceKv(conditionContext.getFlow().getNamespace());
        var stateKey = stateKey(context.getFlowId(), this.getId());

        if (renderedWatermarkField != null && !renderedWatermarkField.isBlank()) {
            var prevWatermark = kv.getValue(stateKey).map(v -> v.value() != null ? String.valueOf(v.value()) : null).orElse(null);

            List<Map<String, Object>> filteredRows = new ArrayList<>();
            Object maxWatermark = null;

            for (Map<String, Object> row : rows) {
                var val = row.get(renderedWatermarkField);
                if (val != null) {
                    if (prevWatermark == null || compareWatermark(val, prevWatermark) > 0) {
                        filteredRows.add(row);
                        if (maxWatermark == null || compareWatermark(val, String.valueOf(maxWatermark)) > 0) {
                            maxWatermark = val;
                        }
                    }
                }
            }

            // Advance the watermark on every successful poll, not only when firing
            var nextWatermark = maxWatermark != null ? String.valueOf(maxWatermark) : prevWatermark;
            if (nextWatermark != null) {
                kv.put(stateKey, new KVValueAndMetadata(new KVMetadata("looker watermark", (Duration) null), nextWatermark));
            }

            if (filteredRows.isEmpty()) {
                return Optional.empty();
            }

            var output = FetchOutput.builder()
                .rows(new ArrayList<>(filteredRows))
                .size((long) filteredRows.size())
                .build();

            return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
        } else {
            var currentSignature = hash(response);
            var prevSignature = kv.getValue(stateKey).map(v -> v.value() != null ? String.valueOf(v.value()) : null).orElse(null);

            // Always record the latest signature, so an empty result is remembered and a later return of old data fires again
            if (!currentSignature.equals(prevSignature)) {
                kv.put(stateKey, new KVValueAndMetadata(new KVMetadata("looker signature", (Duration) null), currentSignature));
            }

            if (currentSignature.equals(prevSignature) || rows.isEmpty()) {
                return Optional.empty();
            }

            var output = FetchOutput.builder()
                .rows(new ArrayList<>(rows))
                .size((long) rows.size())
                .build();

            return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
        }
    }

    // Length-prefixed segments so ("ab", "c") and ("a", "bc") cannot collide
    static String stateKey(String flowId, String triggerId) {
        return "looker_wm_" + flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot calculate the Looker trigger result signature.", e);
        }
    }

    private static int compareWatermark(Object v1, String v2) {
        if (v1 == null) {
            return -1;
        }
        if (v1 instanceof Number n) {
            try {
                double d2 = Double.parseDouble(v2);
                return Double.compare(n.doubleValue(), d2);
            } catch (NumberFormatException ignored) {
            }
        }
        return String.valueOf(v1).compareTo(v2);
    }
}
