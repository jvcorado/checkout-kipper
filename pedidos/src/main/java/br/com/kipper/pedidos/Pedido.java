package br.com.kipper.pedidos;

import java.util.UUID;

public record Pedido(UUID pedidoId, String produtoId, int quantidade,
        StatusPedido status, String mensagem) {
}
