package io.kestra.plugin.looker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.looker.queries.ResultFormat;

public final class LookerQueryResults {
    private LookerQueryResults() {
    }

    public static FetchOutput output(RunContext runContext, FetchType fetchType, ResultFormat resultFormat, byte[] response, String endpoint) throws Exception {
        if (fetchType == FetchType.STORE) {
            String extension = resultFormat == ResultFormat.CSV ? ".csv" : ".json";
            Path file = runContext.workingDir().createTempFile(response, extension);
            return FetchOutput.builder()
                .uri(runContext.storage().putFile(file.toFile()))
                .build();
        }

        if (fetchType == FetchType.NONE) {
            return FetchOutput.builder().build();
        }

        if (resultFormat != ResultFormat.JSON) {
            throw new IllegalArgumentException("Looker CSV results require fetchType=STORE.");
        }

        List<Object> rows;
        try {
            rows = JacksonMapper.toList(new String(response, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Looker query response from '%s' was not a valid JSON array.".formatted(endpoint), e);
        }

        for (Object row : rows) {
            if (!(row instanceof Map<?, ?>)) {
                throw new IllegalStateException("Looker query response from '%s' contained a result that was not an object.".formatted(endpoint));
            }
        }

        FetchOutput.FetchOutputBuilder output = FetchOutput.builder().size((long) rows.size());
        if (fetchType == FetchType.FETCH_ONE) {
            return output
                .row(rows.isEmpty() ? null : JacksonMapper.toMap(rows.getFirst()))
                .build();
        }

        return output.rows(rows).build();
    }
}

