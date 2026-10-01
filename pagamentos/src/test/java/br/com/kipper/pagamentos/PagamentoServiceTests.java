package br.com.kipper.pagamentos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagamentoServiceTests {

    @Test
    void repeticaoEConsultaRecuperamMesmoPagamento() {
        PagamentoService pagamentos = new PagamentoService(StatusPagamento.APROVADO);
        SolicitarPagamento solicitacao = new SolicitarPagamento(UUID.randomUUID());

        Pagamento primeiro = pagamentos.cobrar(solicitacao);

        assertThat(primeiro.status()).isEqualTo(StatusPagamento.APROVADO);
        assertThat(pagamentos.cobrar(solicitacao)).isEqualTo(primeiro);
        assertThat(pagamentos.consultar(solicitacao.pedidoId())).isEqualTo(primeiro);
    }

    @Test
    void recusaTambemTemResultadoEstavelPorPedido() {
        PagamentoService pagamentos = new PagamentoService(StatusPagamento.RECUSADO);
        SolicitarPagamento solicitacao = new SolicitarPagamento(UUID.randomUUID());

        Pagamento primeiro = pagamentos.cobrar(solicitacao);

        assertThat(primeiro.status()).isEqualTo(StatusPagamento.RECUSADO);
        assertThat(pagamentos.cobrar(solicitacao)).isEqualTo(primeiro);
        assertThat(pagamentos.consultar(solicitacao.pedidoId())).isEqualTo(primeiro);
    }

    @Test
    void pedidosDiferentesGeramPagamentosDiferentes() {
        PagamentoService pagamentos = new PagamentoService(StatusPagamento.APROVADO);
        Pagamento primeiro = pagamentos.cobrar(new SolicitarPagamento(UUID.randomUUID()));
        Pagamento segundo = pagamentos.cobrar(new SolicitarPagamento(UUID.randomUUID()));

        assertThat(primeiro.pagamentoId()).isNotEqualTo(segundo.pagamentoId());
        assertThat(primeiro.pedidoId()).isNotEqualTo(segundo.pedidoId());
    }

    @Test
    void consultaInexistenteNaoCriaCobranca() {
        PagamentoService pagamentos = new PagamentoService(StatusPagamento.APROVADO);
        assertThatThrownBy(() -> pagamentos.consultar(UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        erro -> assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void pedidoIdEObrigatorio() {
        PagamentoService pagamentos = new PagamentoService(StatusPagamento.APROVADO);
        assertThatThrownBy(() -> pagamentos.cobrar(new SolicitarPagamento(null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        erro -> assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void mudarSimulacaoSoAfetaNovasCobrancas() {
        PagamentoService pagamentos = new PagamentoService(StatusPagamento.APROVADO);
        SolicitarPagamento primeira = new SolicitarPagamento(UUID.randomUUID());
        Pagamento original = pagamentos.cobrar(primeira);

        pagamentos.configurarSimulacao(StatusPagamento.RECUSADO);

        assertThat(pagamentos.cobrar(primeira)).isEqualTo(original);
        assertThat(pagamentos.cobrar(new SolicitarPagamento(UUID.randomUUID())).status())
                .isEqualTo(StatusPagamento.RECUSADO);
        assertThat(pagamentos.listar()).hasSize(2);
    }

    @Test
    void chamadasSimultaneasParaMesmoPedidoCriamUmaUnicaCobranca() throws Exception {
        PagamentoService pagamentos = new PagamentoService(StatusPagamento.APROVADO);
        SolicitarPagamento solicitacao = new SolicitarPagamento(UUID.randomUUID());
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Pagamento>> tentativas = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 30; i++) {
                tentativas.add(executor.submit(() -> {
                    largada.await();
                    return pagamentos.cobrar(solicitacao);
                }));
            }
            largada.countDown();
            var ids = new HashSet<UUID>();
            for (Future<Pagamento> tentativa : tentativas) {
                ids.add(tentativa.get(5, TimeUnit.SECONDS).pagamentoId());
            }
            assertThat(ids).hasSize(1);
        }
    }
}
