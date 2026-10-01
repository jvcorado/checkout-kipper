package br.com.kipper.pedidos;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PedidoController {
    private final PedidoService pedidos;

    public PedidoController(PedidoService pedidos) {
        this.pedidos = pedidos;
    }

    @PostMapping("/pedidos")
    public ResponseEntity<Pedido> criar(@RequestHeader("Idempotency-Key") String chave,
            @RequestBody CriarPedido dados) {
        PedidoService.Criacao criacao = pedidos.criar(chave, dados);
        Pedido pedido = criacao.pedido();
        int status = !pedido.status().finalizado() ? 202
                : pedido.status() == StatusPedido.RECUSADO ? 409
                : criacao.novo() ? 201 : 200;
        var resposta = ResponseEntity.status(status)
                .location(URI.create("/pedidos/" + pedido.pedidoId()));
        if (status == 202) {
            resposta.header("Retry-After", "2");
        }
        return resposta.body(pedido);
    }

    @GetMapping("/pedidos/{pedidoId}")
    public Pedido consultar(@PathVariable UUID pedidoId) {
        return pedidos.consultar(pedidoId);
    }

    @GetMapping("/pedidos")
    public List<Pedido> listar() {
        return pedidos.listar();
    }
}
