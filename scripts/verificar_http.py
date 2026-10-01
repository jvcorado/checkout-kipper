"""Verifica os JARs reais em localhost e encerra somente os processos que iniciou."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
import json
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
PORTAS = {"pedidos": 8081, "estoque": 8082, "pagamentos": 8083}
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def requisicao(servico, caminho, metodo="GET", corpo=None, chave=None):
    headers = {"Content-Type": "application/json"}
    if chave is not None:
        headers["Idempotency-Key"] = chave
    data = None if corpo is None else json.dumps(corpo).encode()
    req = urllib.request.Request(
        f"http://127.0.0.1:{PORTAS[servico]}{caminho}",
        data=data, headers=headers, method=metodo,
    )
    try:
        resposta = HTTP.open(req, timeout=8)
    except urllib.error.HTTPError as erro:
        resposta = erro
    with resposta:
        conteudo = resposta.read()
        return resposta.status, json.loads(conteudo) if conteudo else None, resposta.headers


def aguardar(consulta, condicao, segundos=12):
    limite = time.monotonic() + segundos
    ultimo = None
    while time.monotonic() < limite:
        try:
            ultimo = consulta()
            if condicao(ultimo):
                return ultimo
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.05)
    raise AssertionError(f"Condição não alcançada. Último resultado: {ultimo}")


class Ambiente:
    def __init__(self, pasta, resultado="APROVADO", atraso=0):
        self.pasta = Path(pasta)
        self.resultado = resultado
        self.atraso = atraso
        self.processos = []

    def iniciar(self, servico):
        jar = ROOT / servico / "target" / f"{servico}-0.0.1-SNAPSHOT.jar"
        if not jar.exists():
            raise RuntimeError(f"Compile {servico} com ./mvnw verify antes deste teste.")
        log = open(self.pasta / f"{servico}.log", "wb")
        argumentos = [
            "java", "-jar", str(jar), "--server.address=127.0.0.1",
            f"--server.port={PORTAS[servico]}", "--spring.main.banner-mode=off",
        ]
        if servico == "pedidos":
            argumentos += [
                "--servicos.estoque-url=http://127.0.0.1:8082",
                "--servicos.pagamentos-url=http://127.0.0.1:8083",
                "--servicos.timeout-ms=400", "--recuperacao.automatica=true",
                "--recuperacao.intervalo-ms=2000",
            ]
        if servico == "pagamentos":
            argumentos += [f"--simulacao.resultado={self.resultado}",
                           f"--simulacao.atraso-resposta-ms={self.atraso}"]
        processo = subprocess.Popen(argumentos, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT)
        self.processos.append((processo, log, servico))
        aguardar(lambda: requisicao(servico, "/"), lambda r: r[0] == 404)
        if processo.poll() is not None:
            raise RuntimeError(f"O processo {servico} encerrou durante a inicialização.")

    def encerrar(self):
        for processo, _, _ in reversed(self.processos):
            processo.terminate()
        for processo, log, _ in self.processos:
            try:
                processo.wait(timeout=10)
            except subprocess.TimeoutExpired:
                processo.kill()
                processo.wait(timeout=5)
            log.close()


@contextmanager
def ambiente(servicos=("estoque", "pagamentos", "pedidos"), **opcoes):
    # Nunca reutiliza nem encerra servidores que já estavam rodando.
    for porta in PORTAS.values():
        with socket.socket() as conexao:
            conexao.settimeout(0.2)
            if conexao.connect_ex(("127.0.0.1", porta)) == 0:
                raise RuntimeError(f"A porta {porta} está ocupada. Encerre sua execução antes do teste.")
    with tempfile.TemporaryDirectory(prefix="kipper-http-") as pasta:
        atual = Ambiente(pasta, **opcoes)
        try:
            for servico in servicos:
                atual.iniciar(servico)
            yield atual
        except Exception:
            for arquivo in Path(pasta).glob("*.log"):
                print(f"Diagnóstico de {arquivo.name}:\n{arquivo.read_text(errors='replace')[-6000:]}")
            raise
        finally:
            atual.encerrar()


def criar(chave, quantidade=2, produto="produto-1"):
    return requisicao("pedidos", "/pedidos", "POST",
                      {"produtoId": produto, "quantidade": quantidade}, chave)


def saldo():
    return requisicao("estoque", "/estoque/produto-1")[1]["disponivel"]


def esperar_confirmacao(pedido_id):
    return aguardar(lambda: requisicao("pedidos", f"/pedidos/{pedido_id}"),
                    lambda r: r[0] == 200 and r[1]["status"] == "CONFIRMADO")


def main():
    with ambiente():
        status, pedido, headers = criar("compra-A")
        assert status == 201 and pedido["status"] == "CONFIRMADO", (status, pedido)
        assert headers["Location"] == f"/pedidos/{pedido['pedidoId']}"
        assert saldo() == 8
        status, repetido, _ = criar("compra-A")
        assert status == 200 and repetido == pedido
        assert saldo() == 8
        assert criar("compra-A", 5)[0] == 409
        status, segundo, _ = criar("compra-B")
        assert status == 201 and segundo["pedidoId"] != pedido["pedidoId"]
        assert saldo() == 6
        assert criar(None)[0] == 400
        assert criar("invalido", 0)[0] == 400
        assert criar("fracionario", 2.5)[0] == 400
        status, recusado, _ = criar("sem-estoque", 99)
        assert status == 409 and recusado["status"] == "RECUSADO"
        assert requisicao("pagamentos", f"/pagamentos/{recusado['pedidoId']}")[0] == 404
        assert saldo() == 6
        print("OK: sucesso, repetição, conflito, compras distintas, validação e falta de estoque.", flush=True)

    with ambiente(resultado="RECUSADO"):
        status, pedido, _ = criar("recusa-A")
        assert status == 201 and pedido["status"] == "CANCELADO", (status, pedido)
        assert saldo() == 10
        assert requisicao("estoque", f"/reservas/{pedido['pedidoId']}")[1]["status"] == "CANCELADA"
        assert requisicao("estoque", f"/reservas/{pedido['pedidoId']}", "DELETE")[0] == 200
        assert saldo() == 10
        assert criar("recusa-A")[1] == pedido
        print("OK: pagamento recusado, cancelamento e devolução sem duplicidade.", flush=True)

    with ambiente(atraso=4000):
        with ThreadPoolExecutor(max_workers=1) as executor:
            primeira = executor.submit(criar, "timeout-A")
            aguardar(saldo, lambda disponivel: disponivel == 8)
            status, em_andamento, _ = criar("timeout-A")
            assert status == 202 and em_andamento["status"] == "PENDENTE", (status, em_andamento)
            status, pendente, _ = primeira.result(timeout=8)
            assert status == 202 and pendente["pedidoId"] == em_andamento["pedidoId"]
        pagamento = requisicao("pagamentos", f"/pagamentos/{pendente['pedidoId']}")[1]
        assert pagamento["status"] == "APROVADO"
        esperar_confirmacao(pendente["pedidoId"])
        assert saldo() == 8
        assert requisicao("pagamentos", f"/pagamentos/{pendente['pedidoId']}")[1] == pagamento
        print("OK: repetição em andamento retorna 202; timeout é recuperado automaticamente.", flush=True)

    with ambiente(servicos=("estoque", "pedidos")) as atual:
        status, pendente, _ = criar("pagamento-indisponivel")
        assert status == 202 and pendente["status"] == "PENDENTE"
        assert saldo() == 8
        atual.iniciar("pagamentos")
        esperar_confirmacao(pendente["pedidoId"])
        assert saldo() == 8
        print("OK: Pagamentos indisponível mantém reserva; retorno do serviço permite concluir.", flush=True)

    with ambiente(servicos=("pagamentos", "pedidos")) as atual:
        status, pendente, _ = criar("estoque-indisponivel")
        assert status == 202 and pendente["status"] == "EM_PROCESSAMENTO"
        assert requisicao("pagamentos", f"/pagamentos/{pendente['pedidoId']}")[0] == 404
        atual.iniciar("estoque")
        esperar_confirmacao(pendente["pedidoId"])
        assert saldo() == 8
        print("OK: Estoque indisponível impede cobrança; retorno do serviço permite concluir.", flush=True)

    print("Todos os cenários HTTP passaram. Os processos do teste foram encerrados.", flush=True)


if __name__ == "__main__":
    main()
