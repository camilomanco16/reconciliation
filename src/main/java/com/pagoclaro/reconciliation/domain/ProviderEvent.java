package com.pagoclaro.reconciliation.domain;

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

@Entity
@Table(name = "PROVIDER_EVENT")
public class ProviderEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "provider_event_seq")
	@SequenceGenerator(name = "provider_event_seq", sequenceName = "provider_event_seq", allocationSize = 1)
	private Long id;

	// Campo simple, sin relación JPA: evita cascadas y borrados en cadena.
	@Column(name = "payment_id", nullable = false)
	private Long paymentId;

	@Column(name = "provider_event_id", nullable = false, unique = true, length = 60)
	private String providerEventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider_status", nullable = false, length = 20)
	private ProviderStatus providerStatus;

	@Column(name = "received_at", nullable = false)
	private LocalDateTime receivedAt;

	protected ProviderEvent() {
	}

	public ProviderEvent(Long paymentId, String providerEventId, ProviderStatus providerStatus,
			LocalDateTime receivedAt) {
		this.paymentId = paymentId;
		this.providerEventId = providerEventId;
		this.providerStatus = providerStatus;
		this.receivedAt = receivedAt;
	}

	public Long getId() {
		return id;
	}

	public Long getPaymentId() {
		return paymentId;
	}

	public String getProviderEventId() {
		return providerEventId;
	}

	public ProviderStatus getProviderStatus() {
		return providerStatus;
	}

	public LocalDateTime getReceivedAt() {
		return receivedAt;
	}
}
