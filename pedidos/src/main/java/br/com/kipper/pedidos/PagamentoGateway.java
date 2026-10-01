package br.com.kipper.pedidos;

import java.util.Optional;
import java.util.UUID;

public interface PagamentoGateway {
    ResultadoPagamento cobrar(UUID pedidoId);
    Optional<ResultadoPagamento> consultar(UUID pedidoId);

    enum ResultadoPagamento { APROVADO, RECUSADO }
}
