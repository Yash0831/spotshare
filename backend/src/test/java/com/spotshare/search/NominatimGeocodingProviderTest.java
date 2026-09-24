package com.spotshare.search;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.spotshare.common.ApiException;

/**
 * The Nominatim JSON parsing is a pure function — tested here without any
 * network. Live calls can't run in sandboxes where the JVM has no outbound
 * TCP; the provider documents that and returns GEOCODER_UNAVAILABLE.
 */
class NominatimGeocodingProviderTest {

    private static final String SAMPLE = """
            [
              {
                "place_id": 1,
                "display_name": "West Loop, Chicago, Cook County, Illinois, USA",
                "lat": "41.8858",
                "lon": "-87.6189"
              },
              {
                "place_id": 2,
                "display_name": "West Loop Gate, Chicago, Illinois, USA",
                "lat": "41.8866",
                "lon": "-87.6205"
              },
              {
                "place_id": 3,
                "display_name": "Broken entry, no coordinates"
              }
            ]
            """;

    @Test
    void parseCandidates_mapsDisplayNameAndCoordinates() {
        List<GeocodeCandidate> candidates = NominatimGeocodingProvider.parseCandidates(SAMPLE);

        // The entry without coordinates is skipped, not fatal.
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).displayName())
                .isEqualTo("West Loop, Chicago, Cook County, Illinois, USA");
        assertThat(candidates.get(0).latitude()).isEqualTo(41.8858);
        assertThat(candidates.get(0).longitude()).isEqualTo(-87.6189);
    }

    @Test
    void parseCandidates_emptyArray_returnsEmptyList() {
        assertThat(NominatimGeocodingProvider.parseCandidates("[]")).isEmpty();
    }

    @Test
    void parseCandidates_garbageJson_returnsGeocoderUnavailable() {
        assertThatThrownBy(() -> NominatimGeocodingProvider.parseCandidates("not json"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.getCode()).isEqualTo("GEOCODER_UNAVAILABLE");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }
}
