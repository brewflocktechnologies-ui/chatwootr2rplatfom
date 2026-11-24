-- Platform-owned state only. Chatwoot and R2R tables are never touched.

create table tenant (
    tenant_key   text primary key,
    display_name text        not null,
    created_at   timestamptz not null default now()
);

insert into tenant (tenant_key, display_name) values ('acme-demo', 'Acme Demo (FICTIONAL)');

-- Non-secret identifiers only. Tokens and secrets stay in environment configuration.
create table tenant_integrations (
    tenant_key          text primary key references tenant (tenant_key),
    chatwoot_account_id bigint      not null,
    chatwoot_inbox_id   bigint      not null,
    chatwoot_team_id    bigint      not null,
    r2r_collection_id   uuid        not null,
    updated_at          timestamptz not null default now()
);

create table knowledge_source (
    id               uuid primary key default gen_random_uuid(),
    tenant_key       text        not null references tenant (tenant_key),
    file_name        text        not null,
    media_type       text        not null,
    size_bytes       integer     not null check (size_bytes > 0 and size_bytes <= 2097152),
    sha256           char(64)    not null,
    -- Raw file kept only so a FAILED ingestion can be retried. Never sent to logs.
    content          bytea       not null,
    r2r_document_id  uuid,
    ingestion_status text        not null check (ingestion_status in ('PENDING', 'INGESTING', 'READY', 'FAILED')),
    last_error       text,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    unique (tenant_key, sha256)
);

create table webhook_inbox (
    id                        uuid primary key default gen_random_uuid(),
    tenant_key                text        not null references tenant (tenant_key),
    dedupe_key                text        not null unique,
    delivery_id               text,
    event_kind                text        not null check (event_kind in ('CUSTOMER_MESSAGE', 'HUMAN_REPLY')),
    chatwoot_conversation_id  bigint      not null,
    chatwoot_message_id       bigint      not null,
    -- Minimum necessary: the customer text is needed for the turn. Never logged.
    customer_text             text,
    status                    text        not null check (status in ('QUEUED', 'PROCESSING', 'RETRY', 'PROCESSED', 'FAILED')),
    outcome                   text,
    attempts                  integer     not null default 0,
    next_attempt_at           timestamptz not null default now(),
    lease_owner               text,
    lease_expires_at          timestamptz,
    last_error               text,
    received_at               timestamptz not null default now(),
    updated_at                timestamptz not null default now()
);
create index webhook_inbox_claim_idx on webhook_inbox (status, next_attempt_at, received_at);
create index webhook_inbox_conversation_idx on webhook_inbox (chatwoot_conversation_id);

create table conversation_control (
    chatwoot_conversation_id bigint primary key,
    tenant_key               text        not null references tenant (tenant_key),
    state                    text        not null check (state in ('AI_ACTIVE', 'HANDOFF_PENDING', 'HUMAN_ACTIVE', 'RESOLVED')),
    version                  bigint      not null default 0,
    handoff_generation       integer     not null default 0,
    last_processed_message_id bigint,
    updated_at               timestamptz not null default now()
);

create table handoff_record (
    id                       uuid primary key default gen_random_uuid(),
    tenant_key               text        not null references tenant (tenant_key),
    chatwoot_conversation_id bigint      not null,
    handoff_generation       integer     not null,
    -- One handoff per conversation per generation: prevents duplicate private notes.
    handoff_key              text        not null unique,
    reason                   text        not null,
    note_body                text        not null,
    status                   text        not null check (status in ('PENDING', 'NOTED', 'TRANSFERRED', 'RESUMED')),
    note_message_id          bigint,
    last_error               text,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    unique (chatwoot_conversation_id, handoff_generation)
);

create table agent_execution (
    id                       uuid primary key default gen_random_uuid(),
    webhook_inbox_id         uuid        not null unique references webhook_inbox (id),
    chatwoot_conversation_id bigint      not null,
    chatwoot_message_id      bigint      not null,
    correlation_id           text        not null,
    state                    text        not null check (state in ('RUNNING', 'SENDING', 'SEND_UNKNOWN', 'SENT', 'HANDED_OFF', 'DISCARDED', 'SKIPPED', 'FAILED')),
    reason_code              text,
    retrieval_chunks         integer,
    top_score                double precision,
    source_titles            text,
    outbound_message_id      bigint,
    model                    text,
    failure_reason           text,
    started_at               timestamptz not null default now(),
    finished_at              timestamptz
);

create table admin_audit (
    id          bigint generated always as identity primary key,
    at          timestamptz not null default now(),
    actor       text        not null,
    action      text        not null,
    target      text        not null,
    detail      text
);
