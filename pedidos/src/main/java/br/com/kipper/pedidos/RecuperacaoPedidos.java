package br.com.kipper.pedidos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "recuperacao.automatica", havingValue = "true", matchIfMissing = true)
public class RecuperacaoPedidos {
    private final PedidoService pedidos;

    public RecuperacaoPedidos(PedidoService pedidos) {
        this.pedidos = pedidos;
    }

    @Scheduled(fixedDelayString = "${recuperacao.intervalo-ms:2000}",
            initialDelayString = "${recuperacao.intervalo-ms:2000}")
    public void recuperar() {
        pedidos.recuperarPendentes();
    }
}
