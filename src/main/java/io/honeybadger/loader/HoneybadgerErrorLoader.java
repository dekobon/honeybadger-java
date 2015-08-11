package io.honeybadger.loader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.honeybadger.reporter.NoticeReporter;
import io.honeybadger.reporter.HoneybadgerReporter;
import io.honeybadger.reporter.dto.ReportedError;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * Utility class used to load a fault's details into a readable object
 * structure.
 */
public class HoneybadgerErrorLoader {
    private static final String BASE_ID_QUERY_URI = "https://app.honeybadger.io/notice/";
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Gson gson = new GsonBuilder()
            .create();

    URI findFaultUri(UUID faultId) throws IOException {
        if (faultId == null) throw new IllegalArgumentException("Null id not accepted");

        URI lookupUri = URI.create(BASE_ID_QUERY_URI + faultId);

        HttpClientBuilder builder = HttpClientBuilder.create()
                .disableRedirectHandling();

        final URI redirectedUri;

        // Use Proxy if configured
        if (System.getProperty("http.proxyHost") != null &&
                !System.getProperty("http.proxyHost").isEmpty()) {
            int port = Integer.parseInt(System.getProperty("http.proxyPort"));
            HttpHost proxy = new HttpHost(System.getProperty("http.proxyHost"),
                    port);

            builder.setProxy(proxy);
        }

        /* Do a HTTP HEAD call to get the redirected URI. */
        try (CloseableHttpClient client = builder.build()) {
            HttpHead request = new HttpHead(lookupUri);

            logger.debug("Querying for error location: {}", lookupUri);
            try (CloseableHttpResponse response = client.execute(request)) {
                Header location = response.getFirstHeader("Location");

                if (location == null) return null;

                redirectedUri = URI.create(location.getValue());
            }
        }

        /* The redirected URI isn't the exact URI that we want but it has
         * all of the information we need to build it. */
        return buildFaultDetailsUri(redirectedUri);
    }

    private URI buildFaultDetailsUri(URI redirectedUri) {
        if (redirectedUri == null) return null;

        String path = redirectedUri.getPath();
        String[] pathParts = path.split("/");
        String projectId = pathParts[2];
        String faultId = pathParts[4];
        String errorId = pathParts[5];

        String faultDetailsLocation = String.format(
                "%s://%s%s/v1/projects/%s/faults/%s/notices/%s/",
                redirectedUri.getScheme(),
                redirectedUri.getHost(),
                redirectedUri.getPort() > 0 ? ":" + redirectedUri.getPort() : "",
                projectId, faultId, errorId
        );

        return URI.create(faultDetailsLocation);
    }

    String pullFaultJson(UUID faultId) throws IOException {
        String readApiKey = readApiKey();

        if (readApiKey == null || readApiKey.isEmpty()) {
            String msg = String.format("Property %s must be set if you are " +
                    "going to be accessing the Read API", NoticeReporter.READ_API_KEY_PROP_KEY);
            throw new IllegalArgumentException(msg);
        }

        final URI baseURI = URI.create(String.format("%s/%s",
                HoneybadgerReporter.honeybadgerUrl(), faultId));

        String withAuth = String.format("%s/?auth_token=%s",
                baseURI, readApiKey);

        logger.debug("Querying for error details: {}", baseURI);

        Response response = Request
                .Get(withAuth)
                .addHeader("Accept", "application/json")
                .execute();

        return response.returnContent().asString();
    }

    /**
     * Finds the Read API key, preferring ENV to system properties.
     *
     * @return the API key if found, otherwise null
     */
    private static String readApiKey() {
        String envKey = System.getenv("HONEYBADGER_READ_API_KEY");
        if (envKey != null && !envKey.isEmpty()) return envKey;

        return System.getProperty(NoticeReporter.READ_API_KEY_PROP_KEY);
    }

    public ReportedError findErrorDetails(UUID faultId) throws IOException {
        String json = pullFaultJson(faultId);

        // HACK: Since our API is not symmetric, we do this in order to rename fields
        // and get *some* of the data that we sent.
        JsonObject originalJson = gson.fromJson(json, JsonObject.class).getAsJsonObject();
        JsonObject cgiData = originalJson.get("web_environment").getAsJsonObject();
        originalJson.get("request").getAsJsonObject().add("cgi_data", cgiData);

        ReportedError error = gson.fromJson(originalJson, ReportedError.class);
        return error;
    }
}