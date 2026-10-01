package br.com.kipper.pedidos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PedidoService {
    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    private final Map<String, Fluxo> porChave = new HashMap<>();
    private final Map<UUID, Fluxo> porId = new HashMap<>();
    private final EstoqueGateway estoque;
    private final PagamentoGateway pagamentos;

    public PedidoService(EstoqueGateway estoque, PagamentoGateway pagamentos) {
        this.estoque = estoque;
        this.pagamentos = pagamentos;
    }

    public Criacao criar(String chave, CriarPedido dados) {
        validar(chave, dados);
        Registro registro = registrar(chave, dados);
        if (registro.novo()) {
            avancar(registro.fluxo());
        }
        // Uma repetição só lê o estado: não espera o HTTP da primeira chamada.
        return new Criacao(registro.fluxo().pedido, registro.novo());
    }

    public Pedido consultar(UUID pedidoId) {
        return localizar(pedidoId).pedido;
    }

    public synchronized List<Pedido> listar() {
        return porId.values().stream().map(fluxo -> fluxo.pedido).toList();
    }

    public Pedido recuperar(UUID pedidoId) {
        Fluxo fluxo = localizar(pedidoId);
        avancar(fluxo);
        return fluxo.pedido;
    }

    public void recuperarPendentes() {
        // A cópia evita manter o bloqueio do registro durante chamadas HTTP.
        for (UUID pedidoId : listarPendentes()) {
            recuperar(pedidoId);
        }
    }

    private synchronized List<UUID> listarPendentes() {
        return porId.values().stream().map(fluxo -> fluxo.pedido)
                .filter(pedido -> !pedido.status().finalizado())
                .map(Pedido::pedidoId).toList();
    }

    private synchronized Registro registrar(String chave, CriarPedido dados) {
        Fluxo existente = porChave.get(chave);
        if (existente != null) {
            if (!existente.dados.equals(dados)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A chave de idempotência já foi usada com outros dados.");
            }
            return new Registro(existente, false);
        }
        Fluxo novo = new Fluxo(dados);
        porChave.put(chave, novo);
        porId.put(novo.pedido.pedidoId(), novo);
        return new Registro(novo, true);
    }

    private synchronized Fluxo localizar(UUID pedidoId) {
        Fluxo fluxo = porId.get(pedidoId);
        if (fluxo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado.");
        }
        return fluxo;
    }

    private void avancar(Fluxo fluxo) {
        if (!fluxo.executando.compareAndSet(false, true)) {
            return;
        }
        try {
            while (!fluxo.pedido.status().finalizado()) {
                executarEtapa(fluxo);
            }
        } catch (EstoqueGateway.ReservaRecusada erro) {
            fluxo.atualizar(StatusPedido.RECUSADO, erro.getMessage());
        } catch (RestClientException erro) {
            // Falha de comunicação não comprova que a operação remota foi recusada.
            if (fluxo.etapa == Etapa.RESERVAR || fluxo.etapa == Etapa.CONSULTAR_RESERVA) {
                fluxo.etapa = Etapa.CONSULTAR_RESERVA;
                fluxo.atualizar(StatusPedido.EM_PROCESSAMENTO, "Resultado da reserva desconhecido.");
            } else if (fluxo.etapa == Etapa.COBRAR || fluxo.etapa == Etapa.CONSULTAR_PAGAMENTO) {
                fluxo.etapa = Etapa.CONSULTAR_PAGAMENTO;
                fluxo.atualizar(StatusPedido.PENDENTE, "Resultado do pagamento desconhecido; estoque mantido reservado.");
            } else {
                fluxo.atualizar(StatusPedido.CANCELAMENTO_PENDENTE, "Pagamento recusado; aguardando confirmação da devolução ao estoque.");
            }
            log.warn("Pedido {} aguardando recuperação na etapa {}: {}",
                    fluxo.pedido.pedidoId(), fluxo.etapa, erro.getClass().getSimpleName());
        } finally {
            fluxo.executando.set(false);
        }
    }

    private void executarEtapa(Fluxo fluxo) {
        UUID pedidoId = fluxo.pedido.pedidoId();
        switch (fluxo.etapa) {
            case RESERVAR -> {
                estoque.reservar(pedidoId, fluxo.dados);
                reservaConfirmada(fluxo);
            }
            case CONSULTAR_RESERVA -> {
                var resultado = estoque.consultar(pedidoId);
                if (resultado.isEmpty()) {
                    // Reenvia a mesma operação idempotente, mantendo o pedidoId.
                    fluxo.etapa = Etapa.RESERVAR;
                } else if (resultado.get() == EstoqueGateway.EstadoReserva.RESERVADA) {
                    reservaConfirmada(fluxo);
                } else {
                    fluxo.atualizar(StatusPedido.CANCELADO, "A reserva já foi cancelada no estoque.");
                }
            }
            case COBRAR -> aplicarPagamento(fluxo, pagamentos.cobrar(pedidoId));
            case CONSULTAR_PAGAMENTO -> {
                var resultado = pagamentos.consultar(pedidoId);
                if (resultado.isEmpty()) {
                    fluxo.etapa = Etapa.COBRAR;
                } else {
                    aplicarPagamento(fluxo, resultado.get());
                }
            }
            case DEVOLVER -> {
                estoque.devolver(pedidoId);
                fluxo.atualizar(StatusPedido.CANCELADO, "Pagamento recusado e unidades devolvidas ao estoque.");
            }
        }
    }

    private void reservaConfirmada(Fluxo fluxo) {
        fluxo.atualizar(StatusPedido.PENDENTE, "Estoque reservado; aguardando pagamento.");
        fluxo.etapa = Etapa.COBRAR;
    }

    private void aplicarPagamento(Fluxo fluxo, PagamentoGateway.ResultadoPagamento resultado) {
        if (resultado == PagamentoGateway.ResultadoPagamento.APROVADO) {
            fluxo.atualizar(StatusPedido.CONFIRMADO, "Estoque reservado e pagamento aprovado.");
        } else {
            fluxo.atualizar(StatusPedido.CANCELAMENTO_PENDENTE, "Pagamento recusado; devolvendo as unidades ao estoque.");
            fluxo.etapa = Etapa.DEVOLVER;
        }
    }

    private void validar(String chave, CriarPedido dados) {
        if (chave == null || chave.isBlank() || chave.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe Idempotency-Key com 1 a 128 caracteres não vazios.");
        }
        if (dados == null || dados.produtoId() == null || dados.produtoId().isBlank()
                || dados.quantidade() == null || dados.quantidade() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe produtoId e quantidade inteira maior que zero.");
        }
    }

    public record Criacao(Pedido pedido, boolean novo) { }
    private record Registro(Fluxo fluxo, boolean novo) { }
    private enum Etapa { RESERVAR, CONSULTAR_RESERVA, COBRAR, CONSULTAR_PAGAMENTO, DEVOLVER }

    private static class Fluxo {
        final CriarPedido dados;
        final AtomicBoolean executando = new AtomicBoolean();
        volatile Pedido pedido;
        Etapa etapa = Etapa.RESERVAR;

        Fluxo(CriarPedido dados) {
            this.dados = dados;
            pedido = new Pedido(UUID.randomUUID(), dados.produtoId(), dados.quantidade(),
                    StatusPedido.EM_PROCESSAMENTO, "Iniciando reserva de estoque.");
        }

        void atualizar(StatusPedido status, String mensagem) {
            pedido = new Pedido(pedido.pedidoId(), dados.produtoId(), dados.quantidade(), status, mensagem);
        }
    }
}
