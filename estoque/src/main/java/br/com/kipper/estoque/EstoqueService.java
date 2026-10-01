package br.com.kipper.estoque;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EstoqueService {

    private final Map<String, Integer> disponiveis = new HashMap<>(Map.of("produto-1", 10));
    private final Map<UUID, Reserva> reservas = new HashMap<>();

    // Verificação, desconto e registro usam o mesmo bloqueio nesta instância.
    public synchronized Reserva reservar(SolicitarReserva solicitacao) {
        validar(solicitacao);

        Reserva existente = reservas.get(solicitacao.pedidoId());
        if (existente != null) {
            if (!existente.produtoId().equals(solicitacao.produtoId())
                    || existente.quantidade() != solicitacao.quantidade()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "O pedido já possui uma reserva com outros dados.");
            }
            if (existente.status() == StatusReserva.CANCELADA) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A reserva deste pedido já foi cancelada.");
            }
            return existente;
        }

        int disponivel = consultarSaldo(solicitacao.produtoId()).disponivel();
        if (disponivel < solicitacao.quantidade()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Estoque insuficiente.");
        }

        Reserva reserva = new Reserva(solicitacao.pedidoId(), solicitacao.produtoId(),
                solicitacao.quantidade(), StatusReserva.RESERVADA);
        disponiveis.put(reserva.produtoId(), disponivel - reserva.quantidade());
        reservas.put(reserva.pedidoId(), reserva);
        return reserva;
    }

    public synchronized Reserva cancelar(UUID pedidoId) {
        Reserva reserva = consultarReserva(pedidoId);
        if (reserva.status() == StatusReserva.CANCELADA) {
            return reserva;
        }
        Reserva cancelada = new Reserva(reserva.pedidoId(), reserva.produtoId(),
                reserva.quantidade(), StatusReserva.CANCELADA);
        disponiveis.put(reserva.produtoId(),
                disponiveis.get(reserva.produtoId()) + reserva.quantidade());
        // Mantemos o registro para que repetições não devolvam as unidades novamente.
        reservas.put(pedidoId, cancelada);
        return cancelada;
    }

    public synchronized SaldoEstoque consultarSaldo(String produtoId) {
        Integer disponivel = disponiveis.get(produtoId);
        if (disponivel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado.");
        }
        return new SaldoEstoque(produtoId, disponivel);
    }

    public synchronized Reserva consultarReserva(UUID pedidoId) {
        Reserva reserva = reservas.get(pedidoId);
        if (reserva == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva não encontrada.");
        }
        return reserva;
    }

    public synchronized List<Reserva> listarReservas() {
        return List.copyOf(reservas.values());
    }

    private void validar(SolicitarReserva solicitacao) {
        if (solicitacao == null || solicitacao.pedidoId() == null
                || solicitacao.produtoId() == null || solicitacao.produtoId().isBlank()
                || solicitacao.quantidade() == null || solicitacao.quantidade() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe pedidoId, produtoId e quantidade inteira maior que zero.");
        }
    }
}
