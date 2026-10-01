// Testes de comportamento em DOM local, sem navegador ou acesso à rede.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
import {webcrypto} from 'node:crypto';
import vm from 'node:vm';

const require = createRequire(process.env.KIPPER_TEST_DEPS
  ? resolve(process.env.KIPPER_TEST_DEPS, 'package.json') : import.meta.url);
const {parseHTML} = require('linkedom');
const html = readFileSync(new URL('web/index.html', import.meta.url), 'utf8');
const code = readFileSync(new URL('web/app.js', import.meta.url), 'utf8');
const config = {resultado:'APROVADO',estoqueOffline:false,pagamentosOffline:false,
  falharDevolucao:false,atrasoPagamentoMs:0,atrasoReservaMs:0,atrasoDevolucaoMs:0,perderRespostaPedido:false};
const initial = () => ({geracao:1,reiniciando:false,erro:null,config:{...config},
  servicos:Object.fromEntries(['pedidos','estoque','pagamentos'].map((name)=>[name,{ativo:true}])),
  pedidos:[],reservas:[],pagamentos:[],estoque:{produtoId:'produto-1',disponivel:10},eventos:[]});
const json = (body,status=200) => new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
const settle = async () => {for(let i=0;i<12;i++) await new Promise(setImmediate);};
const passed=[];
async function test(name, action) {
  await action(); passed.push(name); console.log(`OK: ${name}`);
}
async function harness() {
  const {window,document} = parseHTML(html);
  // LinkeDOM não oferece todos os comportamentos nativos destes controles.
  if (!Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype,'value')?.set) {
    Object.defineProperty(window.HTMLSelectElement.prototype,'value',{
      configurable:true,
      get(){return (this.querySelector('option[selected]') || this.querySelector('option'))?.value ?? '';},
      set(value){for(const option of this.querySelectorAll('option')) option.toggleAttribute('selected',option.value===String(value));}
    });
  }
  if (!('checked' in window.HTMLInputElement.prototype)) {
    Object.defineProperty(window.HTMLInputElement.prototype,'checked',{
      configurable:true,get(){return this.hasAttribute('checked');},set(value){this.toggleAttribute('checked',Boolean(value));}
    });
  }
  const dialog=document.getElementById('reset-dialog');
  dialog.showModal=()=>dialog.setAttribute('open','');
  dialog.close=(value)=>{dialog.returnValue=value;dialog.removeAttribute('open');dialog.dispatchEvent(new window.Event('close'));};
  const lab={snapshot:initial(),calls:[],handler:null,download:null};
  const fetch=async(path,options={})=>{
    const call={path,method:options.method || 'GET',headers:options.headers || {},
      body:options.body===undefined?undefined:JSON.parse(options.body)};
    lab.calls.push(call);
    if(path==='/laboratorio/estado') return json(lab.snapshot);
    if(lab.handler) return lab.handler(call);
    throw new Error(`Chamada não prevista no teste: ${path}`);
  };
  const context=vm.createContext({document,window,fetch,console,crypto:webcrypto,performance,
    Blob,URL:{createObjectURL(blob){lab.download=blob;return 'blob:teste';},revokeObjectURL(){}},
    setTimeout(){return 0;},clearTimeout(){}});
  const evaluate=(source)=>vm.runInContext(source,context);
  evaluate(code);
  await settle();
  const $=(id)=>document.getElementById(id);
  const click=async(id)=>{$(id).dispatchEvent(new window.Event('click',{bubbles:true}));await settle();};
  return {lab,document,window,context,evaluate,$,click};
}

await test('início conectado, 16 cenários e seleção sem criar pedidos',async()=>{
  const h=await harness();
  assert.equal(h.document.querySelectorAll('[data-scenario]').length,16);
  assert.equal(h.$('stock-count').textContent,'10');
  assert.equal(h.$('run-scenario').disabled,false);
  h.document.querySelector('[data-scenario="reserva-timeout"]').click();
  assert.match(h.$('scenario-title').textContent,/Pedidos ainda não sabe/);
  assert(h.lab.calls.every((call)=>call.method==='GET'));
  h.evaluate('running=true;updateButtons()');
  assert(h.$('send-order').disabled && h.$('parallel-order').disabled);
});

await test('formulário envia corpo e chave; 400 aparece como rejeição',async()=>{
  const h=await harness();
  h.lab.handler=()=>json({detail:'Quantidade deve ser positiva'},400);
  h.$('quantity').value='0';h.$('key').value='meu-teste';
  h.$('order-form').dispatchEvent(new h.window.Event('submit',{bubbles:true,cancelable:true}));
  await settle();
  const call=h.lab.calls.find((c)=>c.method==='POST');
  assert.equal(call.path,'/api/pedidos/pedidos');
  assert.deepEqual(call.body,{produtoId:'produto-1',quantidade:0});
  assert.equal(call.headers['Idempotency-Key'],'meu-teste');
  assert(h.$('request-result').classList.contains('error'));
  assert.match(h.$('response-json').textContent,/"http": 400/);
  h.$('omit-key').checked=true;
  await h.click('repeat-order');
  assert.equal(h.lab.calls.filter((c)=>c.method==='POST').at(-1).headers['Idempotency-Key'],undefined);
});

await test('duas chamadas preservam a chave; nova chave expressa outra compra',async()=>{
  const h=await harness();
  h.lab.handler=()=>json({pedidoId:'pedido-A',status:'PENDENTE',quantidade:2},202);
  await h.click('parallel-order');
  const posts=h.lab.calls.filter((c)=>c.method==='POST');
  assert.equal(posts.length,2);
  assert.equal(posts[0].headers['Idempotency-Key'],posts[1].headers['Idempotency-Key']);
  assert.deepEqual(posts[0].body,posts[1].body);
  assert(h.$('request-result').classList.contains('pending'));
  const previous=h.$('key').value;
  await h.click('new-key');
  assert.notEqual(h.$('key').value,previous);
});

await test('sem resposta informa incerteza; resposta antiga não substitui cenário reiniciado',async()=>{
  const h=await harness();
  h.lab.handler=()=>{throw new TypeError('Falha de rede simulada');};
  await h.evaluate('postOrder()');
  assert.match(h.$('request-result').textContent,/não prova que o pedido falhou/);
  let finish;
  h.lab.handler=()=>new Promise((resolve)=>{finish=resolve;});
  const pending=h.evaluate('postOrder()');
  h.lab.snapshot={...initial(),geracao:2};
  await h.evaluate('refresh()');
  finish(json({pedidoId:'antigo',status:'CONFIRMADO'},201));
  await pending;
  assert.equal(h.$('response-details').hidden,true);
  assert.equal(h.evaluate('selectedOrder'),null);
});

await test('controles de falha enviam tipos corretos e normalizar restaura configuração',async()=>{
  const h=await harness();
  h.lab.handler=(call)=>{assert.equal(call.path,'/laboratorio/config');h.lab.snapshot.config=call.body;return json(call.body);};
  h.$('payment-result').value='RECUSADO';h.$('reserve-delay').value='5000';h.$('refund-failure').checked=true;
  h.$('fault-fields').dispatchEvent(new h.window.Event('change',{bubbles:true}));
  await settle();
  const call=h.lab.calls.find((c)=>c.method==='PUT');
  assert.equal(call.body.resultado,'RECUSADO');
  assert.equal(call.body.atrasoReservaMs,5000);
  assert.equal(call.body.falharDevolucao,true);
  await h.click('normalize');
  assert.deepEqual(h.lab.snapshot.config,config);
  assert.equal(h.$('fault-fields').disabled,false);
});

await test('inspeção mostra divergência temporária, protege conteúdo e repete devolução pelo mesmo ID',async()=>{
  const h=await harness();
  const payload='<img src=x onerror="alert(1)">';
  h.lab.snapshot.pedidos=[{pedidoId:'pedido-A',status:'CANCELAMENTO_PENDENTE',quantidade:2,mensagem:payload}];
  h.lab.snapshot.reservas=[{pedidoId:'pedido-A',status:'CANCELADA',quantidade:2,produtoId:payload}];
  h.lab.snapshot.pagamentos=[{pedidoId:'pedido-A',pagamentoId:'pagamento-A',status:'RECUSADO'}];
  h.lab.snapshot.eventos=[{id:1,instante:Date.now()/1000,servico:'pedidos',metodo:'POST',caminho:'/pedidos',
    status:202,duracaoMs:2000,finalizado:true,nota:payload,chave:'compra-A',resposta:{status:'CANCELAMENTO_PENDENTE'},requisicao:{produtoId:payload}}];
  await h.evaluate('refresh()');
  assert.match(h.$('inspector-content').textContent,/Cancelamento pendente/);
  assert.match(h.$('inspector-content').textContent,/Cancelada/);
  assert.equal(h.$('repeat-refund'),null);
  assert.equal(h.document.querySelectorAll('img').length,0);
  assert.match(h.$('timeline').textContent,/<img src=x/);
  h.lab.snapshot.pedidos[0].status='CANCELADO';
  await h.evaluate('refresh()');
  h.lab.handler=(call)=>{assert.equal(call.path,'/api/estoque/reservas/pedido-A');return json(h.lab.snapshot.reservas[0]);};
  await h.click('repeat-refund');
  assert.equal(h.lab.calls.filter((c)=>c.method==='DELETE').length,2);
  assert.match(h.$('request-result').textContent,/10 unidades/);
  h.evaluate('chooseScenario("devolucao-dupla");activeExperiment={id:"devolucao-dupla",geracao:state.geracao,seen:new Set()};renderChecks()');
  assert.equal(h.document.querySelectorAll('#checks .pass').length,3);
});

await test('reinício exige decisão quando há dados e exportação preserva evidências',async()=>{
  const h=await harness();
  h.lab.snapshot.pedidos=[{pedidoId:'pedido-A',status:'CONFIRMADO',quantidade:2,mensagem:'Confirmado'}];
  await h.evaluate('refresh()');
  const cancelled=h.evaluate('confirmReset()');
  assert(h.$('reset-dialog').hasAttribute('open'));
  h.$('reset-dialog').close('cancel');assert.equal(await cancelled,false);
  const accepted=h.evaluate('confirmReset()');
  h.$('reset-dialog').close('confirm');assert.equal(await accepted,true);
  assert(!h.lab.calls.some((call)=>call.path==='/laboratorio/reiniciar'));
  await h.click('export');
  const evidence=JSON.parse(await h.lab.download.text());
  assert.equal(evidence.pedidos[0].pedidoId,'pedido-A');
  assert.equal(evidence.geracao,1);
});

await test('desconexão desabilita ações e permite recuperar o painel',async()=>{
  const h=await harness();
  h.context.fetch=async()=>{throw new Error('offline');};
  await h.evaluate('refresh()');
  assert.equal(h.$('connection').textContent,'Laboratório desconectado');
  assert(h.$('send-order').disabled && h.$('run-scenario').disabled);
  h.context.fetch=async()=>json(h.lab.snapshot);
  await h.evaluate('refresh()');
  assert.equal(h.$('run-scenario').disabled,false);
  assert.equal(h.$('global-message').hidden,true);
});

console.log(`${passed.length} grupos de comportamento da interface aprovados (sem validação visual).`);
