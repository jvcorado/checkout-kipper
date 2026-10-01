package br.com.kipper.estoque;

import java.util.ArrayList;
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

class EstoqueServiceTests {

    @Test
    void reservaDescontaDisponibilidadeEGuardaVinculoComPedido() {
        EstoqueService estoque = new EstoqueService();
        UUID pedidoId = UUID.randomUUID();

        Reserva reserva = estoque.reservar(new SolicitarReserva(pedidoId, "produto-1", 3));

        assertThat(estoque.consultarSaldo("produto-1").disponivel()).isEqualTo(7);
        assertThat(estoque.consultarReserva(pedidoId)).isEqualTo(reserva);
        assertThat(reserva.quantidade()).isEqualTo(3);
    }

    @Test
    void repetirMesmaReservaNaoDescontaDuasVezes() {
        EstoqueService estoque = new EstoqueService();
        SolicitarReserva solicitacao = new SolicitarReserva(UUID.randomUUID(), "produto-1", 3);

        Reserva primeira = estoque.reservar(solicitacao);
        Reserva repetida = estoque.reservar(solicitacao);

        assertThat(repetida).isEqualTo(primeira);
        assertThat(estoque.consultarSaldo("produto-1").disponivel()).isEqualTo(7);
    }

    @Test
    void rejeitaMudancaDosDadosDeUmaReservaExistente() {
        EstoqueService estoque = new EstoqueService();
        UUID pedidoId = UUID.randomUUID();
        estoque.reservar(new SolicitarReserva(pedidoId, "produto-1", 3));

        assertThatThrownBy(() -> estoque.reservar(new SolicitarReserva(pedidoId, "produto-1", 4)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        erro -> assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(estoque.consultarSaldo("produto-1").disponivel()).isEqualTo(7);
    }

    @Test
    void faltaDeEstoqueNaoCriaReservaNemAlteraSaldo() {
        EstoqueService estoque = new EstoqueService();
        UUID pedidoId = UUID.randomUUID();

        assertThatThrownBy(() -> estoque.reservar(new SolicitarReserva(pedidoId, "produto-1", 11)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        erro -> assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(estoque.consultarSaldo("produto-1").disponivel()).isEqualTo(10);
        assertThatThrownBy(() -> estoque.consultarReserva(pedidoId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        erro -> assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void rejeitaQuantidadeInvalidaSemAlterarEstoque() {
        EstoqueService estoque = new EstoqueService();
        for (Integer quantidade : new Integer[] {null, 0, -1}) {
            assertThatThrownBy(() -> estoque.reservar(
                    new SolicitarReserva(UUID.randomUUID(), "produto-1", quantidade)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            erro -> assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(estoque.consultarSaldo("produto-1").disponivel()).isEqualTo(10);
    }

    @Test
    void cancelarDuasVezesDevolveAsUnidadesApenasUmaVez() {
        EstoqueService estoque = new EstoqueService();
        UUID pedidoId = UUID.randomUUID();
        estoque.reservar(new SolicitarReserva(pedidoId, "produto-1", 3));

        Reserva primeira = estoque.cancelar(pedidoId);
        Reserva repetida = estoque.cancelar(pedidoId);

        assertThat(repetida).isEqualTo(primeira);
        assertThat(primeira.status()).isEqualTo(StatusReserva.CANCELADA);
        assertThat(estoque.consultarReserva(pedidoId).status()).isEqualTo(StatusReserva.CANCELADA);
        assertThat(estoque.consultarSaldo("produto-1").disponivel()).isEqualTo(10);
    }

    @Test
    void reservaCanceladaNaoPodeSerReativadaPorRepeticaoAtrasada() {
        EstoqueService estoque = new EstoqueService();
        SolicitarReserva solicitacao = new SolicitarReserva(UUID.randomUUID(), "produto-1", 3);
        estoque.reservar(solicitacao);
        estoque.cancelar(solicitacao.pedidoId());

        assertThatThrownBy(() -> estoque.reservar(solicitacao))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        erro -> assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(estoque.consultarSaldo("produto-1").disponivel()).isEqualTo(10);
    }

    @Test
    void cancelarReservaInexistenteNaoAumentaEstoque() {
        EstoqueService estoque = new EstoqueService();

        assertThatThrownBy(() -> estoque.cancelar(UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        erro -> assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(estoque.consultarSaldo("produto-1").disponivel()).isEqualTo(10);
    }

    @Test
    void pedidosConcorrentesNaoReservamMaisQueDisponivel() throws Exception {
        EstoqueService estoque = new EstoqueService();
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Boolean>> tentativas = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 30; i++) {
                tentativas.add(executor.submit(() -> {
                    largada.await();
                    try {
                        estoque.reservar(new SolicitarReserva(UUID.randomUUID(), "produto-1", 1));
                        return true;
                    } catch (ResponseStatusException erro) {
                        assertThat(erro.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        return false;
                    }
                }));
            }
            largada.countDown();
            int aprovadas = 0;
            for (Future<Boolean> tentativa : tentativas) {
                if (tentativa.get(5, TimeUnit.SECONDS)) {
                    aprovadas++;
                }
            }
            assertThat(aprovadas).isEqualTo(10);
        }
        assertThat(estoque.consultarSaldo("produto-1").disponivel()).isZero();
    }
}
