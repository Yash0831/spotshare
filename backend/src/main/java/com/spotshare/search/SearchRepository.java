package com.spotshare.search;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.spotshare.parking.dto.PhotoDto;

/**
 * The PostGIS discovery queries. One query enforces everything the spec's
 * §10 requires: radius ({@code ST_DWithin}), nearest-first ordering
 * ({@code <->} KNN), active spaces, one share window fully containing
 * {@code [arrival, departure)}, and the optional price/covered/EV/
 * vehicle-size filters.
 *
 * <p>When several of a space's windows contain the period, the cheapest one
 * wins ({@code DISTINCT ON ... ORDER BY hourly_rate_cents NULLS FIRST}) —
 * the price shown is the price the driver would actually pay for that
 * period, and Phase 5 books against exactly one window.
 *
 * <p><strong>Phase 5 will add</strong> "no conflicting CONFIRMED
 * reservation" to this query. Until then, a listed space is shared for the
 * whole period but may still be booked by another driver first.
 */
@Repository
public class SearchRepository {

    private static final String SEARCH_SQL = """
            WITH containing AS (
                SELECT DISTINCT ON (w.space_id)
                    w.space_id, w.starts_at, w.ends_at, w.hourly_rate_cents
                FROM availability_windows w
                WHERE w.starts_at <= :arrival AND w.ends_at >= :departure
                ORDER BY w.space_id, w.hourly_rate_cents NULLS FIRST
            )
            SELECT s.id AS space_id, s.area_label, s.city, s.state,
                   s.parking_type, s.description, s.vehicle_sizes,
                   s.height_limit_inches, s.covered, s.ev_charging,
                   s.latitude, s.longitude,
                   u.first_name, u.last_name,
                   c.starts_at, c.ends_at, c.hourly_rate_cents,
                   ST_Distance(s.geom,
                       ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
            FROM parking_spaces s
            JOIN users u ON u.id = s.host_id
            JOIN containing c ON c.space_id = s.id
            WHERE s.active
              AND ST_DWithin(s.geom,
                  ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters)
              AND (:maxPriceCents IS NULL
                   OR c.hourly_rate_cents IS NULL
                   OR c.hourly_rate_cents <= :maxPriceCents)
              AND (:covered IS NULL OR s.covered = :covered)
              AND (:evCharging IS NULL OR s.ev_charging = :evCharging)
              AND (:vehicleSize IS NULL OR s.vehicle_sizes @> ARRAY[:vehicleSize]::text[])
            ORDER BY s.geom <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
            LIMIT :limit OFFSET :offset
            """;

    private static final String PHOTOS_SQL = """
            SELECT id, space_id, content_type, sort_order, created_at
            FROM parking_photos
            WHERE space_id IN (:spaceIds)
            ORDER BY space_id, sort_order
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<SearchRow> findNearby(double lat, double lng, double radiusMeters,
                              Instant arrival, Instant departure,
                              Integer maxPriceCents, Boolean covered,
                              Boolean evCharging, String vehicleSize,
                              int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("radiusMeters", radiusMeters)
                .addValue("arrival", java.sql.Timestamp.from(arrival))
                .addValue("departure", java.sql.Timestamp.from(departure))
                .addValue("maxPriceCents", maxPriceCents)
                .addValue("covered", covered)
                .addValue("evCharging", evCharging)
                .addValue("vehicleSize", vehicleSize)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(SEARCH_SQL, params, new SearchRowMapper());
    }

    /** Photos for the given spaces, grouped by space id, in sort order. */
    Map<UUID, List<PhotoDto>> findPhotosBySpaceIds(List<UUID> spaceIds) {
        if (spaceIds.isEmpty()) {
            return Map.of();
        }
        List<PhotoRow> rows = jdbc.query(PHOTOS_SQL,
                new MapSqlParameterSource("spaceIds", spaceIds),
                (rs, n) -> new PhotoRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("space_id", UUID.class),
                        rs.getString("content_type"),
                        rs.getInt("sort_order"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)));
        return rows.stream().collect(Collectors.groupingBy(
                PhotoRow::spaceId,
                Collectors.mapping(r -> new PhotoDto(
                        r.id(), r.contentType(), r.sortOrder(),
                        "/api/v1/spaces/" + r.spaceId() + "/photos/" + r.id() + "/content",
                        r.createdAt()),
                        Collectors.toList())));
    }

    private record PhotoRow(UUID id, UUID spaceId, String contentType,
                            int sortOrder, java.time.OffsetDateTime createdAt) {
    }

    private static class SearchRowMapper implements RowMapper<SearchRow> {
        @Override
        public SearchRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            String[] sizes = new String[0];
            Array array = rs.getArray("vehicle_sizes");
            if (array != null) {
                sizes = (String[]) array.getArray();
            }
            return new SearchRow(
                    rs.getObject("space_id", UUID.class),
                    rs.getString("area_label"),
                    rs.getString("city"),
                    rs.getString("state"),
                    rs.getString("parking_type"),
                    rs.getString("description"),
                    sizes,
                    rs.getObject("height_limit_inches", Integer.class),
                    rs.getBoolean("covered"),
                    rs.getBoolean("ev_charging"),
                    rs.getDouble("latitude"),
                    rs.getDouble("longitude"),
                    rs.getString("first_name"),
                    rs.getString("last_name"),
                    rs.getObject("starts_at", java.time.OffsetDateTime.class),
                    rs.getObject("ends_at", java.time.OffsetDateTime.class),
                    rs.getObject("hourly_rate_cents", Integer.class),
                    rs.getDouble("distance_meters"));
        }
    }
}
