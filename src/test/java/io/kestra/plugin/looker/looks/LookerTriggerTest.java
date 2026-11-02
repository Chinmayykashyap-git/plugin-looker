package io.kestra.plugin.looker.looks;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.context.TestRunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextInitializer;
import io.kestra.core.utils.IdUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
@KestraTest
class LookerTriggerTest {
    private static final String LOGIN_PATH = "/api/4.0/login";
    private static final String LOGOUT_PATH = "/api/4.0/logout";
    private static final String TOKEN = "trigger-test-token";

    @Inject
    private TestRunContextFactory runContextFactory;

    @Inject
    private RunContextInitializer runContextInitializer;

    @Test
    void shouldPollAndFireOnlyOnNewRowsUsingWatermark(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();

        String triggerId = IdUtils.create();
        String flowId = IdUtils.create();

        Trigger trigger = Trigger.builder()
            .id(triggerId)
            .type(Trigger.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .lookId(Property.ofValue("100"))
            .watermarkField(Property.ofValue("orders.id"))
            .build();

        Flow flow = Flow.builder()
            .id(flowId)
            .namespace("io.kestra.tests")
            .tenantId("main")
            .revision(1)
            .build();

        TriggerContext triggerContext = TriggerContext.builder()
            .tenantId("main")
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .triggerId(trigger.getId())
            .build();

        RunContext runContext = runContextInitializer.forScheduler(
            (DefaultRunContext) runContextFactory.of(flow.getId(), flow.getNamespace()),
            triggerContext,
            trigger
        );

        ConditionContext conditionContext = ConditionContext.builder()
            .flow(flow)
            .runContext(runContext)
            .build();

        // 1. First poll with initial 2 rows
        stubFor(get(urlPathEqualTo("/api/4.0/looks/100/run/json"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("""
                [{"orders.id":10,"orders.total":100},{"orders.id":20,"orders.total":200}]
                """)));

        Optional<Execution> result1 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(result1).isPresent();
        assertThat(result1.get().getTrigger().getVariables()).containsEntry("size", 2L);

        // 2. Second poll with same rows -> should NOT fire
        Optional<Execution> result2 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(result2).isEmpty();

        // 3. Third poll with 1 new row (id 30) and previous rows
        stubFor(get(urlPathEqualTo("/api/4.0/looks/100/run/json"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("""
                [{"orders.id":10,"orders.total":100},{"orders.id":20,"orders.total":200},{"orders.id":30,"orders.total":300}]
                """)));

        Optional<Execution> result3 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(result3).isPresent();
        Map<String, Object> vars3 = result3.get().getTrigger().getVariables();
        assertThat(vars3).containsEntry("size", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows3 = (List<Map<String, Object>>) vars3.get("rows");
        assertThat(rows3).containsExactly(Map.of("orders.id", 30, "orders.total", 300));
    }

    @Test
    void shouldPollAndFireOnDataChangeWithoutWatermarkField(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();

        String triggerId = IdUtils.create();
        String flowId = IdUtils.create();

        Trigger trigger = Trigger.builder()
            .id(triggerId)
            .type(Trigger.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .lookId(Property.ofValue("200"))
            .build();

        Flow flow = Flow.builder()
            .id(flowId)
            .namespace("io.kestra.tests")
            .tenantId("main")
            .revision(1)
            .build();

        TriggerContext triggerContext = TriggerContext.builder()
            .tenantId("main")
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .triggerId(trigger.getId())
            .build();

        RunContext runContext = runContextInitializer.forScheduler(
            (DefaultRunContext) runContextFactory.of(flow.getId(), flow.getNamespace()),
            triggerContext,
            trigger
        );

        ConditionContext conditionContext = ConditionContext.builder()
            .flow(flow)
            .runContext(runContext)
            .build();

        stubFor(get(urlPathEqualTo("/api/4.0/looks/200/run/json"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("""
                [{"metric":"active_users","value":50}]
                """)));

        Optional<Execution> result1 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(result1).isPresent();

        // Second poll with same data -> empty
        Optional<Execution> result2 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(result2).isEmpty();

        // Third poll with updated data
        stubFor(get(urlPathEqualTo("/api/4.0/looks/200/run/json"))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(200).withBody("""
                [{"metric":"active_users","value":75}]
                """)));

        Optional<Execution> result3 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(result3).isPresent();
        assertThat(result3.get().getTrigger().getVariables()).containsEntry("size", 1L);
    }

    @Test
    void shouldNotShareWatermarkBetweenFlowsWithSameTriggerId(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(get(urlPathEqualTo("/api/4.0/looks/300/run/json"))
            .willReturn(aResponse().withStatus(200).withBody("[{\"orders.id\":10}]")));

        Trigger trigger = trigger(wireMockRuntimeInfo, "300", "orders.id", "shared-trigger");

        Optional<Execution> flowA1 = poll(trigger, IdUtils.create());
        assertThat(flowA1).isPresent();

        // Another flow with the same trigger id has its own watermark
        String flowB = IdUtils.create();
        assertThat(poll(trigger, flowB)).isPresent();
        assertThat(poll(trigger, flowB)).isEmpty();
    }

    @Test
    void shouldBuildLengthPrefixedStateKey() {
        assertThat(Trigger.stateKey("ab", "c")).isNotEqualTo(Trigger.stateKey("a", "bc"));
        assertThat(Trigger.stateKey("a", "b_c")).isNotEqualTo(Trigger.stateKey("a_b", "c"));
    }

    @Test
    void shouldRecordEmptyResultInSignatureMode(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        Trigger trigger = trigger(wireMockRuntimeInfo, "400", null, IdUtils.create());
        String flowId = IdUtils.create();

        stubFor(get(urlPathEqualTo("/api/4.0/looks/400/run/json"))
            .willReturn(aResponse().withStatus(200).withBody("[{\"v\":1}]")));
        assertThat(poll(trigger, flowId)).isPresent();

        // Empty poll must not fire but must advance the state
        stubFor(get(urlPathEqualTo("/api/4.0/looks/400/run/json"))
            .willReturn(aResponse().withStatus(200).withBody("[]")));
        assertThat(poll(trigger, flowId)).isEmpty();

        // Same data as the first poll is now a change again
        stubFor(get(urlPathEqualTo("/api/4.0/looks/400/run/json"))
            .willReturn(aResponse().withStatus(200).withBody("[{\"v\":1}]")));
        assertThat(poll(trigger, flowId)).isPresent();
    }

    @Test
    void shouldPollEmptyLookWithWatermarkThenFireOnFirstRows(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        Trigger trigger = trigger(wireMockRuntimeInfo, "500", "orders.id", IdUtils.create());
        String flowId = IdUtils.create();

        stubFor(get(urlPathEqualTo("/api/4.0/looks/500/run/json"))
            .willReturn(aResponse().withStatus(200).withBody("[]")));
        assertThat(poll(trigger, flowId)).isEmpty();

        stubFor(get(urlPathEqualTo("/api/4.0/looks/500/run/json"))
            .willReturn(aResponse().withStatus(200).withBody("[{\"orders.id\":1}]")));
        assertThat(poll(trigger, flowId)).isPresent();
    }

    @Test
    void shouldEncodeLookIdPathSegment(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubAuthentication();
        stubFor(get(urlPathEqualTo("/api/4.0/looks/..%2Fx/run/json"))
            .willReturn(aResponse().withStatus(200).withBody("[{\"v\":1}]")));
        stubFor(get(urlPathEqualTo("/api/4.0/looks/1%3Fx%3D/run/json"))
            .willReturn(aResponse().withStatus(200).withBody("[{\"v\":1}]")));

        assertThat(poll(trigger(wireMockRuntimeInfo, "../x", null, IdUtils.create()), IdUtils.create())).isPresent();
        assertThat(poll(trigger(wireMockRuntimeInfo, "1?x=", null, IdUtils.create()), IdUtils.create())).isPresent();
    }

    private Trigger trigger(WireMockRuntimeInfo info, String lookId, String watermarkField, String triggerId) {
        return Trigger.builder()
            .id(triggerId)
            .type(Trigger.class.getName())
            .baseUrl(Property.ofValue(info.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .lookId(Property.ofValue(lookId))
            .watermarkField(watermarkField == null ? null : Property.ofValue(watermarkField))
            .build();
    }

    private Optional<Execution> poll(Trigger trigger, String flowId) throws Exception {
        Flow flow = Flow.builder().id(flowId).namespace("io.kestra.tests").tenantId("main").revision(1).build();
        TriggerContext triggerContext = TriggerContext.builder()
            .tenantId("main")
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .triggerId(trigger.getId())
            .build();
        RunContext runContext = runContextInitializer.forScheduler(
            (DefaultRunContext) runContextFactory.of(flow.getId(), flow.getNamespace()),
            triggerContext,
            trigger
        );
        return trigger.evaluate(ConditionContext.builder().flow(flow).runContext(runContext).build(), triggerContext);
    }

    private static void stubAuthentication() {
        stubFor(post(urlPathEqualTo(LOGIN_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{\"access_token\":\"" + TOKEN + "\"}")));
        stubFor(delete(urlPathEqualTo(LOGOUT_PATH))
            .withHeader("Authorization", equalTo("token " + TOKEN))
            .willReturn(aResponse().withStatus(204)));
    }
}

