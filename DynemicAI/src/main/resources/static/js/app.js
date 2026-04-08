/**
 * app.js — DynemicAI Chat Application
 */

// ── MARKED CONFIG ─────────────────────────────────────────────────────────────
marked.setOptions({ breaks: true, gfm: true });
const renderer = new marked.Renderer();
renderer.code  = (code, lang) => {
    const hl = lang && hljs.getLanguage(lang)
        ? hljs.highlight(code, { language: lang }).value
        : hljs.highlightAuto(code).value;
    const id = 'code-' + Math.random().toString(36).slice(2, 8);
    return `<pre><div class="code-header">
        <span class="code-lang">${lang || 'code'}</span>
        <button class="copy-btn" onclick="copyCode('${id}',this)">Copy</button>
    </div><code id="${id}">${hl}</code></pre>`;
};
marked.use({ renderer });

// ── STORAGE KEYS ──────────────────────────────────────────────────────────────
// Changed to v4 — purges all old corrupted localStorage data automatically
const STORAGE_KEY        = 'dynemicai_v4_sessions';
const STORAGE_ACTIVE_KEY = 'dynemicai_v4_active';

// ── STATE ─────────────────────────────────────────────────────────────────────
const STATE = { sessions: [], activeSessionId: null, isStreaming: false };

// ── DOM ───────────────────────────────────────────────────────────────────────
const $                 = id => document.getElementById(id);
const chatList          = $('chatList');
const messagesWrap      = $('messagesWrap');
const messagesContainer = $('messagesContainer');
const welcomeScreen     = $('welcomeScreen');
const chatInput         = $('chatInput');
const sendBtn           = $('sendBtn');
const newChatBtn        = $('newChatBtn');
const modelSelect       = $('modelSelect');
const modelBadge        = $('modelBadge');
const headerModelName   = $('headerModelName');
const chatHeaderTitle   = $('chatHeaderTitle');
const clearBtn          = $('clearBtn');
const charCount         = $('charCount');
const sidebarToggle     = $('sidebarToggle');
const sidebar           = $('sidebar');

// ── UTILS ─────────────────────────────────────────────────────────────────────
const generateId = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
const formatTime = ts  => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

function escapeHtml(text) {
    return String(text)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/\n/g, '<br>');
}

// Check if a message content is corrupted SSE data
function isCorrupted(content) {
    if (!content) return true;
    const c = content.trim();
    return c.startsWith('data:')
        || c.includes('"done":true')
        || c.includes('"done":false')
        || c.includes('"content":null')
        || c.startsWith('{"content":')
        || c === '(no response)';
}

function saveState() {
    // Strip corrupted messages before saving
    const clean = STATE.sessions.map(s => ({
        ...s,
        messages: s.messages.filter(m => !isCorrupted(m.content))
    }));
    localStorage.setItem(STORAGE_KEY,        JSON.stringify(clean));
    localStorage.setItem(STORAGE_ACTIVE_KEY, STATE.activeSessionId || '');
}

function loadState() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) {
            const parsed = JSON.parse(raw);
            STATE.sessions = parsed.map(s => ({
                ...s,
                messages: (s.messages || []).filter(m => !isCorrupted(m.content))
            }));
        } else {
            STATE.sessions = [];
        }
        STATE.activeSessionId = localStorage.getItem(STORAGE_ACTIVE_KEY) || null;
        if (STATE.activeSessionId && !STATE.sessions.find(s => s.id === STATE.activeSessionId))
            STATE.activeSessionId = STATE.sessions[0]?.id || null;
    } catch (_) {
        STATE.sessions = []; STATE.activeSessionId = null;
    }
}

function getModelInfo(value) {
    const [provider, model] = value.split(':');
    const labels = { openai: 'OpenAI', gemini: 'Gemini', anthropic: 'Anthropic' };
    return { provider, model, label: `${model} · ${labels[provider] || provider}` };
}

function getActiveSession() {
    return STATE.sessions.find(s => s.id === STATE.activeSessionId) || null;
}

function showToast(msg) {
    let t = document.querySelector('.toast');
    if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); }
    t.textContent = msg; t.classList.add('show');
    setTimeout(() => t.classList.remove('show'), 2200);
}

window.copyCode = function(id, btn) {
    const el = document.getElementById(id); if (!el) return;
    navigator.clipboard.writeText(el.innerText).then(() => {
        btn.textContent = 'Copied!'; btn.classList.add('copied');
        setTimeout(() => { btn.textContent = 'Copy'; btn.classList.remove('copied'); }, 2000);
    });
};

window.fillPrompt = function(text) {
    chatInput.value = text;
    chatInput.dispatchEvent(new Event('input'));
    chatInput.focus();
};

// ── MODEL UI ──────────────────────────────────────────────────────────────────
function updateModelUI() {
    const info = getModelInfo(modelSelect.value);
    modelBadge.textContent      = info.label;
    headerModelName.textContent = info.model;
}

modelSelect.addEventListener('change', () => {
    updateModelUI();
    const s = getActiveSession();
    if (s) { const i = getModelInfo(modelSelect.value); s.provider = i.provider; s.model = i.model; saveState(); }
    showToast(`Switched to ${getModelInfo(modelSelect.value).label}`);
});

// ── SIDEBAR ───────────────────────────────────────────────────────────────────
sidebarToggle.addEventListener('click', () => sidebar.classList.toggle('open'));

function renderChatList() {
    chatList.innerHTML = '';
    const sorted = [...STATE.sessions].sort((a, b) => b.createdAt - a.createdAt);
    if (!sorted.length) {
        chatList.innerHTML = `<div style="padding:16px;font-size:.8rem;color:#606060;text-align:center">No chats yet</div>`;
        return;
    }
    sorted.forEach(session => {
        const item = document.createElement('div');
        item.className = 'chat-item' + (session.id === STATE.activeSessionId ? ' active' : '');
        item.innerHTML = `
            <span class="chat-item-icon">💬</span>
            <span class="chat-item-text" title="${session.title}">${session.title}</span>
            <button class="chat-item-delete" onclick="deleteSession(event,'${session.id}')">✕</button>`;
        item.addEventListener('click', () => switchSession(session.id));
        chatList.appendChild(item);
    });
}

window.deleteSession = function(e, id) {
    e.stopPropagation();
    STATE.sessions = STATE.sessions.filter(s => s.id !== id);
    if (STATE.activeSessionId === id) STATE.activeSessionId = STATE.sessions[0]?.id || null;
    saveState(); renderChatList(); renderMessages();
};

// ── SESSIONS ──────────────────────────────────────────────────────────────────
function createSession() {
    const info = getModelInfo(modelSelect.value);
    const s = { id: generateId(), title: 'New Chat', messages: [], provider: info.provider, model: info.model, createdAt: Date.now() };
    STATE.sessions.push(s); STATE.activeSessionId = s.id; saveState();
    return s;
}

function switchSession(id) {
    STATE.activeSessionId = id;
    const s = getActiveSession();
    if (s) {
        const val = `${s.provider}:${s.model}`;
        if (modelSelect.querySelector(`option[value="${val}"]`)) modelSelect.value = val;
        updateModelUI();
    }
    saveState(); renderChatList(); renderMessages();
    sidebar.classList.remove('open');
}

newChatBtn.addEventListener('click', () => { createSession(); renderChatList(); renderMessages(); chatInput.focus(); });

// ── RENDER ────────────────────────────────────────────────────────────────────
function renderMessages() {
    const s = getActiveSession();
    if (!s || !s.messages.length) {
        welcomeScreen.style.display     = 'flex';
        messagesContainer.style.display = 'none';
        chatHeaderTitle.textContent     = s?.title || 'New Conversation';
        return;
    }
    welcomeScreen.style.display     = 'none';
    messagesContainer.style.display = 'flex';
    chatHeaderTitle.textContent     = s.title;
    messagesContainer.innerHTML     = '';
    s.messages.forEach(msg => messagesContainer.appendChild(createMessageEl(msg)));
    scrollToBottom();
}

function createMessageEl(msg) {
    const div      = document.createElement('div');
    div.className  = `message ${msg.role}`;
    div.dataset.id = msg.id;
    const isUser   = msg.role === 'user';
    const modelTag = !isUser && msg.model ? `<span class="msg-model-tag">${msg.model}</span>` : '';
    //User = plain escaped text, Assistant = markdown rendered
    const contentHtml = isUser ? escapeHtml(msg.content) : marked.parse(msg.content || '');
    div.innerHTML = `
        <div class="msg-avatar">${isUser ? 'U' : 'AI'}</div>
        <div class="msg-content-wrap">
            <div class="msg-meta">
                <span class="msg-sender">${isUser ? 'you' : 'DynemicAI'}</span>
                ${modelTag}
                <span class="msg-time">${formatTime(msg.timestamp)}</span>
            </div>
            <div class="msg-bubble">${contentHtml}</div>
        </div>`;
    return div;
}

function scrollToBottom(smooth = true) {
    messagesWrap.scrollTo({ top: messagesWrap.scrollHeight, behavior: smooth ? 'smooth' : 'instant' });
}

// ── TYPING INDICATOR ──────────────────────────────────────────────────────────
function showTypingIndicator() {
    removeTypingIndicator();
    const w = document.createElement('div');
    w.className = 'message assistant'; w.id = 'typingIndicator';
    w.innerHTML = `<div class="msg-avatar">AI</div>
        <div class="msg-content-wrap">
            <div class="msg-meta"><span class="msg-sender">DynemicAI</span></div>
            <div class="typing-indicator">
                <div class="typing-dot"></div><div class="typing-dot"></div><div class="typing-dot"></div>
            </div>
        </div>`;
    messagesContainer.appendChild(w); scrollToBottom();
}
function removeTypingIndicator() { const el = $('typingIndicator'); if (el) el.remove(); }

// ── STREAMING ─────────────────────────────────────────────────────────────────
let streamingBubble  = null;
let streamingContent = '';

function startStreamingMessage(msgId, model) {
    streamingContent = '';
    const div = document.createElement('div');
    div.className = 'message assistant'; div.dataset.id = msgId;
    div.innerHTML = `
        <div class="msg-avatar">AI</div>
        <div class="msg-content-wrap">
            <div class="msg-meta">
                <span class="msg-sender">DynemicAI</span>
                <span class="msg-model-tag">${model}</span>
                <span class="msg-time">${formatTime(Date.now())}</span>
            </div>
            <div class="msg-bubble"><span class="streaming-cursor"></span></div>
        </div>`;
    streamingBubble = div.querySelector('.msg-bubble');
    messagesContainer.appendChild(div); scrollToBottom();
}

// Accumulate text chunks → render as markdown progressively
function appendStreamChunk(chunk) {
    if (!chunk) return;
    streamingContent += chunk;
    streamingBubble.innerHTML = marked.parse(streamingContent) + `<span class="streaming-cursor"></span>`;
    scrollToBottom(false);
}

function finalizeStream() {
    if (streamingBubble) streamingBubble.innerHTML = marked.parse(streamingContent || '');
    streamingBubble = null;
}

// ── SEND MESSAGE ──────────────────────────────────────────────────────────────
async function sendMessage() {
    const text = chatInput.value.trim();
    if (!text || STATE.isStreaming) return;
    if (!getActiveSession()) createSession();
    const session = getActiveSession();

    welcomeScreen.style.display     = 'none';
    messagesContainer.style.display = 'flex';

    const userMsg = { id: generateId(), role: 'user', content: text, timestamp: Date.now() };
    session.messages.push(userMsg);
    if (session.messages.length === 1) {
        session.title = text.slice(0, 45) + (text.length > 45 ? '…' : '');
        chatHeaderTitle.textContent = session.title;
    }
    saveState();
    chatInput.value = ''; charCount.textContent = '0'; autoResizeInput();
    messagesContainer.appendChild(createMessageEl(userMsg));
    renderChatList(); scrollToBottom();

    STATE.isStreaming = true; sendBtn.disabled = true;

    const aiMsgId       = generateId();
    const info          = getModelInfo(modelSelect.value);
    let   gotFirstChunk = false;
    let   captured      = '';

    // Only send clean messages — filter out any corrupted content
    const history = session.messages
        .filter(m => !isCorrupted(m.content))
        .map(m => ({ role: m.role, content: m.content }));

    showTypingIndicator();

    API.sendMessage(
        { messages: history, provider: info.provider, model: info.model, sessionId: session.id },

        // onChunk — called for every streamed word/token
        (chunk) => {
            if (!gotFirstChunk) {
                removeTypingIndicator();
                startStreamingMessage(aiMsgId, info.model);
                gotFirstChunk = true;
            }
            appendStreamChunk(chunk);
            captured = streamingContent;
        },

        // onDone — stream finished
        () => {
            finalizeStream();
            if (captured && captured.trim()) {
                session.messages.push({
                    id: aiMsgId, role: 'assistant',
                    content: captured,
                    model: info.model, provider: info.provider,
                    timestamp: Date.now()
                });
                saveState();
            } else {
                const el = messagesContainer.querySelector(`[data-id="${aiMsgId}"]`);
                if (el) el.remove();
            }
            STATE.isStreaming = false; sendBtn.disabled = false; chatInput.focus();
        },

        // onError
        (errMsg) => {
            removeTypingIndicator();
            if (gotFirstChunk) finalizeStream();
            const el = document.createElement('div');
            el.className = 'message assistant';
            el.innerHTML = `<div class="msg-avatar">AI</div>
                <div class="msg-content-wrap">
                    <div class="msg-meta"><span class="msg-sender">DynemicAI</span></div>
                    <div class="error-msg">${escapeHtml(errMsg)}</div>
                </div>`;
            messagesContainer.appendChild(el); scrollToBottom();
            STATE.isStreaming = false; sendBtn.disabled = false;
        }
    );
}

// ── INPUT ─────────────────────────────────────────────────────────────────────
chatInput.addEventListener('input', () => { charCount.textContent = chatInput.value.length; autoResizeInput(); });
chatInput.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } });
sendBtn.addEventListener('click', sendMessage);
function autoResizeInput() { chatInput.style.height = 'auto'; chatInput.style.height = Math.min(chatInput.scrollHeight, 180) + 'px'; }

// ── CLEAR ─────────────────────────────────────────────────────────────────────
clearBtn.addEventListener('click', () => {
    const s = getActiveSession(); if (!s || !s.messages.length) return;
    if (!confirm('Clear this conversation?')) return;
    s.messages = []; s.title = 'New Chat';
    saveState(); renderChatList(); renderMessages(); showToast('Conversation cleared');
});

// ── INIT ──────────────────────────────────────────────────────────────────────
loadState(); updateModelUI(); renderChatList(); renderMessages(); chatInput.focus();