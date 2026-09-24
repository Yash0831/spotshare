package com.spotshare.search;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotshare.common.ApiException;
import com.spotshare.parking.VehicleSize;
import com.spotshare.parking.dto.PhotoDto;

/**
 * Unit tests for discovery: period validation, the prorated total, the
 * privacy-safe DTO mapping, and geocoding delegation. The PostGIS SQL runs
 * only against a real database ({@link SearchIntegrationTest}).
 */
class SearchServiceTest {

    private SearchRepository repository;
    private GeocodingProvider geocoding;
    private SearchService service;
    private Clock clock;

    private final Instant now = Instant.parse("2030-06-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        repository = mock(SearchRepository.class);
        geocoding = (query, limit) -> List.of(
                new GeocodeCandidate("West Loop, Chicago, IL", 41.885, -87.619));
        clock = Clock.fixed(now, ZoneOffset.UTC);
        service = new SearchService(repository, geocoding, clock);
    }

    private SearchRequest request(Instant arrival, Instant departure) {
        return new SearchRequest(
                new BigDecimal("41.8858"), new BigDecimal("-87.6189"),
                null, arrival, departure, null, null, null, null, null, null);
    }

    private SearchRow row() {
        OffsetDateTime start = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return new SearchRow(
                UUID.randomUUID(), "West Loop", "Chicago", "IL",
                "ASSIGNED_SPACE", "A quiet assigned spot",
                new String[]{"SEDAN", "SUV"}, null,
                true, false,
                41.88575, -87.61894,
                "Michael", "Reyes",
                start, start.plusHours(5),
                300, 1609.344);
    }

    @Test
    void search_arrivalNotBeforeDeparture_isRejected() {
        SearchRequest req = request(now.plus(3, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS));

        assertThatThrownBy(() -> service.search(req))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.getCode()).isEqualTo("INVALID_SEARCH_PERIOD");
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void search_arrivalInPast_isRejected() {
        SearchRequest req = request(now.minus(2, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> service.search(req))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("SEARCH_PERIOD_IN_PAST"));
    }

    @Test
    void search_valid_delegatesToRepositoryAndMapsDto() {
        SearchRow row = row();
        given(repository.findNearby(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), isNull(), isNull(), isNull(), isNull(),
                anyInt(), anyInt())).willReturn(List.of(row));
        given(repository.findPhotosBySpaceIds(anyList())).willReturn(Map.of());

        SearchResponseDto response = service.search(
                request(now.plus(1, ChronoUnit.HOURS), now.plus(6, ChronoUnit.HOURS)));

        assertThat(response.results()).hasSize(1);
        PublicSpaceDto dto = response.results().get(0);
        assertThat(dto.areaLabel()).isEqualTo("West Loop");
        assertThat(dto.city()).isEqualTo("Chicago");
        // Approximate location: 3 decimals (~110 m).
        assertThat(dto.approxLatitude()).isEqualTo(41.886);
        assertThat(dto.approxLongitude()).isEqualTo(-87.619);
        // 1609.344 m = exactly 1.0 mile.
        assertThat(dto.distanceMiles()).isEqualTo(1.0);
        assertThat(dto.hostName()).isEqualTo("Michael R.");
        // 5 h × $3.00 = $15.00.
        assertThat(dto.hourlyRateCents()).isEqualTo(300);
        assertThat(dto.estimatedTotalCents()).isEqualTo(1500);
        assertThat(response.hasMore()).isFalse();
        verify(repository).findPhotosBySpaceIds(List.of(row.spaceId()));
    }

    @Test
    void search_freeShare_totalIsZero() {
        SearchRow free = new SearchRow(
                row().spaceId(), row().areaLabel(), row().city(), row().state(),
                row().parkingType(), row().description(), row().vehicleSizes(),
                row().heightLimitInches(), row().covered(), row().evCharging(),
                row().latitude(), row().longitude(), row().hostFirstName(), row().hostLastName(),
                row().windowStartsAt(), row().windowEndsAt(), null, row().distanceMeters());
        given(repository.findNearby(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), isNull(), isNull(), isNull(), isNull(),
                anyInt(), anyInt())).willReturn(List.of(free));
        given(repository.findPhotosBySpaceIds(anyList())).willReturn(Map.of());

        PublicSpaceDto dto = service.search(request(now.plus(1, ChronoUnit.HOURS), now.plus(3, ChronoUnit.HOURS)))
                .results().get(0);

        assertThat(dto.hourlyRateCents()).isNull();
        assertThat(dto.estimatedTotalCents()).isZero();
    }

    @Test
    void search_maxPrice_isConvertedToCents() {
        given(repository.findNearby(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any(), isNull(), isNull(), isNull(),
                anyInt(), anyInt())).willReturn(List.of());
        given(repository.findPhotosBySpaceIds(anyList())).willReturn(Map.of());

        SearchRequest req = new SearchRequest(
                new BigDecimal("41.8858"), new BigDecimal("-87.6189"),
                null, now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS),
                new BigDecimal("12.50"), null, null, null, null, null);
        service.search(req);

        verify(repository).findNearby(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), org.mockito.ArgumentMatchers.eq(1250),
                isNull(), isNull(), isNull(), anyInt(), anyInt());
    }

    @Test
    void search_vehicleSize_isPassedAsEnumName() {
        given(repository.findNearby(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), isNull(), isNull(), isNull(), any(),
                anyInt(), anyInt())).willReturn(List.of());
        given(repository.findPhotosBySpaceIds(anyList())).willReturn(Map.of());

        SearchRequest req = new SearchRequest(
                new BigDecimal("41.8858"), new BigDecimal("-87.6189"),
                null, now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS),
                null, null, null, VehicleSize.SUV, null, null);
        service.search(req);

        verify(repository).findNearby(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), isNull(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq("SUV"), anyInt(), anyInt());
    }

    @Test
    void search_hasMore_trimsToPageSize() {
        given(repository.findNearby(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), isNull(), isNull(), isNull(), isNull(),
                anyInt(), anyInt())).willReturn(List.of(row(), row()));
        given(repository.findPhotosBySpaceIds(anyList())).willReturn(Map.of());

        SearchRequest req = new SearchRequest(
                new BigDecimal("41.8858"), new BigDecimal("-87.6189"),
                null, now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS),
                null, null, null, null, 0, 1);
        SearchResponseDto response = service.search(req);

        assertThat(response.results()).hasSize(1);
        assertThat(response.hasMore()).isTrue();
    }

    @Test
    void estimatedTotal_proratesByMinute() {
        // 90 min × $3.00/hr = $4.50.
        assertThat(SearchService.estimatedTotalCents(300,
                now, now.plusSeconds(90 * 60))).isEqualTo(450);
        // 5 min × $1.00/hr = $0.083… → rounds to $0.08.
        assertThat(SearchService.estimatedTotalCents(100,
                now, now.plusSeconds(5 * 60))).isEqualTo(8);
        assertThat(SearchService.estimatedTotalCents(null, now, now.plus(1, ChronoUnit.HOURS)))
                .isZero();
    }

    @Test
    void hostDisplayName_firstNamePlusLastInitial() {
        assertThat(SearchService.hostDisplayName("Michael", "Reyes")).isEqualTo("Michael R.");
        assertThat(SearchService.hostDisplayName("Ana", "")).isEqualTo("Ana");
        assertThat(SearchService.hostDisplayName("Bo", null)).isEqualTo("Bo");
    }

    @Test
    void publicDto_serializesOnlyThePrivacySafeFields() throws Exception {
        PublicSpaceDto dto = service.toPublicDto(row(), List.of(
                        new PhotoDto(UUID.randomUUID(), "image/jpeg", 0,
                                "/api/v1/spaces/x/photos/y/content",
                                OffsetDateTime.ofInstant(now, ZoneOffset.UTC))),
                now.plus(1, ChronoUnit.HOURS), now.plus(6, ChronoUnit.HOURS));

        JsonNode json = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .findAndAddModules()
                .build()
                .valueToTree(dto);
        Set<String> fields = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);

        // The exact allowlist: no address, label, instructions, or contact
        // field can ever appear here, because the DTO has no such fields.
        assertThat(fields).containsExactlyInAnyOrder(
                "id", "areaLabel", "city", "state", "parkingType", "description",
                "vehicleSizes", "heightLimitInches", "covered", "evCharging",
                "approxLatitude", "approxLongitude", "distanceMiles", "hostName",
                "hourlyRateCents", "estimatedTotalCents",
                "windowStartsAt", "windowEndsAt", "photos");
        assertThat(json.toString())
                .doesNotContain("123 Wacker", "B17", "call me", "host@example.com");
    }

    @Test
    void geocode_shortQuery_isRejected() {
        assertThatThrownBy(() -> service.geocode("ab"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUERY_TOO_SHORT"));
    }

    @Test
    void geocode_delegatesToProvider() {
        List<GeocodeCandidate> candidates = service.geocode("West Loop Chicago");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).displayName()).contains("West Loop");
    }
}
