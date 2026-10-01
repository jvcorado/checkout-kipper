package br.com.kipper.pagamentos;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PagamentoService {

    private final Map<UUID, Pagamento> pagamentos = new ConcurrentHashMap<>();
    private volatile StatusPagamento resultadoSimulado;

    public PagamentoService(@Value("${simulacao.resultado:APROVADO}") StatusPagamento resultadoSimulado) {
        this.resultadoSimulado = resultadoSimulado;
    }

    public Pagamento cobrar(SolicitarPagamento solicitacao) {
        if (solicitacao == null || solicitacao.pedidoId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe pedidoId.");
        }
        // O mesmo pedidoId identifica a mesma cobrança, inclusive sob concorrência.
        return pagamentos.computeIfAbsent(solicitacao.pedidoId(), pedidoId ->
                new Pagamento(UUID.randomUUID(), pedidoId, resultadoSimulado));
    }

    public Pagamento consultar(UUID pedidoId) {
        Pagamento pagamento = pagamentos.get(pedidoId);
        if (pagamento == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pagamento não encontrado.");
        }
        return pagamento;
    }

    public List<Pagamento> listar() {
        return List.copyOf(pagamentos.values());
    }

    public void configurarSimulacao(StatusPagamento resultado) {
        if (resultado == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o resultado simulado.");
        }
        resultadoSimulado = resultado;
    }
}
