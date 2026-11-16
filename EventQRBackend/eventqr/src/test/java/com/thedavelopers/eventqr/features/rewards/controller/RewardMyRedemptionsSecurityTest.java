package com.thedavelopers.eventqr.features.rewards.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.thedavelopers.eventqr.features.monitor.support.PoisonedDataSource;
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRedemptionResponse;
import com.thedavelopers.eventqr.features.rewards.service.RewardService;
import com.thedavelopers.eventqr.shared.constants.AccountRole;
import com.thedavelopers.eventqr.shared.constants.RedemptionStatus;
import com.thedavelopers.eventqr.shared.security.JwtService;
import com.thedavelopers.eventqr.shared.security.RevokedTokenStore;
import com.thedavelopers.eventqr.shared.security.UserTokenRevocationChecker;

/**
 * Full context (real security filter chains) for GET /api/v1/rewards/redemptions/my-redemptions: the route needs a
 * user JWT, takes the caller from it, and the literal segment is matched ahead of /redemptions/{eventId}.
 * RewardService is mocked and the DataSource is poisoned, so nothing here touches a database.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
                "jwt.secret=testtesttesttesttesttesttesttesttesttesttesttest",
                "brevo.api.key=test-api-key-not-real",
                "brevo.sender.email=noreply@example.test"
        })
@AutoConfigureMockMvc
class RewardMyRedemptionsSecurityTest {

    private static final String URL = "/api/v1/rewards/redemptions/my-redemptions";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @MockitoBean
    private RewardService rewardService;
    @MockitoBean
    private UserTokenRevocationChecker revocationChecker;
    @MockitoBean
    private RevokedTokenStore revokedTokenStore;

    private final UUID callerId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clearInvocations(rewardService, revocationChecker, revokedTokenStore);
        given(revocationChecker.isAccessAllowed(any(), any(), any())).willReturn(true);
        given(rewardService.findMyRedemptions(any())).willReturn(List.of());
    }

    private String tokenFor(UUID userId, AccountRole role) {
        return "Bearer " + jwtService.createToken(userId, "attendee@example.test", role);
    }

    @Test
    @DisplayName("no token and an invalid token are 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required: missing or invalid token"));
        mockMvc.perform(get(URL).header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
        verify(rewardService, never()).findMyRedemptions(any());
    }

    @Test
    @DisplayName("the caller's own attendee JWT is served the caller's rows only")
    void servesTheCallersOwnRows() throws Exception {
        given(rewardService.findMyRedemptions(callerId)).willReturn(List.of(
                new RewardRedemptionResponse(UUID.randomUUID(), eventId, callerId, UUID.randomUUID(), 10,
                        RedemptionStatus.REDEEMED, Instant.now(), null, null)));

        mockMvc.perform(get(URL).header("Authorization", tokenFor(callerId, AccountRole.ATTENDEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].attendeeUserId").value(callerId.toString()))
                .andExpect(jsonPath("$.data[0].eventId").value(eventId.toString()));

        verify(rewardService).findMyRedemptions(callerId);
    }

    @Test
    @DisplayName("\"my-redemptions\" is matched literally, never parsed as the {eventId} UUID")
    void literalSegmentIsNotSwallowedByThePathVariable() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", tokenFor(callerId, AccountRole.ATTENDEE)))
                .andExpect(status().isOk());

        verify(rewardService).findMyRedemptions(callerId);
        verify(rewardService, never()).findRedemptions(any());
        verify(rewardService, never()).findRedemptions(any(), any());

        // The variable route is still mapped: a non-UUID segment there is a 400, so the 200 above can only
        // have come from the literal route winning over it.
        mockMvc.perform(get("/api/v1/rewards/redemptions/not-a-uuid")
                        .header("Authorization", tokenFor(callerId, AccountRole.ATTENDEE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an empty history is 200 with an empty array")
    void emptyHistoryIsOk() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", tokenFor(callerId, AccountRole.ATTENDEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @TestConfiguration
    static class PoisonedDataSourceConfiguration {

        @Bean
        @Primary
        DataSource dataSource() {
            return new PoisonedDataSource();
        }
    }
}
