package com.spotshare.reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.spotshare.availability.AvailabilityWindow;
import com.spotshare.availability.AvailabilityWindowRepository;
import com.spotshare.availability.WindowSource;
import com.spotshare.common.ApiException;
import com.spotshare.parking.ParkingSpace;
import com.spotshare.parking.ParkingSpaceRepository;
import com.spotshare.reservation.dto.CreateReservationRequest;
import com.spotshare.user.Role;
import com.spotshare.user.User;
import com.spotshare.user.UserRepository;

/**
 * ReservationService behavior without a database: booking rules, pricing,
 * idempotency, privacy on the detail view, cancellation, and the friendly
 * mapping of the exclusion-constraint backstop. The transaction template is
 * stubbed to run callbacks inline; the advisory lock is a no-op.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final Pattern CODE_PATTERN = Pattern.compile("SP-[A-HJ-NP-Z2-9]{5}");

    @Mock
    private ReservationRepository reservations;
    @Mock
    private ParkingSpaceRepository spaces;
    @Mock
    private AvailabilityWindowRepository windows;
    @Mock
    private UserRepository users;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private TransactionTemplate tx;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private ReservationService service;

    private User host;
    private User driver;
    private ParkingSpace space;
    private AvailabilityWindow window;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        service = new ReservationService(reservations, spaces, windows, users, jdbc, tx, clock);
        host = new User("host@example.com", "hash", "Holly", "Host", null, Role.USER);
        driver = new User("driver@example.com", "hash", "Dan", "Driver", null, Role.USER);
        space = new ParkingSpace(host);
        space.setActive(true);
        space.setAddress("123 Private Way");
        space.setLabel("B17");
        space.setParkingInstructions("Gate code 1234");
        OffsetDateTime now = OffsetDateTime.now(clock);
        window = new AvailabilityWindow(space, now.minusMinutes(30), now.plusHours(4),
                WindowSource.MANUAL, 300, now);

        // Transactions run inline; the advisory lock is a no-op.
        // Lenient: validation tests throw before the transaction starts.
        org.mockito.stubbing.Answer<Object> runInline = inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        };
        lenient().doAnswer(runInline).when(tx).execute(any(TransactionCallback.class));
        lenient().when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(null);
        lenient().when(users.getReferenceById(driver.getId())).thenReturn(driver);
    }

    private void stubHappyPath() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        given(windows.findContaining(any(), any(), any())).willReturn(List.of(window));
        given(reservations.existsConfirmedOverlap(any(), any(), any())).willReturn(false);
    }

    private void stubSuccessfulSave() {
        given(reservations.saveAndFlush(any(Reservation.class)))
                .willAnswer((Answer<Reservation>) inv -> inv.getArgument(0));
    }

    private CreateReservationRequest request(OffsetDateTime arrival, OffsetDateTime departure) {
        return new CreateReservationRequest(space.getId(), arrival, departure);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ---- booking ----------------------------------------------------------

    @Test
    void booksInsideAWindowWithProratedTotal() {
        stubHappyPath();
        stubSuccessfulSave();
        OffsetDateTime now = now();

        ReservationService.CreateResult result =
                service.create(driver.getId(), request(now.plusHours(1), now.plusHours(3)), "key-1");

        assertThat(result.created()).isTrue();
        assertThat(result.reservation().totalCents()).isEqualTo(600); // 2 h × $3
        assertThat(result.reservation().hourlyRateCents()).isEqualTo(300);
        assertThat(result.reservation().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(result.reservation().code()).matches(CODE_PATTERN);
    }

    @Test
    void freeShareBooksForZero() {
        window = new AvailabilityWindow(space, now().minusMinutes(30), now().plusHours(4),
                WindowSource.MANUAL, null, now());
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        given(windows.findContaining(any(), any(), any())).willReturn(List.of(window));
        given(reservations.existsConfirmedOverlap(any(), any(), any())).willReturn(false);
        stubSuccessfulSave();
        OffsetDateTime now = now();

        var result = service.create(driver.getId(), request(now.plusHours(1), now.plusHours(2)), null);

        assertThat(result.reservation().totalCents()).isZero();
        assertThat(result.reservation().hourlyRateCents()).isNull();
    }

    @Test
    void reservationCodesAreUnambiguous() {
        for (int i = 0; i < 200; i++) {
            String code = ReservationService.generateCode();
            assertThat(code).matches(CODE_PATTERN);
            assertThat(code).doesNotContain("0", "O", "1", "I");
        }
    }

    @Test
    void rejectsSelfBooking() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        OffsetDateTime now = now();

        assertThatThrownBy(() -> service.create(host.getId(), request(now.plusHours(1), now.plusHours(2)), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(api.getCode()).isEqualTo("CANNOT_BOOK_OWN_SPACE");
                });
    }

    @Test
    void rejectsInactiveSpace() {
        space.setActive(false);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        OffsetDateTime now = now();

        assertThatThrownBy(() -> service.create(driver.getId(), request(now.plusHours(1), now.plusHours(2)), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SPACE_INACTIVE"));
    }

    @Test
    void rejectsUnknownSpace() {
        given(spaces.findById(any())).willReturn(Optional.empty());
        OffsetDateTime now = now();

        assertThatThrownBy(() -> service.create(driver.getId(), request(now.plusHours(1), now.plusHours(2)), null))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SPACE_NOT_FOUND"));
    }

    @Test
    void rejectsInvertedPeriodWithoutTouchingTheDatabase() {
        OffsetDateTime now = now();

        assertThatThrownBy(() -> service.create(driver.getId(), request(now.plusHours(2), now.plusHours(1)), null))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("INVALID_PERIOD"));
        verifyNoInteractions(tx);
    }

    @Test
    void rejectsArrivalInThePast() {
        OffsetDateTime now = now();

        assertThatThrownBy(() -> service.create(driver.getId(),
                        request(now.minusHours(2), now.minusHours(1)), null))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ARRIVAL_IN_PAST"));
    }

    @Test
    void rejectsPeriodOutsideAnyWindow() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        given(windows.findContaining(any(), any(), any())).willReturn(List.of());
        OffsetDateTime now = now();

        assertThatThrownBy(() -> service.create(driver.getId(), request(now.plusHours(1), now.plusHours(2)), null))
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("PERIOD_NOT_AVAILABLE");
                });
    }

    @Test
    void overlappingReservationBecomesFriendly409() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        given(windows.findContaining(any(), any(), any())).willReturn(List.of(window));
        given(reservations.existsConfirmedOverlap(any(), any(), any())).willReturn(true);
        OffsetDateTime now = now();

        assertThatThrownBy(() -> service.create(driver.getId(), request(now.plusHours(1), now.plusHours(2)), null))
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("SPACE_JUST_RESERVED");
                    assertThat(api.getMessage()).contains("just reserved");
                });
        verify(reservations, never()).saveAndFlush(any());
    }

    @Test
    void exclusionViolationBackstopBecomesFriendly409() {
        stubHappyPath();
        DataIntegrityViolationException exclusion = new DataIntegrityViolationException(
                "exclusion", new SQLException("conflicting key", "23P01"));
        given(reservations.saveAndFlush(any(Reservation.class))).willThrow(exclusion);
        OffsetDateTime now = now();

        assertThatThrownBy(() -> service.create(driver.getId(), request(now.plusHours(1), now.plusHours(2)), null))
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("SPACE_JUST_RESERVED");
                    // Never leaks SQL internals.
                    assertThat(api.getMessage()).doesNotContain("exclusion", "23P01", "gist");
                });
    }

    // ---- idempotency --------------------------------------------------------

    @Test
    void repeatKeyReturnsOriginalWithoutDuplicate() {
        // Only the space lookup and the replay check are reached: the replay
        // returns before the window/overlap/save stubs would be touched, so
        // stubHappyPath() would leave unnecessary stubbings under strict stubs.
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        Reservation existing = new Reservation(space, driver, now().plusHours(1), now().plusHours(2),
                300, 600, "SP-ABCDE", "key-9", now());
        given(reservations.findByDriverIdAndIdempotencyKey(driver.getId(), "key-9"))
                .willReturn(Optional.of(existing));

        var result = service.create(driver.getId(),
                request(now().plusHours(1), now().plusHours(2)), "key-9");

        assertThat(result.created()).isFalse();
        assertThat(result.reservation().code()).isEqualTo("SP-ABCDE");
        verify(reservations, never()).saveAndFlush(any());
    }

    @Test
    void idempotencyRaceReturnsTheWinnersReservation() {
        stubHappyPath();
        Reservation winners = new Reservation(space, driver, now().plusHours(1), now().plusHours(2),
                300, 600, "SP-W1NNR", "key-race", now());
        // Pre-insert check misses; the insert hits the (driver, key) unique
        // constraint; the recovery lookup finds the winner's reservation.
        given(reservations.findByDriverIdAndIdempotencyKey(driver.getId(), "key-race"))
                .willReturn(Optional.empty(), Optional.of(winners));
        DataIntegrityViolationException unique = new DataIntegrityViolationException(
                "duplicate key", new SQLException("duplicate key", "23505"));
        given(reservations.saveAndFlush(any(Reservation.class))).willThrow(unique);

        var result = service.create(driver.getId(),
                request(now().plusHours(1), now().plusHours(2)), "key-race");

        assertThat(result.created()).isFalse();
        assertThat(result.reservation().code()).isEqualTo("SP-W1NNR");
    }

    @Test
    void codeCollisionRetriesWithFreshCode() {
        stubHappyPath();
        DataIntegrityViolationException unique = new DataIntegrityViolationException(
                "duplicate key", new SQLException("duplicate key", "23505"));
        given(reservations.saveAndFlush(any(Reservation.class)))
                .willThrow(unique)
                .willAnswer((Answer<Reservation>) inv -> inv.getArgument(0));
        OffsetDateTime now = now();

        var result = service.create(driver.getId(), request(now.plusHours(1), now.plusHours(2)), null);

        assertThat(result.created()).isTrue();
        verify(reservations, org.mockito.Mockito.times(2)).saveAndFlush(any(Reservation.class));
    }

    // ---- detail privacy -----------------------------------------------------

    private Reservation confirmedReservation() {
        return new Reservation(space, driver, now().plusHours(1), now().plusHours(3),
                300, 600, "SP-K84D2", null, now());
    }

    @Test
    void driverSeesExactAddressWhileConfirmed() {
        given(reservations.findById(any())).willReturn(Optional.of(confirmedReservation()));

        var detail = service.get(driver.getId(), UUID.randomUUID());

        assertThat(detail.address()).isEqualTo("123 Private Way");
        assertThat(detail.spaceLabel()).isEqualTo("B17");
        assertThat(detail.parkingInstructions()).isEqualTo("Gate code 1234");
        assertThat(detail.code()).isEqualTo("SP-K84D2");
    }

    @Test
    void hostSeesExactAddress() {
        given(reservations.findById(any())).willReturn(Optional.of(confirmedReservation()));

        var detail = service.get(host.getId(), UUID.randomUUID());

        assertThat(detail.address()).isEqualTo("123 Private Way");
    }

    @Test
    void strangerGets403NeverTheAddress() {
        given(reservations.findById(any())).willReturn(Optional.of(confirmedReservation()));
        UUID strangerId = UUID.randomUUID();

        assertThatThrownBy(() -> service.get(strangerId, UUID.randomUUID()))
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(api.getCode()).isEqualTo("NOT_YOUR_RESERVATION");
                });
    }

    @Test
    void driverOfCancelledReservationNoLongerSeesAddress() {
        Reservation cancelled = confirmedReservation();
        cancelled.cancel(now(), CancelledBy.DRIVER);
        given(reservations.findById(any())).willReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.get(driver.getId(), UUID.randomUUID()))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RESERVATION_NOT_ACTIVE"));
    }

    @Test
    void missingReservationIs404() {
        given(reservations.findById(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(driver.getId(), UUID.randomUUID()))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RESERVATION_NOT_FOUND"));
    }

    // ---- cancellation -------------------------------------------------------

    @Test
    void driverCancelsUpcomingReservation() {
        Reservation reservation = confirmedReservation();
        given(reservations.findById(reservation.getId())).willReturn(Optional.of(reservation));
        given(reservations.save(any(Reservation.class)))
                .willAnswer((Answer<Reservation>) inv -> inv.getArgument(0));

        var dto = service.cancel(driver.getId(), reservation.getId());

        assertThat(dto.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledBy()).isEqualTo(CancelledBy.DRIVER);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }

    @Test
    void doubleCancelIsANoopSuccess() {
        Reservation reservation = confirmedReservation();
        reservation.cancel(now(), CancelledBy.DRIVER);
        given(reservations.findById(reservation.getId())).willReturn(Optional.of(reservation));

        var dto = service.cancel(driver.getId(), reservation.getId());

        assertThat(dto.status()).isEqualTo(ReservationStatus.CANCELLED);
        verify(reservations, never()).save(any());
    }

    @Test
    void strangerCannotCancel() {
        Reservation reservation = confirmedReservation();
        given(reservations.findById(reservation.getId())).willReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), reservation.getId()))
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(api.getCode()).isEqualTo("NOT_YOUR_RESERVATION");
                });
    }

    @Test
    void cannotCancelAfterArrival() {
        Reservation started = new Reservation(space, driver, now().minusMinutes(10), now().plusHours(1),
                300, 450, "SP-AAAAA", null, now());
        given(reservations.findById(started.getId())).willReturn(Optional.of(started));

        assertThatThrownBy(() -> service.cancel(driver.getId(), started.getId()))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RESERVATION_STARTED"));
    }

    @Test
    void hostCancelsUpcomingReservationRecordedAsHost() {
        Reservation reservation = confirmedReservation();
        given(reservations.findById(reservation.getId())).willReturn(Optional.of(reservation));
        given(reservations.save(any(Reservation.class)))
                .willAnswer((Answer<Reservation>) inv -> inv.getArgument(0));

        var dto = service.cancel(host.getId(), reservation.getId());

        assertThat(dto.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledBy()).isEqualTo(CancelledBy.HOST);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }

    @Test
    void hostCannotCancelAfterArrival() {
        Reservation started = new Reservation(space, driver, now().minusMinutes(10), now().plusHours(1),
                300, 450, "SP-AAAAA", null, now());
        given(reservations.findById(started.getId())).willReturn(Optional.of(started));

        assertThatThrownBy(() -> service.cancel(host.getId(), started.getId()))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RESERVATION_STARTED"));
    }

    @Test
    void hostDoubleCancelIsANoopSuccess() {
        Reservation reservation = confirmedReservation();
        reservation.cancel(now(), CancelledBy.HOST);
        given(reservations.findById(reservation.getId())).willReturn(Optional.of(reservation));

        var dto = service.cancel(host.getId(), reservation.getId());

        assertThat(dto.status()).isEqualTo(ReservationStatus.CANCELLED);
        verify(reservations, never()).save(any());
    }

    // ---- host arrivals ------------------------------------------------------

    @Test
    void hostSeesTodaysArrivalsWithPrivateDriverIdentity() {
        Reservation reservation = confirmedReservation();
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        given(reservations.findArrivalsBySpace(eq(space.getId()), any(OffsetDateTime.class),
                any(OffsetDateTime.class))).willReturn(List.of(reservation));

        var arrivals = service.arrivalsForSpace(host.getId(), space.getId(), null);

        assertThat(arrivals).hasSize(1);
        var row = arrivals.get(0);
        assertThat(row.code()).isEqualTo("SP-K84D2");
        // First name + last initial only — never the full name or contact.
        assertThat(row.driverName()).isEqualTo("Dan D.");
        assertThat(row.status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void arrivalsQueriesTheUtcDayBounds() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        given(reservations.findArrivalsBySpace(eq(space.getId()), any(OffsetDateTime.class),
                any(OffsetDateTime.class))).willReturn(List.of());

        service.arrivalsForSpace(host.getId(), space.getId(), null);

        var startCaptor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        var endCaptor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reservations).findArrivalsBySpace(eq(space.getId()), startCaptor.capture(),
                endCaptor.capture());
        // Clock is fixed at 2026-09-24T12:00Z: omitted date means that UTC day.
        assertThat(startCaptor.getValue()).isEqualTo(
                OffsetDateTime.of(2026, 9, 24, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(endCaptor.getValue()).isEqualTo(
                OffsetDateTime.of(2026, 9, 25, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void arrivalsForAnExplicitDateUsesThatDay() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        given(reservations.findArrivalsBySpace(eq(space.getId()), any(OffsetDateTime.class),
                any(OffsetDateTime.class))).willReturn(List.of());

        service.arrivalsForSpace(host.getId(), space.getId(), LocalDate.of(2026, 9, 25));

        var startCaptor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reservations).findArrivalsBySpace(eq(space.getId()), startCaptor.capture(),
                any(OffsetDateTime.class));
        assertThat(startCaptor.getValue()).isEqualTo(
                OffsetDateTime.of(2026, 9, 25, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void strangerCannotSeeArrivals() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));

        assertThatThrownBy(() -> service.arrivalsForSpace(UUID.randomUUID(), space.getId(), null))
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(api.getCode()).isEqualTo("NOT_YOUR_SPACE");
                });
    }

    @Test
    void arrivalsForMissingSpaceIs404() {
        given(spaces.findById(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.arrivalsForSpace(host.getId(), UUID.randomUUID(), null))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SPACE_NOT_FOUND"));
    }

    // ---- mine ---------------------------------------------------------------

    @Test
    void mineDefaultsToUpcomingThenPast() {
        Reservation upcoming = confirmedReservation();
        Reservation past = new Reservation(space, driver, now().minusHours(3), now().minusHours(2),
                300, 300, "SP-PAST1", null, now());
        given(reservations.findUpcoming(driver.getId(), ReservationStatus.CONFIRMED, now()))
                .willReturn(List.of(upcoming));
        given(reservations.findPast(driver.getId(), ReservationStatus.CONFIRMED, now()))
                .willReturn(List.of(past));

        var list = service.mine(driver.getId(), null);

        assertThat(list).extracting(r -> r.code()).containsExactly("SP-K84D2", "SP-PAST1");
    }

    @Test
    void mineFilterUpcomingSkipsPast() {
        given(reservations.findUpcoming(driver.getId(), ReservationStatus.CONFIRMED, now()))
                .willReturn(List.of(confirmedReservation()));

        var list = service.mine(driver.getId(), "upcoming");

        assertThat(list).hasSize(1);
        verify(reservations, never()).findPast(any(), any(), any());
    }

    @Test
    void mineRejectsUnknownFilter() {
        assertThatThrownBy(() -> service.mine(driver.getId(), "someday"))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("INVALID_FILTER"));
    }
}
