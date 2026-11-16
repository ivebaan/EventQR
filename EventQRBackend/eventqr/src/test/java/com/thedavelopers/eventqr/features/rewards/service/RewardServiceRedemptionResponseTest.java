package com.thedavelopers.eventqr.features.rewards.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thedavelopers.eventqr.features.events.repository.EventRepository;
import com.thedavelopers.eventqr.features.notifications.service.NotificationService;
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRedemptionResponse;
import com.thedavelopers.eventqr.features.rewards.model.entity.RewardRedemption;
import com.thedavelopers.eventqr.features.rewards.repository.AttendeePointBalanceRepository;
import com.thedavelopers.eventqr.features.rewards.repository.PointTransactionRepository;
import com.thedavelopers.eventqr.features.rewards.repository.RewardRedemptionRepository;
import com.thedavelopers.eventqr.features.rewards.repository.RewardRepository;
import com.thedavelopers.eventqr.features.scanning.repository.ScanPurposeRepository;
import com.thedavelopers.eventqr.shared.constants.RedemptionStatus;
import com.thedavelopers.eventqr.shared.interfaces.AttendeeDirectoryPort;
import com.thedavelopers.eventqr.shared.interfaces.RegistrationLookupPort;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RewardServiceRedemptionResponseTest {

    @Mock private AttendeePointBalanceRepository attendeePointBalanceRepository;
    @Mock private PointTransactionRepository pointTransactionRepository;
    @Mock private RewardRepository rewardRepository;
    @Mock private RewardRedemptionRepository rewardRedemptionRepository;
    @Mock private EventRepository eventRepository;
    @Mock private ScanPurposeRepository scanPurposeRepository;
    @Mock private NotificationService notificationService;
    @Mock private AttendeeDirectoryPort attendeeDirectoryPort;
    @Mock private DuplicateRewardClaimChecker duplicateRewardClaimChecker;
    @Mock private RegistrationLookupPort registrationLookupPort;
    @InjectMocks private RewardService service;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private RewardRedemption redemption(UUID eventId, UUID attendeeUserId, UUID scanLogId) {
        RewardRedemption redemption = new RewardRedemption();
        redemption.setId(UUID.randomUUID());
        redemption.setEventId(eventId);
        redemption.setAttendeeUserId(attendeeUserId);
        redemption.setRewardId(UUID.randomUUID());
        redemption.setPointsSpent(10);
        redemption.setStatus(RedemptionStatus.REDEEMED);
        redemption.setRedeemedAt(Instant.now());
        redemption.setRedemptionScanLogId(scanLogId);
        return redemption;
    }

    @Test
    void claimedRewardsSerializesScanLinkForScanRedemptionAndNullForDirectClaim() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID attendeeUserId = UUID.randomUUID();
        UUID scanLogId = UUID.randomUUID();
        when(rewardRedemptionRepository.findByEventIdAndAttendeeUserId(eventId, attendeeUserId)).thenReturn(List.of(
                redemption(eventId, attendeeUserId, scanLogId),
                redemption(eventId, attendeeUserId, null)));

        List<RewardRedemptionResponse> responses = service.findRedemptions(eventId, attendeeUserId);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(responses));
        assertThat(json.get(0).get("redemptionScanLogId").asText()).isEqualTo(scanLogId.toString());
        assertThat(json.get(1).get("redemptionScanLogId").isNull()).isTrue();
    }

    @Test
    void organizerHistorySerializesScanLink() {
        UUID eventId = UUID.randomUUID();
        UUID attendeeUserId = UUID.randomUUID();
        UUID scanLogId = UUID.randomUUID();
        when(rewardRedemptionRepository.findByEventId(eventId))
                .thenReturn(List.of(redemption(eventId, attendeeUserId, scanLogId)));

        List<RewardRedemptionResponse> responses = service.findRedemptions(eventId);

        assertThat(responses).singleElement()
                .extracting(RewardRedemptionResponse::redemptionScanLogId)
                .isEqualTo(scanLogId);
    }

    // --- findMyRedemptions: one call, all events, caller's rows only -------

    @Test
    void myRedemptionsQueriesTheCallerOnlyAndCarriesEventIdPerRow() throws Exception {
        UUID callerId = UUID.randomUUID();
        UUID otherAttendeeId = UUID.randomUUID();
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        when(rewardRedemptionRepository.findByAttendeeUserIdOrderByCreatedAtDescIdDesc(callerId))
                .thenReturn(List.of(redemption(firstEventId, callerId, UUID.randomUUID()),
                        redemption(secondEventId, callerId, null)));
        // A different attendee's history stays out of reach even if the repository were asked for it.
        when(rewardRedemptionRepository.findByAttendeeUserIdOrderByCreatedAtDescIdDesc(otherAttendeeId))
                .thenReturn(List.of(redemption(firstEventId, otherAttendeeId, null)));

        List<RewardRedemptionResponse> responses = service.findMyRedemptions(callerId);

        verify(rewardRedemptionRepository).findByAttendeeUserIdOrderByCreatedAtDescIdDesc(callerId);
        verify(rewardRedemptionRepository, never()).findByAttendeeUserIdOrderByCreatedAtDescIdDesc(otherAttendeeId);
        assertThat(responses).hasSize(2)
                .allSatisfy(row -> assertThat(row.attendeeUserId()).isEqualTo(callerId))
                .extracting(RewardRedemptionResponse::eventId)
                .containsExactly(firstEventId, secondEventId);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(responses));
        assertThat(json.get(0).get("eventId").asText()).isEqualTo(firstEventId.toString());
        assertThat(json.get(0).get("attendeeUserId").asText()).isEqualTo(callerId.toString());
    }

    @Test
    void myRedemptionsWithoutAnyRedemptionIsAnEmptyList() {
        UUID callerId = UUID.randomUUID();
        when(rewardRedemptionRepository.findByAttendeeUserIdOrderByCreatedAtDescIdDesc(callerId)).thenReturn(List.of());

        assertThat(service.findMyRedemptions(callerId)).isEmpty();
    }
}
