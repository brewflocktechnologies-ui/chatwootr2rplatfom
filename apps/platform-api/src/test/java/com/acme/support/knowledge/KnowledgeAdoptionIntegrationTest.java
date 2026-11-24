package com.acme.support.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.support.AbstractIntegrationTest;
import com.acme.support.shared.UpstreamException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class KnowledgeAdoptionIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    R2rClient r2r;

    @Autowired
    KnowledgeService knowledge;

    @BeforeEach
    void setUp() {
        resetPlatformState();
        org.mockito.Mockito.reset(r2r);
    }

    @Test
    void conflictFromR2rAdoptsExistingDocumentWithoutCreatingADuplicate() {
        UUID existing = UUID.fromString("1a3e2ef2-25e1-5fbf-af88-360ac28049a8");
        when(r2r.uploadDocument(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new UpstreamException("r2r", 409, false, "r2r uploadDocument failed with HTTP 409", null));
        when(r2r.findInCollection(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new R2rClient.ExistingDocument(existing, "success")));
        when(r2r.documentStatus(existing)).thenReturn("success");

        byte[] bytes = "# Policy\n\nStandard delivery: 3-5 business days after dispatch.\n".getBytes(StandardCharsets.UTF_8);
        KnowledgeService.Upload result = knowledge.upload("acme-support-policy.md", bytes);

        assertThat(result.created()).isTrue();
        assertThat(result.record().ingestionStatus()).isEqualTo("READY");
        verify(r2r, times(1)).uploadDocument(anyString(), any(), anyString(), anyString(), anyString());
        assertThat(jdbc.sql("select r2r_document_id from knowledge_source where file_name = 'acme-support-policy.md'")
                .query(UUID.class).single()).isEqualTo(existing);
    }

    @Test
    void conflictWithNoMatchStaysFailedInsteadOfGuessing() {
        when(r2r.uploadDocument(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new UpstreamException("r2r", 409, false, "r2r uploadDocument failed with HTTP 409", null));
        when(r2r.findInCollection(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        KnowledgeService.Upload result = knowledge.upload("other.txt",
                "unrelated text".getBytes(StandardCharsets.UTF_8));

        assertThat(result.record().ingestionStatus()).isEqualTo("FAILED");
    }
}
