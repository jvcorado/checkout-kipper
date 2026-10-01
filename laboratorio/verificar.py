"""Testes HTTP do laboratório. Inicia e encerra sua própria execução local."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import http.client
import json
import os
import signal
import subprocess
import sys
import time
from urllib import error, request

from servidor import DEFAULT, ROOT
HTTP = request.build_opener(request.ProxyHandler({}))
BASE = 'http://127.0.0.1:8080'


def chamar(path, metodo='GET', corpo=None, chave=None, origem=None):
    headers = {'Content-Type': 'application/json'}
    if chave is not None:
        headers['Idempotency-Key'] = chave
    if origem is not None:
        headers['Origin'] = origem
    req = request.Request(BASE + path, method=metodo, headers=headers,
                          data=None if corpo is None else json.dumps(corpo).encode())
    try:
        resposta = HTTP.open(req, timeout=20)
    except error.HTTPError as exc:
        resposta = exc
    with resposta:
        dados = resposta.read()
        return resposta.status, json.loads(dados) if dados else None


def estado():
    return chamar('/laboratorio/estado')[1]


def aguardar(predicado, segundos=35):
    limite = time.monotonic() + segundos
    ultimo = None
    while time.monotonic() < limite:
        try:
            ultimo = estado()
            if ultimo['erro']:
                raise AssertionError(ultimo['erro'])
            if predicado(ultimo):
                return ultimo
        except (OSError, error.URLError, http.client.RemoteDisconnected):
            pass
        time.sleep(0.12)
    raise AssertionError(f'Condição não alcançada: {json.dumps(ultimo, ensure_ascii=False)[-5000:]}')


def limpar(**config):
    anterior = estado()['geracao']
    assert chamar('/laboratorio/reiniciar', 'POST', {})[0] == 202
    aguardar(lambda s: s['geracao'] > anterior and not s['reiniciando']
             and s['pedidos'] == [] and s['estoque'] is not None)
    assert chamar('/laboratorio/config', 'PUT', {**DEFAULT, **config})[0] == 200


def criar(chave='compra-A', quantidade=2, produto='produto-1'):
    return chamar('/api/pedidos/pedidos', 'POST', {'produtoId': produto, 'quantidade': quantidade}, chave)


def principal():
    resultados = []
    def ok(texto):
        resultados.append(texto)
        print(f'OK: {texto}', flush=True)

    aguardar(lambda s: not s['reiniciando'] and s['pedidos'] == [] and s['estoque'] is not None)
    assert chamar('/laboratorio/config', 'PUT', {'resultado': 'INVALIDO'})[0] == 400
    assert chamar('/laboratorio/config', 'PUT', {'atrasoPagamentoMs': -1})[0] == 400
    assert chamar('/laboratorio/reiniciar', 'POST', {}, origem='https://outro.example')[0] == 403
    ok('configuração inválida e alterações vindas de outra origem são rejeitadas')

    status, pedido = criar()
    assert status == 201 and pedido['status'] == 'CONFIRMADO', (status, pedido)
    assert criar()[1] == pedido
    assert criar(quantidade=5)[0] == 409
    assert criar('compra-B')[1]['pedidoId'] != pedido['pedidoId']
    s = aguardar(lambda s: len(s['pedidos'] or []) == 2 and s['estoque']['disponivel'] == 6)
    assert len(s['pagamentos']) == 2 and len(s['reservas']) == 2
    assert criar('sem-saldo', 99)[0] == 409
    assert criar('zero', 0)[0] == 400
    assert criar('fracao', 2.5)[0] == 400
    assert criar(None)[0] == 400
    assert criar('desconhecido', 2, 'nao-existe')[0] == 409
    ok('sucesso, repetição, conflito, nova compra, saldo insuficiente e validação')

    limpar(resultado='RECUSADO')
    status, pedido = criar()
    assert status == 201 and pedido['status'] == 'CANCELADO'
    with ThreadPoolExecutor(max_workers=2) as pool:
        respostas = list(pool.map(lambda _: chamar(f"/api/estoque/reservas/{pedido['pedidoId']}", 'DELETE'), range(2)))
    assert all(r[0] == 200 and r[1]['status'] == 'CANCELADA' for r in respostas)
    s = aguardar(lambda s: s['estoque']['disponivel'] == 10 and len(s['reservas'] or []) == 1)
    assert s['reservas'][0]['status'] == 'CANCELADA'
    ok('recusa compensa estoque; devoluções repetidas mantêm saldo 10')

    limpar(atrasoPagamentoMs=5000)
    with ThreadPoolExecutor(max_workers=1) as pool:
        primeira = pool.submit(criar)
        aguardar(lambda s: len(s['reservas'] or []) == 1)
        status, repeticao = criar()
        assert status == 202
        status, pendente = primeira.result(timeout=15)
        assert status == 202 and pendente['pedidoId'] == repeticao['pedidoId']
    s = aguardar(lambda s: len(s['pedidos'] or []) == 1 and s['pedidos'][0]['status'] == 'CONFIRMADO')
    assert s['estoque']['disponivel'] == 8 and len(s['pagamentos']) == 1
    assert any(e['metodo'] == 'GET' and e['servico'] == 'pagamentos' for e in s['eventos'])
    ok('timeout e chamadas simultâneas preservam um pedido e uma cobrança; recuperação consulta resultado')

    limpar(atrasoReservaMs=5000)
    status, pedido = criar()
    assert status == 202 and pedido['status'] == 'EM_PROCESSAMENTO'
    s = aguardar(lambda s: len(s['pedidos'] or []) == 1 and s['pedidos'][0]['status'] == 'CONFIRMADO')
    assert s['estoque']['disponivel'] == 8 and len(s['reservas']) == 1 and len(s['pagamentos']) == 1
    consultas = [e for e in s['eventos'] if e['servico'] == 'estoque' and e['metodo'] == 'GET']
    assert any(e['caminho'] == f"/reservas/{pedido['pedidoId']}" for e in consultas)
    assert sum(e['servico'] == 'estoque' and e['metodo'] == 'POST' for e in s['eventos']) == 1
    ok('timeout da reserva: consulta identifica o desconto já efetuado, sem reservar novamente')

    for chave, pendente, saldo in [('pagamentosOffline', 'PENDENTE', 8), ('estoqueOffline', 'EM_PROCESSAMENTO', 10)]:
        limpar(**{chave: True})
        status, pedido = criar()
        assert status == 202 and pedido['status'] == pendente
        s = aguardar(lambda s: s['estoque']['disponivel'] == saldo and len(s['pedidos'] or []) == 1)
        assert len(s['pagamentos']) == 0
        assert chamar('/laboratorio/config', 'PUT', DEFAULT)[0] == 200
        s = aguardar(lambda s: s['pedidos'][0]['status'] == 'CONFIRMADO')
        assert s['estoque']['disponivel'] == 8 and len(s['pagamentos']) == 1
        ok(f'{chave}: indisponibilidade e retomada automática sem duplicidade')

    limpar(resultado='RECUSADO', falharDevolucao=True)
    status, pedido = criar()
    assert status == 202 and pedido['status'] == 'CANCELAMENTO_PENDENTE'
    s = aguardar(lambda s: s['estoque']['disponivel'] == 8 and len(s['reservas'] or []) == 1)
    assert s['reservas'][0]['status'] == 'RESERVADA'
    assert chamar('/laboratorio/config', 'PUT', DEFAULT)[0] == 200
    s = aguardar(lambda s: s['pedidos'][0]['status'] == 'CANCELADO')
    assert s['estoque']['disponivel'] == 10 and s['pagamentos'][0]['status'] == 'RECUSADO'
    ok('devolução bloqueada mantém cancelamento pendente; normalização confirma compensação')

    limpar(resultado='RECUSADO', atrasoDevolucaoMs=5000)
    status, pedido = criar()
    assert status == 202 and pedido['status'] == 'CANCELAMENTO_PENDENTE'
    s = aguardar(lambda s: s['estoque']['disponivel'] == 10 and s['reservas'][0]['status'] == 'CANCELADA')
    s = aguardar(lambda s: s['pedidos'][0]['status'] == 'CANCELADO')
    assert s['estoque']['disponivel'] == 10
    assert sum(e['metodo'] == 'DELETE' for e in s['eventos']) >= 2
    ok('resposta de devolução atrasada: reserva já cancelada é reconhecida sem novo crédito')

    limpar(perderRespostaPedido=True)
    try:
        criar()
        raise AssertionError('A resposta deveria ter sido descartada.')
    except (http.client.RemoteDisconnected, ConnectionError, error.URLError):
        pass
    s = aguardar(lambda s: len(s['pedidos'] or []) == 1 and s['pedidos'][0]['status'] == 'CONFIRMADO')
    id_original = s['pedidos'][0]['pedidoId']
    status, repetido = criar()
    assert status == 200 and repetido['pedidoId'] == id_original
    s = aguardar(lambda s: any(e['status'] == 'SEM_RESPOSTA' for e in s['eventos']))
    assert s['estoque']['disponivel'] == 8 and len(s['pagamentos']) == 1
    ok('resposta perdida ao cliente; repetição recupera o pedido já criado')

    limpar()
    with ThreadPoolExecutor(max_workers=20) as pool:
        respostas = list(pool.map(lambda i: criar(f'corrida-{i}', 1), range(20)))
    s = aguardar(lambda s: len(s['pedidos'] or []) == 20 and all(p['status'] in ('CONFIRMADO', 'RECUSADO') for p in s['pedidos']))
    assert sum(p['status'] == 'CONFIRMADO' for p in s['pedidos']) == 10
    assert sum(p['status'] == 'RECUSADO' for p in s['pedidos']) == 10
    assert s['estoque']['disponivel'] == 0 and len(s['pagamentos']) == 10
    ok('20 compras concorrentes: 10 confirmadas, 10 recusadas, saldo zero')

    limpar()
    s = estado()
    assert s['pedidos'] == [] and s['reservas'] == [] and s['pagamentos'] == []
    assert s['estoque']['disponivel'] == 10 and s['config'] == DEFAULT
    ok('reiniciar limpa os dados em memória e restaura o estoque e os controles')
    (ROOT / '.laboratorio' / 'verificacao-http.json').write_text(json.dumps({'aprovados':resultados,'total':len(resultados)}, ensure_ascii=False, indent=2))
    print(f'{len(resultados)} grupos de cenários aprovados.', flush=True)


if __name__ == '__main__':
    pasta = ROOT / '.laboratorio'
    pasta.mkdir(exist_ok=True)
    with open(pasta / 'teste-servidor.log', 'w') as log:
        processo = subprocess.Popen([sys.executable, str(Path(__file__).with_name('servidor.py')), '--sem-build'],
                                    cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            time.sleep(0.4)
            if processo.poll() is not None:
                raise RuntimeError('O laboratório de teste não iniciou. As portas 8080–8083 devem estar livres.')
            principal()
        finally:
            if processo.poll() is None:
                processo.send_signal(signal.SIGINT)
                try:
                    processo.wait(timeout=25)
                except subprocess.TimeoutExpired:
                    os.killpg(processo.pid, signal.SIGTERM)
                    processo.wait(timeout=10)
