package com.pagoclaro.reconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

// Los movimientos contables no se modifican: una corrección es un REVERSAL nuevo.
@Entity
@Immutable
@Table(name = "LEDGER_ENTRY")
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_entry_seq")
	@SequenceGenerator(name = "ledger_entry_seq", sequenceName = "ledger_entry_seq", allocationSize = 1)
	private Long id;

	// Campo simple, sin relación JPA: evita cascadas y borrados en cadena.
	@Column(name = "payment_id", nullable = false)
	private Long paymentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type", nullable = false, length = 20)
	private LedgerEntryType entryType;

	@Column(name = "amount", nullable = false, precision = 18, scale = 2)
	private BigDecimal amount;

	// En un REVERSAL apunta al DEBIT que revierte.
	@Column(name = "related_entry_id")
	private Long relatedEntryId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	protected LedgerEntry() {
	}

	public LedgerEntry(Long paymentId, LedgerEntryType entryType, BigDecimal amount, Long relatedEntryId,
			LocalDateTime createdAt) {
		this.paymentId = paymentId;
		this.entryType = entryType;
		this.amount = amount;
		this.relatedEntryId = relatedEntryId;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getPaymentId() {
		return paymentId;
	}

	public LedgerEntryType getEntryType() {
		return entryType;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public Long getRelatedEntryId() {
		return relatedEntryId;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
