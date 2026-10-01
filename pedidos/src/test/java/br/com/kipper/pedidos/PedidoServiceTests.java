package br.com.kipper.pedidos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PedidoServiceTests {
    private final List<String> chamadas = new ArrayList<>();
    private final EstoqueFalso estoque = new EstoqueFalso();
    private final PagamentoFalso pagamentos = new PagamentoFalso();
    private final PedidoService pedidos = new PedidoService(estoque, pagamentos);
    private final CriarPedido dados = new CriarPedido("produto-1", 2);

    @Test
    void confirmaSomenteDepoisDeReservarECobrar() {
        Pedido pedido = pedidos.criar("compra-A", dados).pedido();

        assertThat(pedido.status()).isEqualTo(StatusPedido.CONFIRMADO);
        assertThat(chamadas).containsExactly("reservar", "cobrar");
        assertThat(pedidos.consultar(pedido.pedidoId())).isEqualTo(pedido);
    }

    @Test
    void pagamentoRecusadoDevolveEstoqueAntesDeCancelar() {
        pagamentos.resultado = PagamentoGateway.ResultadoPagamento.RECUSADO;

        Pedido pedido = pedidos.criar("compra-A", dados).pedido();

        assertThat(chamadas).containsExactly("reservar", "cobrar", "devolver");
        assertThat(pedido.status()).isEqualTo(StatusPedido.CANCELADO);
    }

    @Test
    void recusaDeEstoqueImpedeCobranca() {
        estoque.erroReserva = new EstoqueGateway.ReservaRecusada("Estoque insuficiente.");

        var resposta = new PedidoController(pedidos).criar("compra-A", dados);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resposta.getBody().status()).isEqualTo(StatusPedido.RECUSADO);
        assertThat(chamadas).containsExactly("reservar");
    }

    @Test
    void timeoutNaoCancelaEConsultaRecuperaAprovacaoSemOutraCobranca() {
        pagamentos.erroCobranca = new ResourceAccessException("Timeout simulado.");

        var resposta = new PedidoController(pedidos).criar("compra-A", dados);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resposta.getBody().status()).isEqualTo(StatusPedido.PENDENTE);
        assertThat(estoque.devolucoes.get()).isZero();

        Pedido recuperado = pedidos.recuperar(resposta.getBody().pedidoId());

        assertThat(recuperado.status()).isEqualTo(StatusPedido.CONFIRMADO);
        assertThat(chamadas).containsExactly("reservar", "cobrar", "consultar-pagamento");
        assertThat(pagamentos.cobrancas.get()).isEqualTo(1);
        assertThat(estoque.devolucoes.get()).isZero();
    }

    @Test
    void consultaAposTimeoutPodeRevelarRecusaEIniciarDevolucao() {
        pagamentos.erroCobranca = new ResourceAccessException("Timeout simulado.");
        pagamentos.resultado = PagamentoGateway.ResultadoPagamento.RECUSADO;
        Pedido pendente = pedidos.criar("compra-A", dados).pedido();

        Pedido recuperado = pedidos.recuperar(pendente.pedidoId());

        assertThat(recuperado.status()).isEqualTo(StatusPedido.CANCELADO);
        assertThat(chamadas).containsExactly("reservar", "cobrar", "consultar-pagamento", "devolver");
    }

    @Test
    void falhaNaDevolucaoNaoPermiteAnunciarCancelamentoConcluido() {
        pagamentos.resultado = PagamentoGateway.ResultadoPagamento.RECUSADO;
        estoque.erroDevolucao = new ResourceAccessException("Estoque indisponível.");

        Pedido pendente = pedidos.criar("compra-A", dados).pedido();

        assertThat(pendente.status()).isEqualTo(StatusPedido.CANCELAMENTO_PENDENTE);
        estoque.erroDevolucao = null;
        Pedido recuperado = pedidos.recuperar(pendente.pedidoId());

        assertThat(recuperado.status()).isEqualTo(StatusPedido.CANCELADO);
        assertThat(pagamentos.cobrancas.get()).isEqualTo(1);
        assertThat(estoque.reservas.get()).isEqualTo(1);
        assertThat(estoque.devolucoes.get()).isEqualTo(2);
    }

    @Test
    void timeoutNaReservaERecuperadoPelaConsultaAntesDaCobranca() {
        estoque.erroReserva = new ResourceAccessException("Timeout simulado.");
        Pedido pendente = pedidos.criar("compra-A", dados).pedido();

        assertThat(pendente.status()).isEqualTo(StatusPedido.EM_PROCESSAMENTO);
        assertThat(pagamentos.cobrancas.get()).isZero();

        Pedido recuperado = pedidos.recuperar(pendente.pedidoId());

        assertThat(recuperado.status()).isEqualTo(StatusPedido.CONFIRMADO);
        assertThat(chamadas).containsExactly("reservar", "consultar-reserva", "cobrar");
    }

    @Test
    void repetirPedidoConcluidoDevolveMesmoPedidoSemEfeitosNovos() {
        var primeira = new PedidoController(pedidos).criar("compra-A", dados);
        var segunda = new PedidoController(pedidos).criar("compra-A", dados);

        assertThat(primeira.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(segunda.getBody()).isEqualTo(primeira.getBody());
        assertThat(chamadas).containsExactly("reservar", "cobrar");
    }

    @Test
    void mesmaChaveComDadosDiferentesEConflitoSemAlterarPedido() {
        Pedido original = pedidos.criar("compra-A", dados).pedido();

        assertThatThrownBy(() -> pedidos.criar("compra-A", new CriarPedido("produto-1", 5)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        erro -> assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(pedidos.consultar(original.pedidoId())).isEqualTo(original);
        assertThat(chamadas).containsExactly("reservar", "cobrar");
    }

    @Test
    void chavesDiferentesPermitemDuasComprasIguais() {
        Pedido primeiro = pedidos.criar("compra-A", dados).pedido();
        Pedido segundo = pedidos.criar("compra-B", dados).pedido();

        assertThat(primeiro.pedidoId()).isNotEqualTo(segundo.pedidoId());
        assertThat(estoque.reservas.get()).isEqualTo(2);
        assertThat(pagamentos.cobrancas.get()).isEqualTo(2);
    }

    @Test
    void repeticaoDuranteCobrancaRetorna202SemEsperarOuDuplicarOperacoes() throws Exception {
        CountDownLatch cobrando = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        pagamentos.aoCobrar = () -> {
            cobrando.countDown();
            try {
                if (!liberar.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("A cobrança não foi liberada pelo teste.");
                }
            } catch (InterruptedException erro) {
                throw new AssertionError(erro);
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var primeira = executor.submit(() -> pedidos.criar("compra-A", dados));
            try {
                assertThat(cobrando.await(5, TimeUnit.SECONDS)).isTrue();
                var segunda = executor.submit(() -> new PedidoController(pedidos).criar("compra-A", dados))
                        .get(1, TimeUnit.SECONDS);

                assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                assertThat(segunda.getBody().status()).isEqualTo(StatusPedido.PENDENTE);
                assertThat(segunda.getHeaders().getLocation().toString())
                        .isEqualTo("/pedidos/" + segunda.getBody().pedidoId());
                assertThat(estoque.reservas.get()).isEqualTo(1);
                assertThat(pagamentos.cobrancas.get()).isEqualTo(1);

                // Recuperações simultâneas também não iniciam outra execução.
                assertThat(pedidos.recuperar(segunda.getBody().pedidoId()).status())
                        .isEqualTo(StatusPedido.PENDENTE);
                liberar.countDown();
                assertThat(primeira.get(5, TimeUnit.SECONDS).pedido().pedidoId())
                        .isEqualTo(segunda.getBody().pedidoId());
            } finally {
                liberar.countDown();
            }
        }
    }

    @Test
    void dadosInvalidosNaoIniciamChamadasRemotas() {
        assertThatThrownBy(() -> pedidos.criar("", dados)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> pedidos.criar("compra-A", new CriarPedido("produto-1", 0)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(chamadas).isEmpty();
    }

    private class EstoqueFalso implements EstoqueGateway {
        AtomicInteger reservas = new AtomicInteger();
        AtomicInteger devolucoes = new AtomicInteger();
        RuntimeException erroReserva;
        RuntimeException erroDevolucao;

        public void reservar(UUID id, CriarPedido dados) {
            reservas.incrementAndGet();
            chamadas.add("reservar");
            if (erroReserva != null) throw erroReserva;
        }

        public Optional<EstadoReserva> consultar(UUID id) {
            chamadas.add("consultar-reserva");
            return Optional.of(EstadoReserva.RESERVADA);
        }

        public void devolver(UUID id) {
            devolucoes.incrementAndGet();
            chamadas.add("devolver");
            if (erroDevolucao != null) throw erroDevolucao;
        }
    }

    private class PagamentoFalso implements PagamentoGateway {
        AtomicInteger cobrancas = new AtomicInteger();
        RuntimeException erroCobranca;
        ResultadoPagamento resultado = ResultadoPagamento.APROVADO;
        Runnable aoCobrar = () -> { };

        public ResultadoPagamento cobrar(UUID id) {
            cobrancas.incrementAndGet();
            chamadas.add("cobrar");
            aoCobrar.run();
            if (erroCobranca != null) throw erroCobranca;
            return resultado;
        }

        public Optional<ResultadoPagamento> consultar(UUID id) {
            chamadas.add("consultar-pagamento");
            return Optional.of(resultado);
        }
    }
}
