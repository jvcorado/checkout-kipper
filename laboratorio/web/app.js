'use strict';
const $ = (id) => document.getElementById(id);
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const defaults = {resultado:'APROVADO',estoqueOffline:false,pagamentosOffline:false,falharDevolucao:false,atrasoPagamentoMs:0,atrasoReservaMs:0,atrasoDevolucaoMs:0,perderRespostaPedido:false};
const scenarios = [
  {id:'sucesso',group:'O fluxo do checkout',name:'Compra aprovada',title:'Uma compra, três serviços',description:'Reserve 2 unidades, simule uma cobrança aprovada e acompanhe a confirmação do pedido.',expect:['Pedido confirmado','Estoque final: 8','Uma cobrança'],config:{}},
  {id:'sem-estoque',group:'O fluxo do checkout',name:'Estoque insuficiente',title:'Sem estoque, sem cobrança',description:'Solicite 11 unidades quando só existem 10. A reserva deve ser recusada antes de Pagamentos ser chamado.',expect:['HTTP 409','Estoque final: 10','Nenhuma cobrança'],config:{}},
  {id:'recusa',group:'O fluxo do checkout',name:'Pagamento recusado',title:'Recusar também exige compensar',description:'O pagamento será recusado depois da reserva. Pedidos solicita a devolução e só então conclui o cancelamento.',expect:['Pedido cancelado','Reserva cancelada','Estoque final: 10'],config:{resultado:'RECUSADO'}},
  {id:'timeout',group:'Comunicação e recuperação',name:'Timeout no pagamento',title:'A resposta atrasou. A cobrança aconteceu.',description:'A cobrança é registrada, mas sua resposta leva 5 segundos. Pedidos espera 2, retorna pendente e recupera o resultado por consulta.',expect:['HTTP 202 primeiro','Consulta automática','Uma única cobrança'],config:{atrasoPagamentoMs:5000}},
  {id:'pagamento-offline',group:'Comunicação e recuperação',name:'Pagamentos indisponível',title:'Um serviço falhou. O pedido continua existindo.',description:'As chamadas a Pagamentos recebem 503. Observe o pedido pendente e clique em “Normalizar serviços” para permitir sua recuperação.',expect:['Estoque reservado','Nenhuma cobrança enquanto bloqueado','Recuperação após normalizar'],config:{pagamentosOffline:true}},
  {id:'estoque-offline',group:'Comunicação e recuperação',name:'Estoque indisponível',title:'A cobrança precisa esperar pela reserva',description:'Bloqueie as chamadas a Estoque. Pedidos não pode cobrar antes de confirmar a reserva. Use “Normalizar serviços” para continuar.',expect:['Pedido em processamento','Saldo preservado','Sem cobrança antecipada'],config:{estoqueOffline:true}},
  {id:'reserva-timeout',group:'Comunicação e recuperação',name:'Timeout na reserva',title:'O saldo mudou, mas Pedidos ainda não sabe',description:'Atrase a primeira resposta de reserva em 5 segundos, depois de descontar 2 unidades. Pedidos deve consultar a reserva antes de cobrar, sem descontar novamente.',expect:['HTTP 202 primeiro','Consulta à mesma reserva','Estoque final: 8'],config:{atrasoReservaMs:5000}},
  {id:'devolucao-falha',group:'Comunicação e recuperação',name:'Falha na devolução',title:'Cancelamento solicitado não é cancelamento concluído',description:'Recuse o pagamento e bloqueie apenas a devolução. O pedido fica com cancelamento pendente até você normalizar os serviços.',expect:['Cancelamento pendente','Saldo 8 enquanto bloqueado','Saldo 10 após recuperar'],config:{resultado:'RECUSADO',falharDevolucao:true}},
  {id:'devolucao-timeout',group:'Comunicação e recuperação',name:'Devolução com resposta perdida',title:'O estoque já voltou. A confirmação ainda não.',description:'Atrase a primeira resposta de devolução em 5 segundos, depois de restaurar o saldo. A repetição automática deve reconhecer a reserva cancelada.',expect:['Cancelamento recuperado','Saldo final: 10, nunca 12','Mesma reserva'],config:{resultado:'RECUSADO',atrasoDevolucaoMs:5000}},
  {id:'resposta-perdida',group:'Comunicação e recuperação',name:'Cliente sem resposta',title:'A compra terminou, mas o cliente não sabe',description:'Descarte a resposta depois de o pedido ser processado. Depois clique em “Reenviar mesma chave” para recuperar o mesmo pedido.',expect:['Erro de conexão no cliente','Pedido existe no servidor','Reenviar sem duplicar'],config:{perderRespostaPedido:true}},
  {id:'duplicidade',group:'Idempotência e concorrência',name:'Duas chamadas, mesma chave',title:'Duas tentativas. Uma única compra.',description:'Envie duas requisições simultâneas com compra-A. A resposta do pagamento fica atrasada para permitir observar o pedido em andamento.',expect:['Mesmo pedidoId','Repetição retorna 202','Estoque final: 8'],config:{atrasoPagamentoMs:5000}},
  {id:'conflito',group:'Idempotência e concorrência',name:'Mesma chave, outros dados',title:'A chave não autoriza alterar a compra',description:'Crie um pedido de 2 unidades e depois tente usar a mesma chave para comprar 5. O pedido original precisa ser preservado.',expect:['Segunda chamada: 409','Pedido mantém 2 unidades','Estoque final: 8'],config:{}},
  {id:'devolucao-dupla',group:'Idempotência e concorrência',name:'Devolução repetida',title:'Devolver duas vezes não pode somar duas vezes',description:'Cancele um pedido por pagamento recusado e envie mais duas solicitações de devolução simultâneas para a mesma reserva.',expect:['Reserva cancelada','Estoque permanece em 10','Devolução sem duplicar'],config:{resultado:'RECUSADO'}},
  {id:'concorrencia',group:'Idempotência e concorrência',name:'Disputa pelas últimas unidades',title:'20 pedidos disputam 10 unidades',description:'Envie 20 compras distintas de uma unidade ao mesmo tempo. A reserva atômica deve impedir que o saldo fique negativo.',expect:['10 confirmados','10 recusados','Estoque final: 0'],config:{}},
  {id:'validacao',group:'Contratos e limites',name:'Requisições inválidas',title:'O contrato começa na entrada',description:'Teste quantidade zero, quantidade fracionária e ausência de chave. Essas três requisições devem ser rejeitadas antes de criar pedidos.',expect:['Três respostas 400','Nenhum pedido criado','Estoque preservado'],config:{}},
  {id:'memoria',group:'Contratos e limites',name:'Dados em memória',title:'Reiniciar revela o limite deste checkpoint',description:'Crie uma compra. Depois use “Reiniciar laboratório” e observe que pedidos, reservas, pagamentos e chaves desaparecem. O estoque volta a 10.',expect:['Estado existe enquanto o processo vive','Reiniciar apaga os registros','Não simula transações de banco'],config:{}}
];
const statusNames = {EM_PROCESSAMENTO:'Em processamento',PENDENTE:'Pendente',CANCELAMENTO_PENDENTE:'Cancelamento pendente',CONFIRMADO:'Confirmado',CANCELADO:'Cancelado',RECUSADO:'Recusado',RESERVADA:'Reservada',CANCELADA:'Cancelada',APROVADO:'Aprovado'};
const statusClass = (status) => ['CONFIRMADO','APROVADO'].includes(status) ? 'success' : ['PENDENTE','EM_PROCESSAMENTO','CANCELAMENTO_PENDENTE'].includes(status) ? 'pending' : ['RECUSADO'].includes(status) ? 'error' : 'neutral';
const badge = (status) => `<span class="badge ${statusClass(status)}" title="${escapeHtml(status)}">${escapeHtml(statusNames[status] || status)}</span>`;
let selectedScenario = scenarios[0], state = null, selectedOrder = null, running = false, resetting = false, configBusy = false;
let activeExperiment = null, lastResponse = null, requestSequence = 0, orderHash = '', eventHash = '', inspectorHash = '', configHash = '';
const firstSeen = new Map();
const keysByOrder = new Map();
let creationSequence = 0;

function buildNav() {
  let group = '';
  $('scenario-nav').innerHTML = scenarios.map((scenario,index) => {
    const heading = scenario.group !== group ? `<div class="nav-group">${escapeHtml(scenario.group)}</div>` : '';
    group = scenario.group;
    return `${heading}<button type="button" class="nav-item ${selectedScenario.id===scenario.id?'active':''}" data-scenario="${scenario.id}" aria-pressed="${selectedScenario.id===scenario.id}"><span class="nav-num">${String(index+1).padStart(2,'0')}</span><span>${escapeHtml(scenario.name)}</span><span class="nav-arrow" aria-hidden="true">↗</span></button>`;
  }).join('');
}
function chooseScenario(id) {
  selectedScenario = scenarios.find((s) => s.id === id) || scenarios[0];
  document.querySelectorAll('[data-scenario]').forEach((button) => {
    const selected = button.dataset.scenario === selectedScenario.id;
    button.classList.toggle('active',selected); button.setAttribute('aria-pressed',String(selected));
  });
  $('scenario-number').textContent = `EXPERIMENTO ${String(scenarios.indexOf(selectedScenario)+1).padStart(2,'0')}`;
  $('scenario-title').textContent = selectedScenario.title;
  $('scenario-description').textContent = selectedScenario.description;
  $('scenario-expectations').innerHTML = selectedScenario.expect.map((text) => `<span>${escapeHtml(text)}</span>`).join('');
  renderChecks();
}
function ready() {
  return state && !state.reiniciando && !state.erro && !resetting && Object.values(state.servicos).every((s) => s.ativo) && state.pedidos!==null && state.reservas!==null && state.pagamentos!==null;
}
function updateButtons() {
  ['run-scenario','empty-run'].forEach((id) => $(id).disabled = !ready() || running);
  ['send-order','repeat-order','parallel-order'].forEach((id) => $(id).disabled = !ready() || configBusy || running);
  $('normalize').disabled = !ready() || configBusy;
  $('reset').disabled = resetting || running || Boolean(state?.reiniciando);
  $('fault-fields').disabled = !ready() || configBusy;
  $('run-scenario').innerHTML = running ? 'Experimento em execução…' : 'Executar cenário <span aria-hidden="true">↗</span>';
  $('run-hint').textContent = running ? 'Acompanhe os estados e as chamadas abaixo.' : 'Começa com 10 unidades e dados limpos.';
}
function notice(message, bad=false) {
  $('global-message').hidden = !message;
  $('global-message').textContent = message;
  $('global-message').classList.toggle('bad',bad);
}
async function api(path, method='GET', body) {
  const response = await fetch(path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store'});
  const data = await response.json();
  if (!response.ok) throw new Error(data.detail || `HTTP ${response.status}`);
  return data;
}
function showResult(title, message, kind='') {
  $('request-result').className = `request-result ${kind}`;
  $('request-result').innerHTML = `<span class="result-symbol" aria-hidden="true">${kind==='error'?'!':kind==='success'?'✓':'↗'}</span><div><strong>${escapeHtml(title)}</strong><p>${escapeHtml(message)}</p></div>`;
}
function setLastResponse(data) {
  lastResponse = data;
  $('response-details').hidden = false;
  $('response-json').textContent = JSON.stringify(data,null,2);
}
function readOrder() {
  const quantity = $('quantity').value;
  return {produtoId:$('product').value,quantidade:quantity===''?null:Number(quantity)};
}
async function postOrder(body=readOrder(), key=$('omit-key').checked?null:$('key').value) {
  const seq = ++requestSequence;
  const generation = state?.geracao;
  const headers = {'Content-Type':'application/json'};
  if (key!==null) headers['Idempotency-Key'] = key;
  showResult('Enviando pedido','A mesma chave pode ser reenviada enquanto a primeira chamada está em andamento.','pending');
  const start = performance.now();
  try {
    const response = await fetch('/api/pedidos/pedidos',{method:'POST',headers,body:JSON.stringify(body)});
    const data = await response.json();
    const result = {status:response.status,body:data};
    if (generation !== state?.geracao) return result;
    if (data.pedidoId) {
      selectedOrder = data.pedidoId;
      keysByOrder.set(data.pedidoId,key ?? 'Sem chave');
    }
    if (seq === requestSequence) {
      setLastResponse({metodo:'POST',endpoint:'/pedidos',chave:key,requisicao:body,http:response.status,location:response.headers.get('Location'),duracaoMs:Math.round(performance.now()-start),resposta:data});
      showResult(`HTTP ${response.status} · ${data.status ? statusNames[data.status] || data.status : response.ok?'Resposta recebida':'Requisição rejeitada'}`,data.mensagem || data.detail || 'Consulte os registros dos serviços abaixo.',response.status===202?'pending':response.ok?'success':'error');
    }
    await refresh();
    return result;
  } catch (error) {
    if (generation === state?.geracao && seq === requestSequence) {
      setLastResponse({metodo:'POST',endpoint:'/pedidos',chave:key,requisicao:body,http:null,erro:'O cliente não recebeu uma resposta. O resultado da operação pode ser desconhecido.'});
      showResult('O cliente ficou sem resposta','Isso não prova que o pedido falhou. Reenvie com a mesma chave para recuperar o resultado sem criar outra compra.','error');
    }
    await refresh().catch(()=>{});
    return {status:null,body:null,networkError:true};
  }
}
async function setConfig(config) {
  configBusy = true; updateButtons();
  $('config-message').textContent = 'Aplicando a configuração aos próximos pedidos…';
  $('config-message').classList.remove('error');
  try {
    await api('/laboratorio/config','PUT',config);
    configHash = '';
    await refresh();
    $('config-message').textContent = 'Configuração aplicada. Registros já concluídos mantêm seu resultado original.';
  } catch (error) {
    $('config-message').textContent = error.message;
    $('config-message').classList.add('error');
    throw error;
  } finally { configBusy=false; renderConfig(); updateButtons(); }
}
function renderConfig() {
  if (!state || configBusy) return;
  const hash = JSON.stringify(state.config);
  if (hash===configHash) return;
  configHash=hash;
  const cfg = state.config;
  $('payment-result').value=cfg.resultado;
  $('payment-offline').checked=cfg.pagamentosOffline;
  $('stock-offline').checked=cfg.estoqueOffline;
  $('refund-failure').checked=cfg.falharDevolucao;
  $('payment-delay').value=String(cfg.atrasoPagamentoMs);
  $('reserve-delay').value=String(cfg.atrasoReservaMs);
  $('refund-delay').value=String(cfg.atrasoDevolucaoMs);
  $('lose-response').checked=cfg.perderRespostaPedido;
}
function currentConfig() {
  return {resultado:$('payment-result').value,pagamentosOffline:$('payment-offline').checked,estoqueOffline:$('stock-offline').checked,falharDevolucao:$('refund-failure').checked,atrasoPagamentoMs:Number($('payment-delay').value),atrasoReservaMs:Number($('reserve-delay').value),atrasoDevolucaoMs:Number($('refund-delay').value),perderRespostaPedido:$('lose-response').checked};
}
async function confirmReset() {
  if (!(state?.pedidos?.length || state?.eventos?.length)) return true;
  const dialog = $('reset-dialog');
  dialog.returnValue='cancel';
  dialog.showModal();
  return await new Promise((resolve) => dialog.addEventListener('close',()=>resolve(dialog.returnValue==='confirm'),{once:true}));
}
function clearView() {
  selectedOrder=null; activeExperiment=null; firstSeen.clear(); keysByOrder.clear();
  orderHash='';eventHash='';inspectorHash='';configHash=''; lastResponse=null;
  $('response-details').hidden=true;
  showResult('Experimento limpo','O estoque voltou ao início. Você pode fazer uma nova compra.');
}
async function restartLaboratory() {
  resetting=true;updateButtons();notice('Reiniciando os três serviços e preparando 10 unidades de estoque…');
  try {
    const previous=state?.geracao;
    await api('/laboratorio/reiniciar','POST',{});
    const deadline=Date.now()+45000;
    while (Date.now()<deadline) {
      await new Promise((resolve)=>setTimeout(resolve,450));
      await refresh();
      if (state?.erro) throw new Error(state.erro);
      if (state && state.geracao!==previous && !state.reiniciando && state.pedidos!==null && state.estoque!==null && state.pagamentos!==null) {
        clearView();notice('');return;
      }
    }
    throw new Error('Os serviços demoraram para iniciar. Veja os logs da pasta .laboratorio.');
  } finally {resetting=false;updateButtons();}
}
async function runScenario() {
  if (!ready() || running || !await confirmReset()) return;
  const scenario = selectedScenario;
  running=true;updateButtons();
  try {
    await restartLaboratory();
    await setConfig({...defaults,...scenario.config});
    $('product').value='produto-1';$('quantity').value='2';$('key').value='compra-A';$('omit-key').checked=false;
    activeExperiment={id:scenario.id,geracao:state.geracao,seen:new Set()};
    const body={produtoId:'produto-1',quantidade:2};
    if (scenario.id==='sem-estoque') {
      $('quantity').value='11';await postOrder({...body,quantidade:11},'compra-A');
    } else if (scenario.id==='duplicidade') {
      await Promise.all([postOrder(body,'compra-A'),postOrder(body,'compra-A')]);
    } else if (scenario.id==='conflito') {
      await postOrder(body,'compra-A');$('quantity').value='5';await postOrder({...body,quantidade:5},'compra-A');
    } else if (scenario.id==='devolucao-dupla') {
      const result=await postOrder(body,'compra-A');
      if (result.body?.status!=='CANCELADO') throw new Error('O cancelamento precisa terminar antes de repetir a devolução.');
      await repeatRefund(result.body.pedidoId);
    } else if (scenario.id==='concorrencia') {
      $('quantity').value='1';
      await Promise.all(Array.from({length:20},(_,i)=>postOrder({...body,quantidade:1},`disputa-${i+1}`)));
      showResult('20 requisições enviadas','Confira quantos pedidos foram confirmados e quantos foram recusados por falta de estoque.','success');
    } else if (scenario.id==='validacao') {
      await postOrder({...body,quantidade:0},'invalido-zero');
      await postOrder({...body,quantidade:2.5},'invalido-fracao');
      await postOrder(body,null);
    } else {
      await postOrder(body,'compra-A');
    }
    if (['pagamento-offline','estoque-offline','devolucao-falha'].includes(scenario.id)) notice('A falha continua ativa para você observar o estado pendente. Clique em “Normalizar serviços” quando quiser permitir a recuperação.');
    if (scenario.id==='memoria') notice('O estado existe apenas na memória dos serviços. Exporte as evidências e clique em “Reiniciar laboratório” para observar a perda dos registros.');
    await refresh();
  } catch (error) {notice(error.message,true);}
  finally {running=false;updateButtons();}
}
async function repeatRefund(id) {
  const generation=state?.geracao;
  const responses=await Promise.all([0,1].map(async()=>{
    const response=await fetch(`/api/estoque/reservas/${encodeURIComponent(id)}`,{method:'DELETE'});
    return {status:response.status,body:await response.json()};
  }));
  if (generation!==state?.geracao) return;
  await refresh();
  setLastResponse({metodo:'DELETE',endpoint:`/reservas/${id}`,tentativas:responses});
  const success=responses.every((r)=>r.status===200 && r.body.status==='CANCELADA');
  showResult(success?'Devolução repetida sem criar outra operação':'Não foi possível confirmar as duas respostas',success?`As duas chamadas retornaram a mesma reserva cancelada. Confira o saldo: ${state?.estoque?.disponivel ?? 'desconhecido'} unidades.`:'Consulte as respostas e o estado da reserva antes de concluir.',success?'success':'error');
}
function renderServices() {
  const orders=state.pedidos || [], payments=state.pagamentos || [], reservations=state.reservas || [];
  const pending=orders.filter((o)=>['PENDENTE','EM_PROCESSAMENTO','CANCELAMENTO_PENDENTE'].includes(o.status)).length;
  $('count-orders').textContent=state.pedidos===null?'—':orders.length;
  $('orders-extra').textContent=pending?`${pending} aguardando conclusão`:`${orders.filter((o)=>o.status==='CONFIRMADO').length} confirmados`;
  $('stock-count').textContent=state.estoque?.disponivel ?? '—';
  const reserved=reservations.filter((r)=>r.status==='RESERVADA').reduce((total,r)=>total+r.quantidade,0);
  $('stock-extra').textContent=`${reserved} unidades reservadas`;
  $('count-payments').textContent=state.pagamentos===null?'—':payments.length;
  $('payments-extra').textContent=`${payments.filter((p)=>p.status==='APROVADO').length} aprovadas · ${payments.filter((p)=>p.status==='RECUSADO').length} recusadas`;
  for (const service of ['pedidos','estoque','pagamentos']) {
    const health=$(`health-${service}`);
    const blocked=service==='estoque'?state.config.estoqueOffline:service==='pagamentos'?state.config.pagamentosOffline:false;
    const active=state.servicos[service]?.ativo && !state.reiniciando;
    health.className=`health ${!active?'down':blocked?'warn':'up'}`;
    health.textContent=!active?'Iniciando':blocked?'503 simulado':'● Disponível';
    health.title=blocked?'A camada de simulação bloqueia as chamadas. O processo Java continua ativo.':'Estado do processo local';
  }
}
function renderOrders() {
  const orders=[...(state.pedidos || [])];
  for (const event of state.eventos) {
    if (event.servico==='pedidos' && event.metodo==='POST' && event.resposta?.pedidoId) {
      keysByOrder.set(event.resposta.pedidoId,event.chave ?? 'Sem chave');
    }
  }
  for (const order of orders) if (!firstSeen.has(order.pedidoId)) firstSeen.set(order.pedidoId,++creationSequence);
  orders.sort((a,b)=>firstSeen.get(b.pedidoId)-firstSeen.get(a.pedidoId));
  if (!selectedOrder && orders.length) selectedOrder=orders[0].pedidoId;
  if (selectedOrder && !orders.some((o)=>o.pedidoId===selectedOrder)) selectedOrder=orders[0]?.pedidoId ?? null;
  const hash=JSON.stringify([orders,selectedOrder,[...keysByOrder]]);
  if (hash===orderHash) return;
  orderHash=hash;
  $('order-total').textContent=String(orders.length);
  $('orders-empty').hidden=orders.length>0;
  $('orders-body').innerHTML=orders.map((order)=>`<tr class="${order.pedidoId===selectedOrder?'selected':''}"><td><button type="button" class="order-link" data-order="${escapeHtml(order.pedidoId)}" title="${escapeHtml(order.pedidoId)}">${escapeHtml(order.pedidoId.slice(0,8))}<span class="order-key">${escapeHtml(keysByOrder.get(order.pedidoId) || 'Chave ainda não observada')}</span></button></td><td>${escapeHtml(order.quantidade)} un.</td><td>${badge(order.status)}</td><td><button type="button" class="inspect-button" data-order="${escapeHtml(order.pedidoId)}" aria-label="Inspecionar pedido ${escapeHtml(order.pedidoId.slice(0,8))}">↗</button></td></tr>`).join('');
}
function renderInspector() {
  const order=state.pedidos?.find((p)=>p.pedidoId===selectedOrder);
  const reservation=state.reservas?.find((r)=>r.pedidoId===selectedOrder);
  const payment=state.pagamentos?.find((p)=>p.pedidoId===selectedOrder);
  const hash=JSON.stringify([order,reservation,payment]);
  if (hash===inspectorHash) return;
  inspectorHash=hash;
  if (!order) {
    $('inspector-content').innerHTML='<div class="inspector-placeholder"><span aria-hidden="true">◎</span><p>Selecione um pedido para comparar os registros de cada serviço.</p></div>';
    return;
  }
  const item=(number,name,status,description)=>`<div class="state-line"><span class="step-num">${number}</span><div class="state-text"><strong>${name}</strong>${status?badge(status):'<span class="badge neutral">Sem registro</span>'}<p>${escapeHtml(description)}</p></div></div>`;
  $('inspector-content').innerHTML=`<p class="inspector-id">pedidoId<br>${escapeHtml(order.pedidoId)}</p>${item('1','Pedido',order.status,order.mensagem)}${item('2','Reserva',reservation?.status,reservation?`${reservation.quantidade} unidades de ${reservation.produtoId}`:state.reservas===null?'Não foi possível consultar Estoque.':'Nenhuma reserva encontrada para este pedido.')}${item('3','Pagamento',payment?.status,payment?`Cobrança ${payment.pagamentoId.slice(0,8)}`:state.pagamentos===null?'Não foi possível consultar Pagamentos.':'Nenhuma cobrança encontrada para este pedido.')}${order.status==='CANCELADO' && reservation?.status==='CANCELADA'?`<div class="inspector-actions"><p>Teste a idempotência da compensação: repetir a devolução deve preservar o saldo.</p><button class="button secondary small" id="repeat-refund" type="button" data-refund="${escapeHtml(order.pedidoId)}">Repetir devolução (2×)</button></div>`:''}`;
}
function renderTimeline() {
  const events=[...state.eventos].reverse();
  $('event-count').textContent=`${events.length} chamada${events.length===1?'':'s'}`;
  const hash=JSON.stringify(events);
  if (hash===eventHash) return;
  eventHash=hash;
  const open=new Set([...$('timeline').querySelectorAll('details[open]')].map((node)=>node.dataset.event));
  $('timeline').innerHTML=events.length?events.map((event)=>{
    const status=event.status ?? '…';
    const kind=event.status==='SEM_RESPOSTA' || Number(status)>=400?'error':status===202 || !event.finalizado?'pending':'success';
    const time=new Date(event.instante*1000).toLocaleTimeString('pt-BR',{hour12:false});
    const from=event.servico==='pedidos'?'Cliente → Pedidos':`Pedidos / painel → ${event.servico==='estoque'?'Estoque':'Pagamentos'}`;
    return `<details class="event" data-event="${event.id}" ${open.has(String(event.id))?'open':''}><summary><time class="event-time">${escapeHtml(time)}</time><span class="method ${escapeHtml(event.metodo)}">${escapeHtml(event.metodo)}</span><span class="event-service">${escapeHtml(event.servico)}</span><span class="event-route" title="${escapeHtml(event.caminho)}">${escapeHtml(event.caminho)}</span><span class="event-status"><span class="badge ${kind}">${status==='SEM_RESPOSTA'?'Sem resposta':escapeHtml(status)}</span></span><span class="event-duration">${event.duracaoMs===null?'em curso':`${event.duracaoMs} ms`}</span></summary><div class="event-detail"><p>${escapeHtml(from)} · ${escapeHtml(event.nota)}${event.chave?` · chave: ${escapeHtml(event.chave)}`:''}</p><div class="event-json"><div><h3>REQUISIÇÃO</h3><pre>${escapeHtml(JSON.stringify(event.requisicao ?? {metodo:event.metodo,caminho:event.caminho},null,2))}</pre></div><div><h3>RESPOSTA OBSERVADA NA ORIGEM</h3><pre>${escapeHtml(event.resposta===null?'Aguardando resposta do serviço…':JSON.stringify(event.resposta,null,2))}</pre></div></div></div></details>`;
  }).join(''):'<div class="timeline-empty">O histórico começa quando você envia um pedido.</div>';
}
function renderChecks() {
  if (!state || !activeExperiment || activeExperiment.id!==selectedScenario.id || activeExperiment.geracao!==state.geracao) {
    $('checks-card').hidden=true;return;
  }
  const orders=state.pedidos || [], payments=state.pagamentos || [], reservations=state.reservas || [];
  const stock=state.estoque?.disponivel;
  const seen=activeExperiment.seen;
  orders.forEach((o)=>seen.add(o.status));
  state.eventos.filter((e)=>e.servico==='pedidos').forEach((e)=>{
    if (e.status===202) seen.add('HTTP202');
    if (e.status==='SEM_RESPOSTA') seen.add('SEM_RESPOSTA');
    if (e.resposta?.status) seen.add(e.resposta.status);
  });
  const any=(status)=>orders.some((o)=>o.status===status);
  const cancelled=any('CANCELADO') && reservations.some((r)=>r.status==='CANCELADA');
  let checks=[];
  switch (selectedScenario.id) {
    case 'sucesso':case 'memoria':checks=[['Pedido confirmado',any('CONFIRMADO')],['Saldo de 8 unidades',stock===8],['Uma cobrança',payments.length===1]];break;
    case 'sem-estoque':checks=[['Reserva recusada',any('RECUSADO')],['Saldo preservado em 10',stock===10],['Nenhuma cobrança',payments.length===0]];break;
    case 'recusa':case 'devolucao-dupla':checks=[['Pedido e reserva cancelados',cancelled],['Saldo em 10, não em 12',stock===10],['Pagamento recusado',payments.some((p)=>p.status==='RECUSADO')]];break;
    case 'timeout':case 'duplicidade':case 'reserva-timeout':checks=[['Resposta 202 observada',seen.has('HTTP202')],['Um único pedido e cobrança',orders.length===1 && payments.length===1],['Recuperação confirmou o pedido',any('CONFIRMADO')],['Saldo de 8 unidades',stock===8]];break;
    case 'pagamento-offline':checks=state.config.pagamentosOffline?[['Pedido pendente',any('PENDENTE')],['Estoque reservado: saldo 8',stock===8],['Nenhuma cobrança ainda',payments.length===0]]:[['Pedido confirmado após recuperar',any('CONFIRMADO')],['Uma cobrança',payments.length===1],['Saldo continua em 8',stock===8]];break;
    case 'estoque-offline':checks=state.config.estoqueOffline?[['Pedido em processamento',any('EM_PROCESSAMENTO')],['Saldo preservado em 10',stock===10],['Sem cobrança antecipada',payments.length===0]]:[['Reserva e pedido confirmados',any('CONFIRMADO') && reservations.some((r)=>r.status==='RESERVADA')],['Uma cobrança',payments.length===1],['Saldo de 8 unidades',stock===8]];break;
    case 'devolucao-falha':checks=state.config.falharDevolucao?[['Cancelamento ainda pendente',any('CANCELAMENTO_PENDENTE')],['Reserva mantida: saldo 8',stock===8],['Pagamento já recusado',payments.some((p)=>p.status==='RECUSADO')]]:[['Pedido e reserva cancelados',cancelled],['Devolução concluída: saldo 10',stock===10]];break;
    case 'devolucao-timeout':checks=[['Cancelamento pendente observado',seen.has('CANCELAMENTO_PENDENTE')],['Recuperação concluiu o cancelamento',cancelled],['Saldo correto: 10',stock===10]];break;
    case 'resposta-perdida':checks=[['Resposta ao cliente descartada',seen.has('SEM_RESPOSTA')],['Pedido foi confirmado no servidor',any('CONFIRMADO')],['Só um pedido e cobrança',orders.length===1 && payments.length===1],['Saldo de 8 unidades',stock===8]];break;
    case 'conflito':checks=[['Alteração rejeitada com 409',state.eventos.some((e)=>e.servico==='pedidos' && e.status===409)],['Pedido original manteve 2 unidades',orders.length===1 && orders[0].quantidade===2],['Saldo de 8 unidades',stock===8]];break;
    case 'concorrencia':checks=[['10 confirmados',orders.filter((o)=>o.status==='CONFIRMADO').length===10],['10 recusados',orders.filter((o)=>o.status==='RECUSADO').length===10],['Saldo em zero',stock===0],['10 cobranças, sem duplicar',payments.length===10]];break;
    case 'validacao':checks=[['Três respostas 400',state.eventos.filter((e)=>e.servico==='pedidos' && e.status===400).length===3],['Nenhum pedido ou cobrança',orders.length===0 && payments.length===0],['Saldo preservado em 10',stock===10]];break;
  }
  $('checks-card').hidden=false;
  $('checks-title').textContent=checks.every(([,ok])=>ok)?'Resultados esperados observados':'Observe a evolução dos estados';
  $('checks').innerHTML=checks.map(([label,ok])=>`<div class="check ${ok?'pass':''}"><span aria-hidden="true">${ok?'✓':'○'}</span><span>${escapeHtml(label)}</span><span class="sr-only">${ok?'verificado':'ainda não verificado'}</span></div>`).join('');
}
let refreshPromise=null, disconnected=false;
async function refresh() {
  if (refreshPromise) return refreshPromise;
  refreshPromise=(async()=>{
    try {
      const next=await api('/laboratorio/estado');
      if (disconnected) notice('');
      disconnected=false;
      if (state && state.geracao!==next.geracao) clearView();
      state=next;
      const connected=ready();
      $('connection').className=`connection ${connected?'ready':state.erro?'error':''}`;
      $('connection').textContent=state.reiniciando?'Preparando os serviços':state.erro?'Falha na inicialização':connected?'3 serviços conectados · HTTP real':'Verificando serviços';
      if (state.erro) notice(state.erro,true);
      renderServices();renderOrders();renderInspector();renderTimeline();renderConfig();renderChecks();updateButtons();
    } catch (error) {
      disconnected=true;
      $('connection').className='connection error';
      $('connection').textContent='Laboratório desconectado';
      notice('Não foi possível acessar o laboratório local. Confira se o comando de inicialização continua em execução.',true);
      ['send-order','repeat-order','parallel-order','run-scenario','empty-run','normalize'].forEach((id)=>$(id).disabled=true);
    }
  })().finally(()=>{refreshPromise=null;});
  return refreshPromise;
}
async function poll() {await refresh();setTimeout(poll,800);}
function safe(action) {return async(...args)=>{try {await action(...args);} catch(error){notice(error.message,true);}};}
$('scenario-nav').addEventListener('click',(event)=>{const button=event.target.closest('[data-scenario]');if(button) chooseScenario(button.dataset.scenario);});
$('orders-body').addEventListener('click',(event)=>{const button=event.target.closest('[data-order]');if(button){selectedOrder=button.dataset.order;renderOrders();renderInspector();}});
$('inspector-content').addEventListener('click',safe(async(event)=>{const button=event.target.closest('[data-refund]');if(button){button.disabled=true;try{await repeatRefund(button.dataset.refund);}finally{button.disabled=false;}}}));
$('order-form').addEventListener('submit',safe(async(event)=>{event.preventDefault();await postOrder();}));
$('repeat-order').addEventListener('click',safe(()=>postOrder()));
$('parallel-order').addEventListener('click',safe(async()=>{const body=readOrder(),key=$('omit-key').checked?null:$('key').value;await Promise.all([postOrder(body,key),postOrder(body,key)]);}));
$('new-key').addEventListener('click',()=>{$('key').value=`compra-${crypto.randomUUID().slice(0,8)}`;$('omit-key').checked=false;$('key').focus();});
$('run-scenario').addEventListener('click',safe(runScenario));
$('empty-run').addEventListener('click',safe(runScenario));
$('reset').addEventListener('click',safe(async()=>{if(await confirmReset()){await restartLaboratory();$('key').value='compra-A';await refresh();}}));
$('normalize').addEventListener('click',safe(async()=>{await setConfig({...defaults});notice('Serviços normalizados. Pedidos consulta automaticamente as operações pendentes; aguarde a próxima rodada de recuperação.');}));
$('fault-fields').addEventListener('change',safe(async()=>{await setConfig(currentConfig());}));
$('export').addEventListener('click',()=>{
  if (!state) return;
  const payload={exportadoEm:new Date().toISOString(),cenario:activeExperiment?scenarios.find((s)=>s.id===activeExperiment.id).name:'Teste livre',ultimaRespostaCliente:lastResponse,...state};
  const url=URL.createObjectURL(new Blob([JSON.stringify(payload,null,2)],{type:'application/json'}));
  const link=document.createElement('a');link.href=url;link.download=`kipper-experimento-${new Date().toISOString().replace(/[:.]/g,'-')}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});
buildNav();chooseScenario('sucesso');updateButtons();poll();
