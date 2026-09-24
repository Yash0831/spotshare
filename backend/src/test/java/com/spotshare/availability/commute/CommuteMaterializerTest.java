package com.spotshare.availability.commute;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The hourly sweep fans out to one transaction per space with active schedules. */
@ExtendWith(MockitoExtension.class)
class CommuteMaterializerTest {

    @Mock
    private CommuteScheduleRepository schedules;

    @Mock
    private CommuteService commutes;

    @Test
    void sweep_materializesEachActiveSpaceOnce() {
        CommuteMaterializer materializer = new CommuteMaterializer(schedules, commutes);
        UUID spaceA = UUID.randomUUID();
        UUID spaceB = UUID.randomUUID();
        given(schedules.findActiveSpaceIds()).willReturn(List.of(spaceA, spaceB));

        materializer.materializeAll();

        verify(commutes).materializeSpace(spaceA);
        verify(commutes).materializeSpace(spaceB);
    }

    @Test
    void sweep_withNoActiveSchedules_doesNothing() {
        CommuteMaterializer materializer = new CommuteMaterializer(schedules, commutes);
        given(schedules.findActiveSpaceIds()).willReturn(List.of());

        materializer.materializeAll();

        verify(commutes, never()).materializeSpace(any());
    }
}
