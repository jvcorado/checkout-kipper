package br.com.kipper.estoque;

import java.util.UUID;

public record Reserva(UUID pedidoId, String produtoId, int quantidade, StatusReserva status) {
}
