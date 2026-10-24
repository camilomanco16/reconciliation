package com.pagoclaro.reconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "PAYMENT")
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_seq")
	@SequenceGenerator(name = "payment_seq", sequenceName = "payment_seq", allocationSize = 1)
	private Long id;

	@Column(name = "external_ref", nullable = false, unique = true, length = 40)
	private String externalRef;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentStatus status;

	@Column(name = "amount", nullable = false, precision = 18, scale = 2)
	private BigDecimal amount;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	// Protección extra: el flujo principal usa bloqueo pesimista.
	@Version
	@Column(name = "row_version", nullable = false)
	private Long rowVersion;

	protected Payment() {
	}

	public Payment(String externalRef, PaymentStatus status, BigDecimal amount, LocalDateTime updatedAt) {
		this.externalRef = externalRef;
		this.status = status;
		this.amount = amount;
		this.updatedAt = updatedAt;
	}

	public Long getId() {
		return id;
	}

	public String getExternalRef() {
		return externalRef;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public void setStatus(PaymentStatus status) {
		this.status = status;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	public Long getRowVersion() {
		return rowVersion;
	}
}
