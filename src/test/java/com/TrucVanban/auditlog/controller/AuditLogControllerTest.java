package com.TrucVanban.auditlog.controller;

import com.TrucVanban.auditlog.dto.request.AuditLogFilterRequest;
import com.TrucVanban.auditlog.dto.response.AuditLogPageResponse;
import com.TrucVanban.auditlog.dto.response.AuditLogResponse;
import com.TrucVanban.auditlog.dto.response.AuditPageableResponse;
import com.TrucVanban.auditlog.dto.response.AuditTimelineItemResponse;
import com.TrucVanban.auditlog.dto.response.AuditTimelinePageResponse;
import com.TrucVanban.auditlog.service.AuditQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogControllerTest {
    @Test
    void auditLogResponseOmitsNullFields() throws Exception {
        UUID eventId = UUID.randomUUID();
        AuditLogResponse auditLog = AuditLogResponse.builder()
                .eventId(eventId)
                .result("SUCCESS")
                .build();

        String json = new ObjectMapper().writeValueAsString(auditLog);

        assertThat(json).contains(eventId.toString(), "\"result\":\"SUCCESS\"");
        assertThat(json).doesNotContain("errorCode", "beforeState", "afterState");
    }

    @Test
    void searchEndpointUsesQueryFilter() {
        AuditQueryService service = mock(AuditQueryService.class);
        AuditLogController controller = new AuditLogController(service);
        AuditLogFilterRequest filter = new AuditLogFilterRequest();
        filter.setDocumentCode("DOC-001");
        filter.setTransactionCode("TXN-001");
        var page = new AuditLogPageResponse(
                List.of(), new AuditPageableResponse(0, 20), 0, 0, true, true);
        when(service.search(filter)).thenReturn(page);

        var response = controller.search(filter);

        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData()).isEqualTo(page);
        verify(service).search(filter);
    }

    @Test
    void timelineEndpointReturnsLeanList() {
        AuditQueryService service = mock(AuditQueryService.class);
        AuditLogController controller = new AuditLogController(service);
        var timeline = new AuditTimelinePageResponse(
                "DOC-001",
                "Cơ quan gửi",
                null,
                null,
                null,
                List.of(),
                List.of(new AuditTimelineItemResponse(
                        null, "DOC-001", null, "Cập nhật văn bản", "Văn thư",
                        null, "SUCCESS", "Cập nhật văn bản", null)),
                0, 20, 1, 1);
        when(service.getTimeline("DOC-001", 0, 20)).thenReturn(timeline);

        var response = controller.getTimeline("DOC-001", 0, 20);

        assertThat(response.getBody().getData()).isEqualTo(timeline);
        verify(service).getTimeline("DOC-001", 0, 20);
    }

    @Test
    void timelineEndpointAllowsMissingDocumentCode() throws Exception {
        AuditQueryService service = mock(AuditQueryService.class);
        AuditLogController controller = new AuditLogController(service);
        var timeline = new AuditTimelinePageResponse(
                null, null, null, null, null, null,
                List.of(), 0, 20, 0, 0);
        when(service.getTimeline(null, 0, 20)).thenReturn(timeline);

        var response = controller.getTimeline(null, 0, 20);
        String json = new ObjectMapper().writeValueAsString(response.getBody().getData());

        assertThat(response.getBody().getData()).isEqualTo(timeline);
        assertThat(json).doesNotContain(
                "documentCode",
                "senderOrganization",
                "signingOrganizations",
                "receiverOrganizations");
        verify(service).getTimeline(null, 0, 20);
    }
}
