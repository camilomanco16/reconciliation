package com.pagoclaro.reconciliation.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pagoclaro.reconciliation.service.ReconciliationResult;
import com.pagoclaro.reconciliation.service.ReconciliationResult.Outcome;
import com.pagoclaro.reconciliation.service.ReconciliationService;

@RestController
@RequestMapping("/support/payments")
public class ReconciliationController {

	private final ReconciliationService reconciliationService;

	public ReconciliationController(ReconciliationService reconciliationService) {
		this.reconciliationService = reconciliationService;
	}

	@PostMapping("/{externalRef}/reconcile")
	public ResponseEntity<ReconciliationResult> reconcile(@PathVariable String externalRef) {
		ReconciliationResult result = reconciliationService.reconcile(externalRef);
		HttpStatus status = result.result() == Outcome.NO_ENCONTRADO ? HttpStatus.NOT_FOUND : HttpStatus.OK;
		return ResponseEntity.status(status).body(result);
	}
}
