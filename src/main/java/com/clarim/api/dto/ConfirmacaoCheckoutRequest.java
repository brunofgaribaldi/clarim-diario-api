package com.clarim.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmacaoCheckoutRequest(
        @NotBlank(message = "A sessão do checkout é obrigatória")
        String sessionId
) {}
