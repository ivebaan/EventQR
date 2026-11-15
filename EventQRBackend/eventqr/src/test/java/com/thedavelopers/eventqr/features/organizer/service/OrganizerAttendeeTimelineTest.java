package com.thedavelopers.eventqr.features.organizer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.thedavelopers.eventqr.features.events.model.entity.Event;
import com.thedavelopers.eventqr.features.events.repository.EventRepository;
import com.thedavelopers.eventqr.features.idprinting.repository.IdTemplateRepository;
import com.thedavelopers.eventqr.features.notifications.service.NotificationService;
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerDtos.OrganizerAttendeeResponse;
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerDtos.TransactionEntry;
import com.thedavelopers.eventqr.features.organizer.repository.EventStaffAssignmentRepository;
import com.thedavelopers.eventqr.features.registrations.model.entity.EventRegistration;
import com.thedavelopers.eventqr.features.registrations.repository.EventRegistrationRepository;
import com.thedavelopers.eventqr.features.registrations.service.RegistrationService;
import com.thedavelopers.eventqr.features.rewards.repository.PointTransactionRepository;
import com.thedavelopers.eventqr.features.rewards.repository.RewardRedemptionRepository;
import com.thedavelopers.eventqr.features.scanning.model.entity.ScanPurpose;
import com.thedavelopers.eventqr.features.scanning.repository.ScanPurposeRepository;
import com.thedavelopers.eventqr.features.transactions.model.entity.TransactionLog;
import com.thedavelopers.eventqr.features.transactions.repository.TransactionLogRepository;
import com.thedavelopers.eventqr.features.transactions.repository.TransactionRuleRepository;
import com.thedavelopers.eventqr.features.users.model.entity.UserProfile;
import com.thedavelopers.eventqr.features.users.repository.UserProfileRepository;
import com.thedavelopers.eventqr.shared.constants.AccountRole;
import com.thedavelopers.eventqr.shared.constants.EventStatus;
import com.thedavelopers.eventqr.shared.constants.RegistrationStatus;
import com.thedavelopers.eventqr.shared.constants.ScanPurposeCode;
import com.thedavelopers.eventqr.shared.constants.TransactionResult;
import com.thedavelopers.eventqr.shared.constants.TransactionType;
import com.thedavelopers.eventqr.shared.security.UserTokenRevocationChecker;

import jakarta.persistence.EntityManager;

/** Attendee timeline rows carry the custom scan purpose name for booth/session visits. */
@ExtendWith(MockitoExtension.class)
class OrganizerAttendeeTimelineTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID organizerId = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID boothAPurposeId = UUID.randomUUID();
    private EventRegistrationRepository registrations;
    private TransactionLogRepository transactions;
    private ScanPurposeRepository scanPurposes;
    private OrganizerService service;
    private EventRegistration aliceRegistration;

    @BeforeEach
    void setUp() {
        EventRepository events = mock(EventRepository.class);
        UserProfileRepository users = mock(UserProfileRepository.class);
        registrations = mock(EventRegistrationRepository.class);
        transactions = mock(TransactionLogRepository.class);
        scanPurposes = mock(ScanPurposeRepository.class);
        service = new OrganizerService(events, registrations, transactions,
                scanPurposes, mock(TransactionRuleRepository.class), mock(RewardRedemptionRepository.class),
                mock(PointTransactionRepository.class), mock(EventStaffAssignmentRepository.class), users,
                mock(IdTemplateRepository.class), mock(NotificationService.class), mock(RegistrationService.class),
                mock(UserTokenRevocationChecker.class));
        Event event = new Event();
        event.setId(eventId);
        event.setOrganizerUserId(organizerId);
        event.setStatus(EventStatus.ACTIVE);
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(users.findById(organizerId)).thenReturn(Optional.of(new UserProfile()));
        aliceRegistration = registration(alice, "alice");
        when(registrations.findByEventId(eventId)).thenReturn(List.of(aliceRegistration));
        when(scanPurposes.findByEventId(eventId)).thenReturn(List.of(purpose(boothAPurposeId, "Booth A", ScanPurposeCode.BOOTH_VISIT)));
    }

    private EventRegistration registration(UUID attendee, String name) {
        EventRegistration r = new EventRegistration();
        r.setId(UUID.randomUUID());
        r.setEventId(eventId);
        r.setAttendeeUserId(attendee);
        r.setAttendeeName(name);
        r.setAttendeeEmail(name + "@example.com");
        r.setStatus(RegistrationStatus.REGISTERED);
        r.setRegisteredAt(Instant.now());
        return r;
    }

    private ScanPurpose purpose(UUID id, String name, ScanPurposeCode code) {
        ScanPurpose purpose = new ScanPurpose();
        purpose.setId(id);
        purpose.setEventId(eventId);
        purpose.setName(name);
        purpose.setCode(code);
        return purpose;
    }

    private TransactionLog log(EventRegistration registration, TransactionType type, UUID scanPurposeId, String scannedAt) {
        TransactionLog log = new TransactionLog();
        log.setEventId(eventId);
        log.setAttendeeUserId(registration.getAttendeeUserId());
        log.setRegistrationId(registration.getId());
        log.setQrCredentialId(UUID.randomUUID());
        log.setScanPurposeId(scanPurposeId);
        log.setTransactionType(type);
        log.setTransactionResult(TransactionResult.APPROVED);
        log.setScannedAt(Instant.parse(scannedAt));
        return log;
    }

    @Test
    void boothScanTimelineRowCarriesCustomPurposeName() {
        when(transactions.findByEventId(eventId)).thenReturn(List.of(
                log(aliceRegistration, TransactionType.BOOTH_VISIT, boothAPurposeId, "2026-01-01T10:00:00Z")));

        List<OrganizerAttendeeResponse> result = service.attendees(organizerId, eventId, AccountRole.ORGANIZER);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).recentTransactions())
                .extracting(TransactionEntry::type, TransactionEntry::scanPurposeName)
                .containsExactly(tuple("BOOTH_VISIT", "Booth A"));
        assertThat(result.get(0).recentTransactions().get(0).timestamp()).isNotBlank();
    }

    @Test
    void timelineRowWithoutPurposeKeepsTypeAndTimestamp() {
        when(transactions.findByEventId(eventId)).thenReturn(List.of(
                log(aliceRegistration, TransactionType.ATTENDANCE, null, "2026-01-01T10:00:00Z")));

        List<OrganizerAttendeeResponse> result = service.attendees(organizerId, eventId, AccountRole.ORGANIZER);

        TransactionEntry entry = result.get(0).recentTransactions().get(0);
        assertThat(entry.type()).isEqualTo("ATTENDANCE");
        assertThat(entry.timestamp()).isNotBlank();
        assertThat(entry.scanPurposeName()).isNull();
    }

    @Test
    void timelineRowWithUnknownPurposeIdYieldsNullName() {
        when(transactions.findByEventId(eventId)).thenReturn(List.of(
                log(aliceRegistration, TransactionType.BOOTH_VISIT, UUID.randomUUID(), "2026-01-01T10:00:00Z")));

        List<OrganizerAttendeeResponse> result = service.attendees(organizerId, eventId, AccountRole.ORGANIZER);

        assertThat(result.get(0).recentTransactions().get(0).scanPurposeName()).isNull();
    }

    @Test
    void timelineRowWithBlankPurposeNameYieldsNullName() {
        when(scanPurposes.findByEventId(eventId)).thenReturn(List.of(purpose(boothAPurposeId, "   ", ScanPurposeCode.BOOTH_VISIT)));
        when(transactions.findByEventId(eventId)).thenReturn(List.of(
                log(aliceRegistration, TransactionType.BOOTH_VISIT, boothAPurposeId, "2026-01-01T10:00:00Z")));

        List<OrganizerAttendeeResponse> result = service.attendees(organizerId, eventId, AccountRole.ORGANIZER);

        assertThat(result.get(0).recentTransactions().get(0).scanPurposeName()).isNull();
    }

    @Test
    void nonBoothLogIsUnaffectedByBoothPurposeName() {
        when(transactions.findByEventId(eventId)).thenReturn(List.of(
                log(aliceRegistration, TransactionType.ATTENDANCE, null, "2026-01-01T10:00:00Z"),
                log(aliceRegistration, TransactionType.SESSION_VISIT, boothAPurposeId, "2026-01-01T11:00:00Z")));

        List<OrganizerAttendeeResponse> result = service.attendees(organizerId, eventId, AccountRole.ORGANIZER);

        assertThat(result.get(0).recentTransactions())
                .extracting(TransactionEntry::type, TransactionEntry::scanPurposeName)
                .containsExactly(tuple("SESSION_VISIT", "Booth A"), tuple("ATTENDANCE", null));
    }

    @Test
    void scanPurposesAreFetchedOncePerRequestForAttendeesAndLogs() {
        EventRegistration bobRegistration = registration(bob, "bob");
        when(registrations.findByEventId(eventId)).thenReturn(List.of(aliceRegistration, bobRegistration));
        when(transactions.findByEventId(eventId)).thenReturn(List.of(
                log(aliceRegistration, TransactionType.BOOTH_VISIT, boothAPurposeId, "2026-01-01T10:00:00Z"),
                log(aliceRegistration, TransactionType.SESSION_VISIT, boothAPurposeId, "2026-01-01T11:00:00Z"),
                log(bobRegistration, TransactionType.BOOTH_VISIT, boothAPurposeId, "2026-01-01T12:00:00Z")));

        List<OrganizerAttendeeResponse> result = service.attendees(organizerId, eventId, AccountRole.ORGANIZER);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).recentTransactions()).extracting(TransactionEntry::scanPurposeName)
                .containsOnly("Booth A");
        assertThat(result.get(1).recentTransactions()).extracting(TransactionEntry::scanPurposeName)
                .containsOnly("Booth A");
        verify(scanPurposes, times(1)).findByEventId(eventId);
    }

    @Test
    void updateAttendeeStatusTimelineCarriesPurposeName() {
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
        when(registrations.updateStatusIfCurrent(aliceRegistration.getId(), "REGISTERED", "NO_SHOW")).thenReturn(1);
        when(registrations.findById(aliceRegistration.getId())).thenReturn(Optional.of(aliceRegistration));
        when(transactions.findByEventId(eventId)).thenReturn(List.of(
                log(aliceRegistration, TransactionType.BOOTH_VISIT, boothAPurposeId, "2026-01-01T10:00:00Z")));

        OrganizerAttendeeResponse result = service.updateAttendeeStatus(organizerId, eventId, AccountRole.ORGANIZER,
                alice, RegistrationStatus.NO_SHOW.name());

        assertThat(result.recentTransactions())
                .extracting(TransactionEntry::type, TransactionEntry::scanPurposeName)
                .containsExactly(tuple("BOOTH_VISIT", "Booth A"));
        verify(registrations).updateStatusIfCurrent(any(), eq("REGISTERED"), eq("NO_SHOW"));
    }
}
