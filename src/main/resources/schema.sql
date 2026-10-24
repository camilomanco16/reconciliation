-- Esquema de conciliación de pagos.
-- Compatible con H2 (MODE=Oracle) y lo más cercano posible a Oracle:
-- se usan VARCHAR2, NUMBER, TIMESTAMP y secuencias en lugar de IDENTITY.

CREATE TABLE PAYMENT (
    id           NUMBER         NOT NULL,
    external_ref VARCHAR2(40)   NOT NULL,
    status       VARCHAR2(20)   NOT NULL,
    amount       NUMBER(18,2)   NOT NULL,
    updated_at   TIMESTAMP      NOT NULL,
    row_version  NUMBER         DEFAULT 0 NOT NULL,
    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT uk_payment_external_ref UNIQUE (external_ref),
    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED'))
);

CREATE TABLE PROVIDER_EVENT (
    id                NUMBER        NOT NULL,
    payment_id        NUMBER        NOT NULL,
    provider_event_id VARCHAR2(60)  NOT NULL,
    provider_status   VARCHAR2(20)  NOT NULL,
    received_at       TIMESTAMP     NOT NULL,
    CONSTRAINT pk_provider_event PRIMARY KEY (id),
    CONSTRAINT uk_provider_event_ext_id UNIQUE (provider_event_id),
    CONSTRAINT fk_provider_event_payment FOREIGN KEY (payment_id) REFERENCES PAYMENT (id),
    CONSTRAINT ck_provider_event_status CHECK (provider_status IN ('APPROVED', 'DECLINED'))
);

CREATE TABLE LEDGER_ENTRY (
    id               NUMBER        NOT NULL,
    payment_id       NUMBER        NOT NULL,
    entry_type       VARCHAR2(20)  NOT NULL,
    amount           NUMBER(18,2)  NOT NULL,
    related_entry_id NUMBER        NULL,
    created_at       TIMESTAMP     NOT NULL,
    CONSTRAINT pk_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entry_payment FOREIGN KEY (payment_id) REFERENCES PAYMENT (id),
    CONSTRAINT fk_ledger_entry_related FOREIGN KEY (related_entry_id) REFERENCES LEDGER_ENTRY (id),
    CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'REVERSAL'))
);

CREATE TABLE SUPPORT_AUDIT (
    id         NUMBER          NOT NULL,
    payment_id NUMBER          NOT NULL,
    action     VARCHAR2(40)    NOT NULL,
    reason     VARCHAR2(400)   NOT NULL,
    created_at TIMESTAMP       NOT NULL,
    CONSTRAINT pk_support_audit PRIMARY KEY (id),
    CONSTRAINT fk_support_audit_payment FOREIGN KEY (payment_id) REFERENCES PAYMENT (id)
);

CREATE INDEX ix_provider_event_payment ON PROVIDER_EVENT (payment_id);
CREATE INDEX ix_ledger_entry_payment ON LEDGER_ENTRY (payment_id);
CREATE INDEX ix_support_audit_payment ON SUPPORT_AUDIT (payment_id);

-- Secuencias para inserciones futuras; arrancan por encima de los IDs de data.sql.
CREATE SEQUENCE payment_seq        START WITH 100 INCREMENT BY 1;
CREATE SEQUENCE provider_event_seq START WITH 100 INCREMENT BY 1;
CREATE SEQUENCE ledger_entry_seq   START WITH 100 INCREMENT BY 1;
CREATE SEQUENCE support_audit_seq  START WITH 100 INCREMENT BY 1;
