package br.com.kipper.estoque;

import java.util.UUID;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EstoqueController {

    private final EstoqueService estoque;

    public EstoqueController(EstoqueService estoque) {
        this.estoque = estoque;
    }

    @PostMapping("/reservas")
    public Reserva reservar(@RequestBody SolicitarReserva solicitacao) {
        return estoque.reservar(solicitacao);
    }

    @GetMapping("/estoque/{produtoId}")
    public SaldoEstoque consultarSaldo(@PathVariable String produtoId) {
        return estoque.consultarSaldo(produtoId);
    }

    @GetMapping("/reservas/{pedidoId}")
    public Reserva consultarReserva(@PathVariable UUID pedidoId) {
        return estoque.consultarReserva(pedidoId);
    }

    @DeleteMapping("/reservas/{pedidoId}")
    public Reserva cancelar(@PathVariable UUID pedidoId) {
        return estoque.cancelar(pedidoId);
    }

    @GetMapping("/reservas")
    public List<Reserva> listar() {
        return estoque.listarReservas();
    }
}
