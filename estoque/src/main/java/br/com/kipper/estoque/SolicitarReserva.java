package br.com.kipper.estoque;

import java.util.UUID;

public record SolicitarReserva(UUID pedidoId, String produtoId, Integer quantidade) {
}
