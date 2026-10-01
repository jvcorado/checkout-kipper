package br.com.kipper.pagamentos;

import java.util.UUID;

public record Pagamento(UUID pagamentoId, UUID pedidoId, StatusPagamento status) {
}
