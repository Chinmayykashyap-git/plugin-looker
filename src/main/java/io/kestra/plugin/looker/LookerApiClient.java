package io.kestra.plugin.looker;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.commons.lang3.ArrayUtils;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

public final class LookerApiClient implements AutoCloseable {
    private static final String API_PREFIX = "/api/4.0";

    /**
     * Encodes a user-provided value so it stays a single URL path segment: everything except
     * RFC 3986 unreserved characters is percent-encoded (including '/', '?', '#' and spaces).
     * Dot-only segments are rejected because they would be resolved as path traversal.
     */
    public static String encodePathSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("A Looker path identifier must not be blank.");
        }
        if (".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException("'" + segment + "' is not a valid Looker path identifier.");
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    private final HttpClient httpClient;
    private final String baseUrl;
    private String accessToken;
    private boolean closed;

    public LookerApiClient(RunContext runContext, String baseUrl, String clientId, String clientSecret) throws Exception {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Looker baseUrl must not be empty.");
        }

        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.builder()
            .configuration(HttpConfiguration.builder().allowFailed(Property.ofValue(true)).build())
            .runContext(runContext)
            .build();

        try {
            this.login(clientId, clientSecret);
        } catch (Exception e) {
            try {
                this.httpClient.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }

    public byte[] request(String method, String path, HttpRequest.RequestBody body) throws Exception {
        return this.request(method, path, null, body);
    }

    public byte[] request(String method, String path, Map<String, Object> queryParams, HttpRequest.RequestBody body) throws Exception {
        if (this.closed) {
            throw new IllegalStateException("Looker client is already closed.");
        }

        String fullPath = path;
        if (queryParams != null && !queryParams.isEmpty()) {
            StringJoiner joiner = new StringJoiner("&");
            for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
                if (entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank()) {
                    joiner.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" +
                        URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
                }
            }
            String query = joiner.toString();
            if (!query.isEmpty()) {
                fullPath = fullPath + (fullPath.contains("?") ? "&" : "?") + query;
            }
        }

        HttpResponse<Byte[]> response = this.send(method, fullPath, body, true);
        this.requireSuccess(response, fullPath);

        return response.getBody() == null ? new byte[0] : ArrayUtils.toPrimitive(response.getBody());
    }

    @Override
    public void close() throws Exception {
        if (this.closed) {
            return;
        }

        this.closed = true;
        Exception failure = null;
        try {
            if (this.accessToken != null) {
                HttpResponse<Byte[]> response = this.send("DELETE", API_PREFIX + "/logout", null, true);
                this.requireSuccess(response, API_PREFIX + "/logout");
            }
        } catch (Exception e) {
            failure = e;
        } finally {
            this.accessToken = null;
            try {
                this.httpClient.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private void login(String clientId, String clientSecret) throws Exception {
        HttpResponse<Byte[]> response = this.send(
            "POST",
            API_PREFIX + "/login",
            HttpRequest.UrlEncodedRequestBody.of(Map.of("client_id", clientId, "client_secret", clientSecret)),
            false
        );
        this.requireSuccess(response, API_PREFIX + "/login");

        byte[] responseBody = response.getBody() == null ? new byte[0] : ArrayUtils.toPrimitive(response.getBody());
        if (responseBody.length == 0) {
            throw new IllegalStateException("Looker login response was empty.");
        }

        JsonNode loginResponse;
        try {
            loginResponse = JacksonMapper.ofJson().readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Looker login response was not valid JSON.", e);
        }

        JsonNode token = loginResponse == null ? null : loginResponse.get("access_token");
        if (token == null || !token.isTextual() || token.asText().isBlank()) {
            throw new IllegalStateException("Looker login response did not contain a valid access_token.");
        }

        this.accessToken = token.asText();
    }

    private HttpResponse<Byte[]> send(String method, String path, HttpRequest.RequestBody body, boolean authenticated) throws Exception {
        HttpRequest.HttpRequestBuilder request = HttpRequest.builder()
            .method(method)
            .uri(this.uri(path));
        if (body != null) {
            request.body(body);
        }
        if (authenticated) {
            if (this.accessToken == null) {
                throw new IllegalStateException("Looker client is not authenticated.");
            }
            request.addHeader("Authorization", "token " + this.accessToken);
        }

        return this.httpClient.request(request.build(), Byte[].class);
    }

    private URI uri(String path) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(this.baseUrl + normalizedPath);
    }

    private void requireSuccess(HttpResponse<?> response, String path) {
        int statusCode = response.getStatus().getCode();
        if (statusCode < 200 || statusCode >= 300) {
            String operation = path.endsWith("/login") ? "Looker authentication" : "Looker API request";
            throw new IllegalStateException("%s to '%s' failed with HTTP status %d.".formatted(operation, path, statusCode));
        }
    }
}
