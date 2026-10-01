package br.com.kipper.pedidos;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PagamentoHttpClient implements PagamentoGateway {
    private final RestClient client;

    public PagamentoHttpClient(@Value("${servicos.pagamentos-url}") String endereco,
            @Value("${servicos.timeout-ms:2000}") long timeoutMs) {
        client = ClientesHttp.criar(endereco, timeoutMs);
    }

    @Override
    public ResultadoPagamento cobrar(UUID pedidoId) {
        PagamentoResposta resposta = client.post().uri("/pagamentos")
                .contentType(MediaType.APPLICATION_JSON).body(new PagamentoRequest(pedidoId))
                .retrieve().body(PagamentoResposta.class);
        return validar(resposta, pedidoId);
    }

    @Override
    public Optional<ResultadoPagamento> consultar(UUID pedidoId) {
        try {
            PagamentoResposta resposta = client.get().uri("/pagamentos/{id}", pedidoId)
                    .retrieve().body(PagamentoResposta.class);
            return Optional.of(validar(resposta, pedidoId));
        } catch (RestClientResponseException erro) {
            if (erro.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw erro;
        }
    }

    private ResultadoPagamento validar(PagamentoResposta resposta, UUID pedidoId) {
        if (resposta == null || !pedidoId.equals(resposta.pedidoId())
                || resposta.pagamentoId() == null || resposta.status() == null) {
            throw new RestClientException("Resposta inválida do serviço Pagamentos.");
        }
        return resposta.status();
    }

    record PagamentoRequest(UUID pedidoId) { }
    record PagamentoResposta(UUID pagamentoId, UUID pedidoId, ResultadoPagamento status) { }
}
