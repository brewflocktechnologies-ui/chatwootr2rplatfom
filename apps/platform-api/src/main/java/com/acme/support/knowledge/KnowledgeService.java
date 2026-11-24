package com.acme.support.knowledge;

import com.acme.support.shared.ConflictException;
import com.acme.support.shared.NotFoundException;
import com.acme.support.shared.UpstreamException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Tenant knowledge documents: validation, platform metadata, and ingestion into the tenant R2R collection. */
@Service
public class KnowledgeService {

    static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 _().-]{0,119}\\.(md|txt|pdf)$");
    private static final String TENANT = "acme-demo";

    private final JdbcClient jdbc;
    private final R2rClient r2r;

    public KnowledgeService(JdbcClient jdbc, R2rClient r2r) {
        this.jdbc = jdbc;
        this.r2r = r2r;
    }

    public record KnowledgeView(UUID id, String fileName, String mediaType, long sizeBytes, String sha256,
                                String ingestionStatus, UUID r2rDocumentId, String lastError, Instant updatedAt) {
    }

    public List<KnowledgeView> list() {
        return jdbc.sql("""
                select id, file_name, media_type, size_bytes, sha256, ingestion_status, r2r_document_id, last_error, updated_at
                from knowledge_source where tenant_key = :t order by updated_at desc
                """).param("t", TENANT).query(KnowledgeService::map).list();
    }

    /** Returns the stored record. The boolean is true when a new record was created (false for an identical file). */
    public Upload upload(String originalName, byte[] bytes) {
        String name = validateName(originalName);
        String mediaType = mediaTypeFor(name);
        validateContent(name, bytes);
        String sha = sha256Hex(bytes);
        Optional<KnowledgeView> existing = find(sha);
        if (existing.isPresent()) {
            return new Upload(existing.get(), false);
        }
        UUID id = jdbc.sql("""
                insert into knowledge_source (tenant_key, file_name, media_type, size_bytes, sha256, content, ingestion_status)
                values (:t, :n, :m, :s, :h, :c, 'PENDING') returning id
                """).param("t", TENANT).param("n", name).param("m", mediaType).param("s", bytes.length)
                .param("h", sha).param("c", bytes).query(UUID.class).single();
        ingest(id, name, bytes, mediaType, null);
        return new Upload(get(id), true);
    }

    /** Re-ingests a FAILED document. Idempotent: a non-FAILED document is refused, not re-uploaded. */
    public KnowledgeView retry(UUID id) {
        KnowledgeView current = get(id);
        if (!"FAILED".equals(current.ingestionStatus())) {
            throw new ConflictException("only FAILED documents can be retried; current status " + current.ingestionStatus());
        }
        byte[] content = jdbc.sql("select content from knowledge_source where id = :id")
                .param("id", id).query(byte[].class).single();
        // If R2R already accepted the document, only re-check its status instead of uploading a second copy.
        ingest(id, current.fileName(), content, current.mediaType(), current.r2rDocumentId());
        return get(id);
    }

    public record Upload(KnowledgeView record, boolean created) {
    }

    private void ingest(UUID id, String name, byte[] content, String mediaType, UUID existingDocId) {
        jdbc.sql("update knowledge_source set ingestion_status = 'INGESTING', updated_at = now() where id = :id")
                .param("id", id).update();
        try {
            UUID docId = existingDocId != null ? existingDocId : uploadOrAdopt(name, content, mediaType, sha256Hex(content));
            jdbc.sql("update knowledge_source set r2r_document_id = :d, updated_at = now() where id = :id")
                    .param("d", docId).param("id", id).update();
            String status = r2r.documentStatus(docId);
            if ("success".equals(status)) {
                setStatus(id, "READY", null);
            } else {
                setStatus(id, "FAILED", "R2R ingestion status: " + status);
            }
        } catch (UpstreamException e) {
            setStatus(id, "FAILED", e.getMessage());
        }
    }

    /** A 409 means R2R already holds this document in the tenant collection: adopt it instead of duplicating. */
    private UUID uploadOrAdopt(String name, byte[] content, String mediaType, String sha) {
        try {
            return r2r.uploadDocument(name, content, mediaType, name, sha);
        } catch (UpstreamException e) {
            if (e.status() != 409) {
                throw e;
            }
            return r2r.findInCollection(name, name, sha).orElseThrow(() -> e).id();
        }
    }

    private void setStatus(UUID id, String status, String error) {
        jdbc.sql("update knowledge_source set ingestion_status = :s, last_error = :e, updated_at = now() where id = :id")
                .param("s", status).param("e", error).param("id", id).update();
    }

    private Optional<KnowledgeView> find(String sha) {
        return jdbc.sql("""
                select id, file_name, media_type, size_bytes, sha256, ingestion_status, r2r_document_id, last_error, updated_at
                from knowledge_source where tenant_key = :t and sha256 = :h
                """).param("t", TENANT).param("h", sha).query(KnowledgeService::map).optional();
    }

    private KnowledgeView get(UUID id) {
        return jdbc.sql("""
                select id, file_name, media_type, size_bytes, sha256, ingestion_status, r2r_document_id, last_error, updated_at
                from knowledge_source where id = :id
                """).param("id", id).query(KnowledgeService::map).optional()
                .orElseThrow(() -> new NotFoundException("knowledge document not found"));
    }

    static String validateName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("file name is required");
        }
        if (originalName.contains("/") || originalName.contains("\\") || originalName.contains("..")) {
            throw new IllegalArgumentException("file name must not contain path separators");
        }
        if (!SAFE_NAME.matcher(originalName).matches()) {
            throw new IllegalArgumentException("file name must be letters, digits, space, _ . ( ) - and end in .md, .txt or .pdf");
        }
        return originalName;
    }

    static String mediaTypeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        throw new IllegalArgumentException("unsupported file type");
    }

    /** Magic-byte and encoding checks. The client-declared content type is never trusted. */
    static void validateContent(String name, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("file exceeds 2 MiB");
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw new IllegalArgumentException("file content is not a PDF");
            }
            return;
        }
        for (byte b : bytes) {
            if (b == 0) {
                throw new IllegalArgumentException("text file contains NUL bytes");
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("text file is not valid UTF-8");
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KnowledgeView map(ResultSet rs, int n) throws SQLException {
        return new KnowledgeView(
                rs.getObject("id", UUID.class),
                rs.getString("file_name"),
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256").trim(),
                rs.getString("ingestion_status"),
                rs.getObject("r2r_document_id", UUID.class),
                rs.getString("last_error"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());
    }
}
