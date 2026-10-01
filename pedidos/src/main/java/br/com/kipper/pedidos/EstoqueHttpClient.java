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
public class EstoqueHttpClient implements EstoqueGateway {
    private final RestClient client;

    public EstoqueHttpClient(@Value("${servicos.estoque-url}") String endereco,
            @Value("${servicos.timeout-ms:2000}") long timeoutMs) {
        client = ClientesHttp.criar(endereco, timeoutMs);
    }

    @Override
    public void reservar(UUID pedidoId, CriarPedido dados) {
        try {
            ReservaResposta resposta = client.post().uri("/reservas")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReservaRequest(pedidoId, dados.produtoId(), dados.quantidade()))
                    .retrieve().body(ReservaResposta.class);
            validar(resposta, pedidoId);
            if (resposta.status() != EstadoReserva.RESERVADA
                    || !dados.produtoId().equals(resposta.produtoId())
                    || !dados.quantidade().equals(resposta.quantidade())) {
                throw new RestClientException("Resposta de reserva incompatível com o pedido.");
            }
        } catch (RestClientResponseException erro) {
            int status = erro.getStatusCode().value();
            if (status == 404 || status == 409) {
                throw new ReservaRecusada(status == 404
                        ? "Produto não encontrado no estoque."
                        : "O estoque recusou a reserva.");
            }
            throw erro;
        }
    }

    @Override
    public Optional<EstadoReserva> consultar(UUID pedidoId) {
        try {
            ReservaResposta resposta = client.get().uri("/reservas/{id}", pedidoId)
                    .retrieve().body(ReservaResposta.class);
            validar(resposta, pedidoId);
            return Optional.of(resposta.status());
        } catch (RestClientResponseException erro) {
            if (erro.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw erro;
        }
    }

    @Override
    public void devolver(UUID pedidoId) {
        ReservaResposta resposta = client.delete().uri("/reservas/{id}", pedidoId)
                .retrieve().body(ReservaResposta.class);
        validar(resposta, pedidoId);
        if (resposta.status() != EstadoReserva.CANCELADA) {
            throw new RestClientException("O estoque ainda não confirmou a devolução.");
        }
    }

    private void validar(ReservaResposta resposta, UUID pedidoId) {
        if (resposta == null || !pedidoId.equals(resposta.pedidoId()) || resposta.status() == null) {
            throw new RestClientException("Resposta inválida do serviço Estoque.");
        }
    }

    record ReservaRequest(UUID pedidoId, String produtoId, int quantidade) { }
    record ReservaResposta(UUID pedidoId, String produtoId, Integer quantidade, EstadoReserva status) { }
}
