package br.com.kipper.pedidos;

import java.util.Optional;
import java.util.UUID;

public interface EstoqueGateway {
    void reservar(UUID pedidoId, CriarPedido dados);
    Optional<EstadoReserva> consultar(UUID pedidoId);
    void devolver(UUID pedidoId);

    enum EstadoReserva { RESERVADA, CANCELADA }

    class ReservaRecusada extends RuntimeException {
        public ReservaRecusada(String mensagem) {
            super(mensagem);
        }
    }
}
