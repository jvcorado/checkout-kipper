package br.com.kipper.pagamentos;

import java.util.UUID;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PagamentoController {

    private final PagamentoService pagamentos;
    private final long atrasoRespostaMs;

    public PagamentoController(PagamentoService pagamentos,
            @Value("${simulacao.atraso-resposta-ms:0}") long atrasoRespostaMs) {
        if (atrasoRespostaMs < 0) {
            throw new IllegalArgumentException("O atraso da simulação não pode ser negativo.");
        }
        this.pagamentos = pagamentos;
        this.atrasoRespostaMs = atrasoRespostaMs;
    }

    @PostMapping("/pagamentos")
    public Pagamento cobrar(@RequestBody SolicitarPagamento solicitacao) {
        Pagamento pagamento = pagamentos.cobrar(solicitacao);
        // Simula uma resposta atrasada depois que a cobrança já foi registrada.
        try {
            Thread.sleep(atrasoRespostaMs);
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Resposta interrompida. Consulte o resultado pelo pedidoId.", erro);
        }
        return pagamento;
    }

    @GetMapping("/pagamentos/{pedidoId}")
    public Pagamento consultar(@PathVariable UUID pedidoId) {
        return pagamentos.consultar(pedidoId);
    }

    @GetMapping("/pagamentos")
    public List<Pagamento> listar() {
        return pagamentos.listar();
    }
}
