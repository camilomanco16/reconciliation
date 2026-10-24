package com.pagoclaro.reconciliation.service;

// Respuesta pública de la conciliación: sin ids internos ni detalles técnicos.
public record ReconciliationResult(String externalRef, Outcome result, String reason) {

	public enum Outcome {
		CONSISTENTE,
		CORREGIDO,
		REVISION_MANUAL,
		NO_ENCONTRADO
	}
}
