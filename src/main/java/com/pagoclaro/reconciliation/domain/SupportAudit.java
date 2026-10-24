package com.pagoclaro.reconciliation.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "SUPPORT_AUDIT")
public class SupportAudit {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "support_audit_seq")
	@SequenceGenerator(name = "support_audit_seq", sequenceName = "support_audit_seq", allocationSize = 1)
	private Long id;

	// Campo simple, sin relación JPA: evita cascadas y borrados en cadena.
	@Column(name = "payment_id", nullable = false)
	private Long paymentId;

	@Column(name = "action", nullable = false, length = 40)
	private String action;

	@Column(name = "reason", nullable = false, length = 400)
	private String reason;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	protected SupportAudit() {
	}

	public SupportAudit(Long paymentId, String action, String reason, LocalDateTime createdAt) {
		this.paymentId = paymentId;
		this.action = action;
		this.reason = reason;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getPaymentId() {
		return paymentId;
	}

	public String getAction() {
		return action;
	}

	public String getReason() {
		return reason;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
