#!/usr/bin/env python3
"""Laboratório local: interface, supervisão dos três JVMs e falhas HTTP controladas."""
from __future__ import annotations

import argparse
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import subprocess
import threading
import time
from urllib import error, request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
WEB = Path(__file__).resolve().parent / 'web'
PORTAS = {'pedidos': 8081, 'estoque': 8082, 'pagamentos': 8083}
DEFAULT = {'resultado': 'APROVADO', 'estoqueOffline': False, 'pagamentosOffline': False,
           'falharDevolucao': False, 'atrasoPagamentoMs': 0, 'atrasoReservaMs': 0, 'atrasoDevolucaoMs': 0,
           'perderRespostaPedido': False}
HTTP = request.build_opener(request.ProxyHandler({}))


def remoto(servico, caminho, metodo='GET', corpo=None, headers=None, timeout=8):
    dados = None if corpo is None else (corpo if isinstance(corpo, bytes) else json.dumps(corpo).encode())
    cabecalhos = {'Content-Type': 'application/json', **(headers or {})}
    req = request.Request(f'http://127.0.0.1:{PORTAS[servico]}{caminho}', data=dados,
                          headers=cabecalhos, method=metodo)
    try:
        resposta = HTTP.open(req, timeout=timeout)
    except error.HTTPError as exc:
        resposta = exc
    with resposta:
        return resposta.status, resposta.read(), dict(resposta.headers)


def ler_json(servico, caminho):
    try:
        status, corpo, _ = remoto(servico, caminho, timeout=0.6)
        return json.loads(corpo) if status == 200 else None
    except (OSError, error.URLError, ValueError):
        return None


class Laboratorio:
    def __init__(self, porta):
        self.porta = porta
        self.lock = threading.RLock()
        self.lifecycle = threading.Lock()
        self.config_lock = threading.Lock()
        self.snapshot_lock = threading.Lock()
        self.config = dict(DEFAULT)
        self.eventos = deque(maxlen=400)
        self.sequencia = 0
        self.geracao = 0
        self.reiniciando = True
        self.erro = None
        self.processos = {}
        self.cache = None
        self.cache_em = 0
        self.fechando = threading.Event()
        self.pasta = ROOT / '.laboratorio'
        self.pasta.mkdir(exist_ok=True)

    def validar_portas(self):
        for porta in PORTAS.values():
            with socket.socket() as sock:
                sock.settimeout(0.2)
                if sock.connect_ex(('127.0.0.1', porta)) == 0:
                    raise RuntimeError(f'A porta {porta} já está ocupada. Encerre essa execução antes de abrir o laboratório; nenhum processo existente será encerrado por este programa.')

    def iniciar_servicos(self):
        self.validar_portas()
        for nome, porta in PORTAS.items():
            jar = ROOT / nome / 'target' / f'{nome}-0.0.1-SNAPSHOT.jar'
            log = open(self.pasta / f'{nome}.log', 'wb')
            args = ['java', '-jar', str(jar), '--spring.profiles.active=laboratorio',
                    '--server.address=127.0.0.1', f'--server.port={porta}', '--spring.main.banner-mode=off']
            if nome == 'pedidos':
                args += [f'--servicos.estoque-url=http://127.0.0.1:{self.porta}/api/estoque',
                         f'--servicos.pagamentos-url=http://127.0.0.1:{self.porta}/api/pagamentos',
                         '--servicos.timeout-ms=2000', '--recuperacao.automatica=true',
                         '--recuperacao.intervalo-ms=2000']
            if nome == 'pagamentos':
                args += ['--simulacao.resultado=APROVADO', '--simulacao.atraso-resposta-ms=0']
            processo = subprocess.Popen(args, cwd=ROOT, stdin=subprocess.DEVNULL,
                                        stdout=log, stderr=subprocess.STDOUT)
            with self.lock:
                self.processos[nome] = (processo, log)
        limite = time.monotonic() + 30
        while time.monotonic() < limite and not self.fechando.is_set():
            with self.lock:
                for nome, (processo, _) in self.processos.items():
                    if processo.poll() is not None:
                        raise RuntimeError(f'{nome} encerrou na inicialização. Consulte .laboratorio/{nome}.log.')
            pronto = (ler_json('pedidos', '/pedidos') is not None
                      and ler_json('estoque', '/reservas') is not None
                      and ler_json('pagamentos', '/pagamentos') is not None)
            if pronto:
                return
            self.fechando.wait(0.2)
        raise RuntimeError('Os serviços não iniciaram a tempo. Consulte os logs em .laboratorio/.')

    def parar_servicos(self):
        with self.lock:
            processos = list(self.processos.values())
            self.processos = {}
        for processo, _ in processos:
            if processo.poll() is None:
                processo.terminate()
        for processo, log in processos:
            try:
                processo.wait(timeout=8)
            except subprocess.TimeoutExpired:
                processo.kill()
                processo.wait(timeout=5)
            log.close()

    def reiniciar(self):
        if not self.lifecycle.acquire(blocking=False):
            return False
        with self.lock:
            self.reiniciando = True
            self.geracao += 1
            self.erro = None
            self.cache = None
        def trabalho():
            try:
                with self.config_lock:
                    self.parar_servicos()
                    with self.lock:
                        self.config = dict(DEFAULT)
                        self.eventos.clear()
                    if not self.fechando.is_set():
                        self.iniciar_servicos()
            except Exception as exc:
                self.parar_servicos()
                with self.lock:
                    self.erro = str(exc)
            finally:
                with self.lock:
                    self.reiniciando = False
                    self.cache = None
                self.lifecycle.release()
        threading.Thread(target=trabalho, daemon=True).start()
        return True

    def configurar(self, dados):
        if not isinstance(dados, dict) or set(dados) - set(DEFAULT):
            raise ValueError('Configuração desconhecida.')
        with self.config_lock:
            with self.lock:
                if self.reiniciando:
                    raise ValueError('Aguarde a inicialização dos serviços.')
                novo = {**self.config, **dados}
            if novo['resultado'] not in ('APROVADO', 'RECUSADO'):
                raise ValueError('Resultado precisa ser APROVADO ou RECUSADO.')
            for chave in ('estoqueOffline', 'pagamentosOffline', 'falharDevolucao', 'perderRespostaPedido'):
                if type(novo[chave]) is not bool:
                    raise ValueError('As opções de falha precisam ser booleanas.')
            for chave in ('atrasoPagamentoMs', 'atrasoReservaMs', 'atrasoDevolucaoMs'):
                if type(novo[chave]) is not int or not 0 <= novo[chave] <= 15000:
                    raise ValueError('O atraso precisa estar entre 0 e 15000 milissegundos.')
            status, _, _ = remoto('pagamentos', '/laboratorio/resultado', 'PUT', {'resultado': novo['resultado']})
            if status != 200:
                raise RuntimeError('Não foi possível configurar o simulador Java de pagamentos.')
            with self.lock:
                self.config = novo
                self.cache = None
            return dict(novo)

    def evento(self, servico, caminho, metodo, corpo, chave):
        with self.lock:
            self.sequencia += 1
            item = {'id': self.sequencia, 'geracao': self.geracao, 'instante': time.time(),
                    'servico': servico, 'caminho': caminho, 'metodo': metodo,
                    'chave': chave, 'requisicao': corpo, 'status': None, 'resposta': None,
                    'duracaoMs': None, 'nota': 'Em andamento', 'finalizado': False}
            self.eventos.append(item)
            return item

    def atualizar_evento(self, evento, **dados):
        with self.lock:
            if evento['geracao'] == self.geracao:
                evento.update(dados)

    def estado(self):
        with self.snapshot_lock:
            with self.lock:
                if self.cache is not None and time.monotonic() - self.cache_em < 0.35:
                    return self.cache
                geracao = self.geracao
                reiniciando = self.reiniciando
            resultados = [None] * 4
            if not reiniciando:
                with ThreadPoolExecutor(max_workers=4) as pool:
                    futuros = [pool.submit(ler_json, s, p) for s, p in (
                        ('pedidos', '/pedidos'), ('estoque', '/estoque/produto-1'),
                        ('estoque', '/reservas'), ('pagamentos', '/pagamentos'))]
                    resultados = [f.result() for f in futuros]
            with self.lock:
                # Uma leitura de uma geração anterior nunca alimenta o novo cenário.
                if geracao != self.geracao:
                    resultados = [None] * 4
                dados = {'geracao': self.geracao, 'reiniciando': self.reiniciando,
                         'erro': self.erro, 'config': dict(self.config),
                         'pedidos': resultados[0], 'estoque': resultados[1],
                         'reservas': resultados[2], 'pagamentos': resultados[3],
                         'eventos': [dict(e) for e in self.eventos],
                         'servicos': {nome: {'porta': porta,
                           'ativo': nome in self.processos and self.processos[nome][0].poll() is None}
                           for nome, porta in PORTAS.items()}}
                self.cache = dados
                self.cache_em = time.monotonic()
                return dados

    def fechar(self):
        self.fechando.set()
        with self.lifecycle:
            self.parar_servicos()


class Servidor(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 128


class Handler(BaseHTTPRequestHandler):
    server_version = 'KipperLab/1.0'

    @property
    def lab(self):
        return self.server.lab

    def log_message(self, *args):
        pass

    def enviar(self, status, corpo, tipo='application/json; charset=utf-8', headers=None):
        dados = corpo if isinstance(corpo, bytes) else json.dumps(corpo, ensure_ascii=False).encode()
        try:
            self.send_response(status)
            self.send_header('Content-Type', tipo)
            self.send_header('Content-Length', str(len(dados)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'same-origin')
            self.send_header('Content-Security-Policy', "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")
            for chave, valor in (headers or {}).items():
                if chave.lower() in ('location', 'retry-after'):
                    self.send_header(chave, valor)
            self.end_headers()
            self.wfile.write(dados)
        except (BrokenPipeError, ConnectionResetError, OSError):
            return False
        return True

    def problema(self, status, mensagem):
        self.enviar(status, {'status': status, 'detail': mensagem})

    def body(self):
        transferencia = self.headers.get('Transfer-Encoding', '').lower()
        if transferencia:
            if transferencia != 'chunked' or self.headers.get('Content-Length') is not None:
                raise ValueError('Codificação de transporte inválida.')
            partes = []
            total = 0
            while True:
                linha = self.rfile.readline(1024)
                tamanho = int(linha.split(b';', 1)[0].strip(), 16)
                if tamanho == 0:
                    limite_trailers = 0
                    while True:
                        trailer = self.rfile.readline(1024)
                        limite_trailers += len(trailer)
                        if trailer in (b'\r\n', b'\n', b''):
                            break
                        if limite_trailers > 8192:
                            raise ValueError('Cabeçalhos finais excedem o limite.')
                    return b''.join(partes)
                total += tamanho
                if tamanho < 0 or total > 16384:
                    raise ValueError('O corpo deve ter no máximo 16 KiB.')
                parte = self.rfile.read(tamanho)
                if len(parte) != tamanho or self.rfile.read(2) != b'\r\n':
                    raise ValueError('Corpo HTTP incompleto.')
                partes.append(parte)
        tamanho = int(self.headers.get('Content-Length', '0'))
        if tamanho < 0 or tamanho > 16384:
            raise ValueError('O corpo deve ter no máximo 16 KiB.')
        return self.rfile.read(tamanho) if tamanho else b''

    def do_GET(self):
        self.tratar()

    def do_POST(self):
        self.tratar()

    def do_PUT(self):
        self.tratar()

    def do_DELETE(self):
        self.tratar()

    def tratar(self):
        try:
            hosts = {f'127.0.0.1:{self.lab.porta}', f'localhost:{self.lab.porta}'}
            if self.headers.get('Host') not in hosts:
                self.problema(403, 'Use o endereço local do laboratório.')
                return
            if self.command != 'GET':
                origem = self.headers.get('Origin')
                if (origem and origem not in {f'http://{h}' for h in hosts}) or self.headers.get('Sec-Fetch-Site') == 'cross-site':
                    self.problema(403, 'A alteração deve partir do próprio laboratório.')
                    return
            caminho = urlsplit(self.path).path
            if caminho == '/laboratorio/estado' and self.command == 'GET':
                self.enviar(200, self.lab.estado())
            elif caminho == '/laboratorio/config' and self.command == 'PUT':
                if not self.headers.get('Content-Type', '').startswith('application/json'):
                    self.problema(415, 'Envie JSON.')
                    return
                dados = json.loads(self.body())
                self.enviar(200, self.lab.configurar(dados))
            elif caminho == '/laboratorio/reiniciar' and self.command == 'POST':
                if not self.headers.get('Content-Type', '').startswith('application/json'):
                    self.problema(415, 'Envie JSON.')
                    return
                self.body()
                iniciado = self.lab.reiniciar()
                self.enviar(202, {'reiniciando': True, 'iniciado': iniciado})
            elif caminho.startswith('/api/'):
                self.proxy(caminho)
            elif caminho in ('/', '/app.css', '/app.js', '/favicon.svg') and self.command == 'GET':
                nome = 'index.html' if caminho == '/' else caminho[1:]
                tipo = {'html': 'text/html; charset=utf-8', 'css': 'text/css; charset=utf-8',
                        'js': 'text/javascript; charset=utf-8', 'svg': 'image/svg+xml'}[nome.rsplit('.', 1)[1]]
                self.enviar(200, (WEB / nome).read_bytes(), tipo)
            else:
                self.problema(404, 'Caminho não encontrado.')
        except (ValueError, json.JSONDecodeError) as exc:
            self.problema(400, str(exc))
        except (OSError, error.URLError, RuntimeError) as exc:
            self.problema(503, str(exc))

    def proxy(self, caminho):
        partes = caminho.split('/', 3)
        if len(partes) != 4 or partes[2] not in PORTAS:
            self.problema(404, 'Serviço desconhecido.')
            return
        servico = partes[2]
        destino = '/' + partes[3]
        permitidos = {'pedidos': ('/pedidos',), 'estoque': ('/reservas', '/estoque'),
                      'pagamentos': ('/pagamentos',)}[servico]
        if not any(destino == p or destino.startswith(p + '/') for p in permitidos):
            self.problema(404, 'Operação não disponível no painel.')
            return
        dados = self.body()
        try:
            corpo = json.loads(dados) if dados else None
        except ValueError:
            corpo = dados.decode(errors='replace')
        chave = self.headers.get('Idempotency-Key')
        inicio = time.monotonic()
        with self.lab.lock:
            if self.lab.reiniciando:
                self.problema(503, 'Laboratório reiniciando. Aguarde os serviços.')
                return
            config = dict(self.lab.config)
            geracao = self.lab.geracao
            perder = servico == 'pedidos' and destino == '/pedidos' and self.command == 'POST' and config['perderRespostaPedido']
            devolver = servico == 'estoque' and destino.startswith('/reservas/') and self.command == 'DELETE'
            atraso = config['atrasoPagamentoMs'] if servico == 'pagamentos' and self.command == 'POST' else 0
            if servico == 'estoque' and destino == '/reservas' and self.command == 'POST' and not config['estoqueOffline']:
                atraso = config['atrasoReservaMs']
                self.lab.config['atrasoReservaMs'] = 0
            if devolver and not config['falharDevolucao'] and not config['estoqueOffline']:
                atraso = config['atrasoDevolucaoMs']
                self.lab.config['atrasoDevolucaoMs'] = 0
            if perder:
                self.lab.config['perderRespostaPedido'] = False
            evento = self.lab.evento(servico, destino, self.command, corpo, chave)
        indisponivel = ((servico == 'estoque' and config['estoqueOffline'])
                        or (servico == 'pagamentos' and config['pagamentosOffline'])
                        or (devolver and config['falharDevolucao']))
        if indisponivel:
            resposta = {'status': 503, 'detail': 'Falha HTTP simulada pelo laboratório; a operação não foi encaminhada.'}
            self.lab.atualizar_evento(evento, status=503, resposta=resposta,
                duracaoMs=round((time.monotonic() - inicio) * 1000), finalizado=True, nota='Falha simulada antes da operação')
            self.enviar(503, resposta)
            return
        try:
            with self.lab.lock:
                if geracao != self.lab.geracao or self.lab.reiniciando:
                    self.problema(503, 'O cenário foi reiniciado.')
                    return
            headers = {'Idempotency-Key': chave} if chave is not None else {}
            status, resposta, headers_resposta = remoto(servico, destino, self.command,
                                                      dados if dados else None, headers, timeout=18)
            try:
                conteudo = json.loads(resposta)
            except ValueError:
                conteudo = resposta.decode(errors='replace')
            self.lab.atualizar_evento(evento, resposta=conteudo, status=status,
                nota=f'Operação respondeu; resposta atrasada {atraso / 1000:g}s' if atraso else 'Resposta recebida')
            if atraso:
                limite = time.monotonic() + atraso / 1000
                while time.monotonic() < limite and not self.lab.fechando.is_set():
                    with self.lab.lock:
                        if geracao != self.lab.geracao:
                            return
                    self.lab.fechando.wait(min(0.1, max(0, limite - time.monotonic())))
            if perder:
                self.lab.atualizar_evento(evento, status='SEM_RESPOSTA', statusOrigem=status,
                    nota='Resposta descartada depois da execução real', finalizado=True,
                    duracaoMs=round((time.monotonic() - inicio) * 1000))
                self.close_connection = True
                try:
                    self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.connection.close()
                return
            enviada = self.enviar(status, resposta, headers=headers_resposta)
            self.lab.atualizar_evento(evento, finalizado=True,
                duracaoMs=round((time.monotonic() - inicio) * 1000),
                nota=('Resposta atrasada; efeito já registrado no serviço' if atraso else
                      'Resposta entregue' if enviada else 'Conexão encerrada pelo chamador'))
        except (OSError, error.URLError) as exc:
            resposta = {'status': 503, 'detail': f'Não foi possível obter resposta de {servico}.'}
            self.lab.atualizar_evento(evento, status=503, resposta=resposta, finalizado=True,
                duracaoMs=round((time.monotonic() - inicio) * 1000), nota=type(exc).__name__)
            self.enviar(503, resposta)


def compilar():
    for nome in PORTAS:
        pasta = ROOT / nome
        jar = pasta / 'target' / f'{nome}-0.0.1-SNAPSHOT.jar'
        fontes = [pasta / 'pom.xml', *list((pasta / 'src/main').rglob('*'))]
        if jar.exists() and all(not p.is_file() or p.stat().st_mtime <= jar.stat().st_mtime for p in fontes):
            continue
        print(f'Compilando {nome}…', flush=True)
        resultado = subprocess.run(['./mvnw', '-B', '-ntp', '-DskipTests', 'package'], cwd=pasta,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if resultado.returncode:
            raise RuntimeError(resultado.stdout[-8000:])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--porta', type=int, default=8080)
    parser.add_argument('--sem-build', action='store_true')
    args = parser.parse_args()
    if not 1024 <= args.porta <= 65535 or args.porta in PORTAS.values():
        parser.error('Escolha uma porta entre 1024 e 65535 diferente de 8081–8083.')
    if not args.sem_build:
        compilar()
    for nome in PORTAS:
        if not (ROOT / nome / 'target' / f'{nome}-0.0.1-SNAPSHOT.jar').exists():
            parser.error(f'Compile o projeto {nome} primeiro.')
    lab = Laboratorio(args.porta)
    lab.validar_portas()
    servidor = Servidor(('127.0.0.1', args.porta), Handler)
    servidor.lab = lab
    lab.reiniciar()
    print(f'Laboratório: http://localhost:{args.porta} — Ctrl+C encerra os três serviços.', flush=True)
    try:
        servidor.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        print('\nEncerrando os serviços do laboratório…', flush=True)
    finally:
        lab.fechar()
        servidor.server_close()


if __name__ == '__main__':
    main()
