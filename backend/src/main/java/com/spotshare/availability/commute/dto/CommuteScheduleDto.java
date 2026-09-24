package com.spotshare.availability.commute.dto;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.spotshare.availability.commute.CommuteSchedule;

/** One weekly commute entry as the host sees it. */
public record CommuteScheduleDto(
        UUID id,
        UUID spaceId,
        /** 0 = Monday .. 6 = Sunday. */
        int dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        /** Integer cents per hour; {@code null} = free. */
        Integer hourlyRateCents,
        /** IANA zone the times-of-day were entered in, e.g. "America/Chicago". */
        String timezone,
        /** False while paused. */
        boolean active,
        OffsetDateTime createdAt
) {
    public static CommuteScheduleDto from(CommuteSchedule schedule) {
        return new CommuteScheduleDto(
                schedule.getId(),
                schedule.getSpace().getId(),
                schedule.getDayOfWeek(),
                schedule.getStartTime(),
                schedule.getEndTime(),
                schedule.getHourlyRateCents(),
                schedule.getTimezone(),
                schedule.isActive(),
                schedule.getCreatedAt());
    }
}
