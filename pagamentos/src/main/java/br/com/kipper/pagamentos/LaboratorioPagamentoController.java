package br.com.kipper.pagamentos;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Controle local de testes. Não está disponível na execução normal. */
@Profile("laboratorio")
@RestController
public class LaboratorioPagamentoController {
    private final PagamentoService pagamentos;

    public LaboratorioPagamentoController(PagamentoService pagamentos) {
        this.pagamentos = pagamentos;
    }

    @PutMapping("/laboratorio/resultado")
    public Configuracao configurar(@RequestBody Configuracao configuracao) {
        pagamentos.configurarSimulacao(configuracao.resultado());
        return configuracao;
    }

    public record Configuracao(StatusPagamento resultado) { }
}
