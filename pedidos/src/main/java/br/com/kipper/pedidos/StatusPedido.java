package br.com.kipper.pedidos;

public enum StatusPedido {
    EM_PROCESSAMENTO,
    PENDENTE,
    CANCELAMENTO_PENDENTE,
    CONFIRMADO,
    CANCELADO,
    RECUSADO;

    public boolean finalizado() {
        return this == CONFIRMADO || this == CANCELADO || this == RECUSADO;
    }
}
