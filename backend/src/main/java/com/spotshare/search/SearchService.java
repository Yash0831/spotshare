package com.spotshare.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.spotshare.common.ApiException;
import com.spotshare.parking.ParkingType;
import com.spotshare.parking.dto.PhotoDto;

/**
 * Driver discovery. Validates the search period (Bean Validation on
 * {@link SearchRequest} covers single fields; the cross-field rules live
 * here), runs the PostGIS query, and maps rows to the privacy-safe
 * {@link PublicSpaceDto}.
 *
 * <p>Privacy is structural: this service never reads the exact address,
 * space label, parking instructions, or host contact columns, and the DTO
 * has no fields for them — there is nothing to accidentally leak.
 */
@Service
public class SearchService {

    /** Meters in a mile — radius input and displayed distances are miles. */
    static final double METERS_PER_MILE = 1609.344;

    private static final double DEFAULT_RADIUS_MILES = 3.0;
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 60s grace so a phone clock slightly ahead of the server isn't rejected. */
    private static final long PAST_GRACE_SECONDS = 60;

    private final SearchRepository repository;
    private final GeocodingProvider geocoding;
    private final Clock clock;

    public SearchService(SearchRepository repository, GeocodingProvider geocoding, Clock clock) {
        this.repository = repository;
        this.geocoding = geocoding;
        this.clock = clock;
    }

    public SearchResponseDto search(SearchRequest req) {
        if (!req.arrival().isBefore(req.departure())) {
            throw ApiException.unprocessable("INVALID_SEARCH_PERIOD",
                    "Your arrival time must be before your departure time.");
        }
        if (req.arrival().isBefore(clock.instant().minusSeconds(PAST_GRACE_SECONDS))) {
            throw ApiException.unprocessable("SEARCH_PERIOD_IN_PAST",
                    "Your arrival time is in the past. Please pick a future time.");
        }

        double radiusMiles = req.radiusMiles() != null ? req.radiusMiles() : DEFAULT_RADIUS_MILES;
        int page = req.page() != null ? req.page() : 0;
        int size = req.size() != null ? req.size() : DEFAULT_PAGE_SIZE;
        Integer maxPriceCents = req.maxPrice() == null ? null
                : req.maxPrice().multiply(BigDecimal.valueOf(100)).intValueExact();

        // Fetch one row past the page to know whether another page exists.
        List<SearchRow> rows = repository.findNearby(
                req.lat().doubleValue(), req.lng().doubleValue(),
                radiusMiles * METERS_PER_MILE,
                req.arrival(), req.departure(),
                maxPriceCents, req.covered(), req.evCharging(),
                req.vehicleSize() == null ? null : req.vehicleSize().name(),
                size + 1, page * size);
        boolean hasMore = rows.size() > size;
        List<SearchRow> pageRows = hasMore ? rows.subList(0, size) : rows;

        List<UUID> spaceIds = pageRows.stream().map(SearchRow::spaceId).toList();
        Map<UUID, List<PhotoDto>> photos = repository.findPhotosBySpaceIds(spaceIds);

        List<PublicSpaceDto> results = new ArrayList<>(pageRows.size());
        for (SearchRow row : pageRows) {
            results.add(toPublicDto(row,
                    photos.getOrDefault(row.spaceId(), List.of()),
                    req.arrival(), req.departure()));
        }
        return new SearchResponseDto(results, page, size, hasMore);
    }

    /** Address lookup for the destination search box. */
    public List<GeocodeCandidate> geocode(String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 3) {
            throw ApiException.badRequest("QUERY_TOO_SHORT",
                    "Type at least 3 characters to search for a place.");
        }
        return geocoding.search(q, 5);
    }

    /**
     * Maps one query row to the public DTO. Package-visible for unit tests —
     * this is where the privacy rules are pinned down.
     */
    PublicSpaceDto toPublicDto(SearchRow row, List<PhotoDto> photos,
                               Instant arrival, Instant departure) {
        return new PublicSpaceDto(
                row.spaceId(),
                row.areaLabel(),
                row.city(),
                row.state(),
                ParkingType.valueOf(row.parkingType()),
                row.description(),
                List.of(row.vehicleSizes()),
                row.heightLimitInches(),
                row.covered(),
                row.evCharging(),
                round3(row.latitude()),
                round3(row.longitude()),
                round1(row.distanceMeters() / METERS_PER_MILE),
                hostDisplayName(row.hostFirstName(), row.hostLastName()),
                row.hourlyRateCents(),
                estimatedTotalCents(row.hourlyRateCents(), arrival, departure),
                row.windowStartsAt(),
                row.windowEndsAt(),
                photos);
    }

    /**
     * Prorated total for the period: billable minutes × (hourly rate / 60),
     * rounded HALF_UP to cents (spec §7). Free shares cost nothing.
     */
    static int estimatedTotalCents(Integer hourlyRateCents, Instant arrival, Instant departure) {
        if (hourlyRateCents == null) {
            return 0;
        }
        long minutes = Duration.between(arrival, departure).toMinutes();
        return BigDecimal.valueOf(hourlyRateCents)
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /** "Michael R." — first name plus last initial, per the spec. */
    static String hostDisplayName(String firstName, String lastName) {
        if (lastName == null || lastName.isBlank()) {
            return firstName;
        }
        return firstName + " " + lastName.strip().charAt(0) + ".";
    }

    /** Approximate public coordinates: 3 decimals ≈ 110 m (spec §11). */
    static double round3(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    /** Displayed distances: miles, one decimal (spec §10). */
    static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
