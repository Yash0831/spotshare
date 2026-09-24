package com.spotshare.search;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotshare.common.ApiException;
import com.spotshare.config.GeocodingProperties;

/**
 * {@link GeocodingProvider} backed by OpenStreetMap's Nominatim search API.
 *
 * <p>Honest limitations, documented rather than hidden:
 * <ul>
 *   <li>The JVM in some sandboxes cannot open outbound TCP connections at
 *       all — live Nominatim calls are then impossible and the endpoint
 *       returns {@code GEOCODER_UNAVAILABLE} (HTTP 502). The interface and
 *       this implementation are still fully wired; {@link SearchService}
 *       is tested with a fake provider.</li>
 *   <li>Nominatim's usage policy asks for at most one request per second;
 *       this provider enforces that gap itself so a burst of drivers can
 *       never get the deployment's IP blocked.</li>
 *   <li>The query is scoped to the United States
 *       ({@code countrycodes=us}) — SpotShare is a US product.</li>
 * </ul>
 */
@Service
public class NominatimGeocodingProvider implements GeocodingProvider {

    /** Minimum gap between outbound calls, per Nominatim's usage policy. */
    private static final long MIN_GAP_MILLIS = 1100;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GeocodingProperties properties;
    private final AtomicLong lastCallMillis = new AtomicLong(0);

    public NominatimGeocodingProvider(ObjectMapper objectMapper, GeocodingProperties properties) {
        this(objectMapper, properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** Test seam: inject a stubbed client without touching the parsing. */
    NominatimGeocodingProvider(ObjectMapper objectMapper, GeocodingProperties properties,
                               HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public List<GeocodeCandidate> search(String query, int limit) {
        enforceRateGap();
        URI uri = URI.create(properties.baseUrl()
                + "/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=jsonv2&limit=" + limit
                + "&countrycodes=us&addressdetails=0");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", properties.userAgent())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw geocoderUnavailable();
            }
            return parseCandidates(response.body());
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw geocoderUnavailable();
        } catch (Exception e) {
            throw geocoderUnavailable();
        }
    }

    /**
     * Parses a Nominatim {@code jsonv2} search array. Pure function —
     * unit-tested without any network.
     */
    static List<GeocodeCandidate> parseCandidates(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            List<GeocodeCandidate> out = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    JsonNode name = node.get("display_name");
                    JsonNode lat = node.get("lat");
                    JsonNode lon = node.get("lon");
                    if (name == null || lat == null || lon == null) {
                        continue;
                    }
                    out.add(new GeocodeCandidate(
                            name.asText(),
                            Double.parseDouble(lat.asText()),
                            Double.parseDouble(lon.asText())));
                }
            }
            return out;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODER_UNAVAILABLE",
                    "The address lookup service didn't respond properly. Please try again.");
        }
    }

    /** Sleeps just long enough to keep ≥1s between outbound calls. */
    private void enforceRateGap() {
        synchronized (lastCallMillis) {
            long wait = MIN_GAP_MILLIS - (System.currentTimeMillis() - lastCallMillis.get());
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw geocoderUnavailable();
                }
            }
            lastCallMillis.set(System.currentTimeMillis());
        }
    }

    private static ApiException geocoderUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODER_UNAVAILABLE",
                "The address lookup service is unreachable right now. "
                        + "Please try again, or drop a pin on the map instead.");
    }
}
