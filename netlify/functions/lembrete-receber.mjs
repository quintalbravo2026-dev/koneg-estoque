// Lembrete diário de Contas a receber pelo Telegram.
// Roda sozinho todo dia às 8h (horário de Brasília) e manda no Telegram
// as contas atrasadas, as que vencem hoje e as dos próximos dias.
//
// Variáveis de ambiente (Netlify > Project configuration > Environment variables):
//   TELEGRAM_TOKEN         token do robô (dado pelo @BotFather)
//   FIREBASE_CLIENT_EMAIL  "client_email" do arquivo de chave do Firebase
//   FIREBASE_PRIVATE_KEY   "private_key" do arquivo de chave do Firebase
//   TELEGRAM_CHAT_ID       (opcional) descoberto e guardado sozinho na 1ª execução
import crypto from 'node:crypto';

const PROJETO = 'koneg-fd968';
const DIAS_ANTES = 3; // avisa também das contas que vencem nos próximos 3 dias
const BASE = `https://firestore.googleapis.com/v1/projects/${PROJETO}/databases/(default)/documents`;

export const config = { schedule: '0 11 * * *' }; // 11h UTC = 8h em Brasília

const b64url = (b) => Buffer.from(b).toString('base64').replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');

async function tokenGoogle() {
  const email = process.env.FIREBASE_CLIENT_EMAIL;
  const chave = String(process.env.FIREBASE_PRIVATE_KEY || '').replace(/\\n/g, '\n').replace(/^"|"$/g, '');
  if (!email || !chave) throw new Error('Faltam FIREBASE_CLIENT_EMAIL / FIREBASE_PRIVATE_KEY no Netlify');
  const agora = Math.floor(Date.now() / 1000);
  const cab = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const corpo = b64url(JSON.stringify({ iss: email, scope: 'https://www.googleapis.com/auth/datastore', aud: 'https://oauth2.googleapis.com/token', iat: agora, exp: agora + 3600 }));
  const ass = b64url(crypto.createSign('RSA-SHA256').update(`${cab}.${corpo}`).sign(chave));
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${cab}.${corpo}.${ass}` }),
  });
  const j = await r.json();
  if (!j.access_token) throw new Error('Google recusou a chave: ' + JSON.stringify(j));
  return j.access_token;
}

function valor(v) {
  if (!v) return undefined;
  if ('stringValue' in v) return v.stringValue;
  if ('doubleValue' in v) return Number(v.doubleValue);
  if ('integerValue' in v) return Number(v.integerValue);
  if ('booleanValue' in v) return v.booleanValue;
  if ('nullValue' in v) return null;
  return undefined;
}
const doc = (d) => Object.fromEntries(Object.entries(d.fields || {}).map(([k, v]) => [k, valor(v)]));

async function lerColecao(tk, col) {
  const lista = []; let pag = '';
  do {
    const r = await fetch(`${BASE}/${col}?pageSize=300${pag ? '&pageToken=' + pag : ''}`, { headers: { Authorization: 'Bearer ' + tk } });
    const j = await r.json();
    if (j.error) throw new Error('Firestore: ' + j.error.message);
    (j.documents || []).forEach((d) => lista.push(doc(d)));
    pag = j.nextPageToken || '';
  } while (pag);
  return lista;
}

async function lerChatSalvo(tk) {
  const r = await fetch(`${BASE}/admin/telegram`, { headers: { Authorization: 'Bearer ' + tk } });
  if (!r.ok) return '';
  return String(doc(await r.json()).chatId || '');
}
async function salvarChat(tk, chatId) {
  await fetch(`${BASE}/admin/telegram?updateMask.fieldPaths=chatId`, {
    method: 'PATCH', headers: { Authorization: 'Bearer ' + tk, 'Content-Type': 'application/json' },
    body: JSON.stringify({ fields: { chatId: { stringValue: String(chatId) } } }),
  });
}

const tg = (metodo, corpo) => fetch(`https://api.telegram.org/bot${process.env.TELEGRAM_TOKEN}/${metodo}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(corpo || {}),
}).then((r) => r.json());

const hojeBR = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date());
const somaDias = (iso, n) => { const d = new Date(iso + 'T12:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
const dataBR = (iso) => iso.split('-').reverse().join('/');
const diaSemana = (iso) => new Date(iso + 'T12:00:00Z').toLocaleDateString('pt-BR', { weekday: 'short', timeZone: 'UTC' }).replace('.', '');
const brl = (v) => (Number(v) || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
const esc = (t) => String(t || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

export function montarMensagem(contas, hoje) {
  const ate = somaDias(hoje, DIAS_ANTES);
  const pend = contas.filter((c) => !c.recebido && c.vencimento && c.vencimento <= ate)
    .sort((a, b) => a.vencimento.localeCompare(b.vencimento));
  if (!pend.length) return '';
  const linha = (c) => `• <b>${esc(c.cliente || 'Sem nome')}</b> — ${brl(c.valor)}${c.descricao ? `\n   <i>${esc(c.descricao)}</i>` : ''}`;
  const atras = pend.filter((c) => c.vencimento < hoje), dHoje = pend.filter((c) => c.vencimento === hoje), prox = pend.filter((c) => c.vencimento > hoje);
  const soma = (l) => brl(l.reduce((t, c) => t + (Number(c.valor) || 0), 0));
  const p = [`💰 <b>Contas a receber</b> · ${dataBR(hoje)}`];
  if (dHoje.length) p.push(`\n📅 <b>Vence hoje</b> (${soma(dHoje)})\n` + dHoje.map(linha).join('\n'));
  if (atras.length) p.push(`\n⚠️ <b>Atrasadas</b> (${soma(atras)})\n` + atras.map((c) => linha(c) + ` · venceu ${dataBR(c.vencimento)}`).join('\n'));
  if (prox.length) p.push(`\n🔜 <b>Próximos ${DIAS_ANTES} dias</b> (${soma(prox)})\n` + prox.map((c) => linha(c) + ` · ${diaSemana(c.vencimento)} ${dataBR(c.vencimento).slice(0, 5)}`).join('\n'));
  p.push('\n👉 koneg.com.br/painel');
  return p.join('\n');
}

export default async () => {
  try {
    if (!process.env.TELEGRAM_TOKEN) throw new Error('Falta TELEGRAM_TOKEN no Netlify');
    const tk = await tokenGoogle();

    // Descobre a conversa do Telegram (na 1ª vez, pela mensagem que você mandou ao robô) e guarda
    let chat = process.env.TELEGRAM_CHAT_ID || await lerChatSalvo(tk);
    let novo = false;
    if (!chat) {
      const up = await tg('getUpdates');
      const ult = (up.result || []).reverse().find((u) => u.message && u.message.chat);
      if (!ult) throw new Error('Mande qualquer mensagem para o seu robô no Telegram e rode de novo');
      chat = String(ult.message.chat.id); novo = true;
      await salvarChat(tk, chat);
    }

    const contas = await lerColecao(tk, 'receber');
    const msg = montarMensagem(contas, hojeBR());
    if (novo) await tg('sendMessage', { chat_id: chat, parse_mode: 'HTML', text: '✅ <b>Telegram ligado ao painel Koneg.</b>\nTodo dia às 8h você recebe aqui as contas a receber que vencem hoje, as atrasadas e as dos próximos dias.' + (msg ? '' : '\n\nHoje não há nenhuma conta para avisar.') });
    if (msg) {
      const r = await tg('sendMessage', { chat_id: chat, parse_mode: 'HTML', text: msg, disable_web_page_preview: true });
      if (!r.ok) throw new Error('Telegram: ' + JSON.stringify(r));
    }
    console.log(msg ? 'Lembrete enviado' : 'Nada para avisar hoje');
    return new Response('ok');
  } catch (e) {
    console.error('Erro no lembrete:', e.message);
    return new Response('erro: ' + e.message, { status: 500 });
  }
};
