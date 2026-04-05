/**
 * api.js — DynemicAI API Layer
 * Handles SSE streaming from Spring Boot backend.
 */
const API = (() => {

    const BASE_URL = 'http://localhost:8081/api';

    async function sendMessage({ messages, provider, model, sessionId }, onChunk, onDone, onError) {
        try {
            const response = await fetch(`${BASE_URL}/chat/stream`, {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body:    JSON.stringify({ messages, provider, model, sessionId }),
            });

            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                onError(err.error || err.message || `Server error: ${response.status}`);
                return;
            }

            const reader  = response.body.getReader();
            const decoder = new TextDecoder();
            let   buffer  = '';

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop(); // keep incomplete last line

                for (const line of lines) {
                    const trimmed = line.trim();
                    if (!trimmed || !trimmed.startsWith('data:')) continue;

                    const jsonStr = trimmed.slice(5).trim();
                    if (!jsonStr || jsonStr === '[DONE]') { onDone(); return; }

                    try {
                        const parsed = JSON.parse(jsonStr);

                        // Error signal
                        if (parsed.error) { onError(parsed.error); return; }

                        // Content chunk — emit to UI
                        if (parsed.content !== null && parsed.content !== undefined && parsed.content !== '') {
                            onChunk(parsed.content);
                        }

                        // Done signal — only stop if content is empty (final signal)
                        if (parsed.done === true && !parsed.content) {
                            onDone();
                            return;
                        }

                    } catch (_) {
                        // Non-JSON chunk — emit raw text
                        if (jsonStr && jsonStr !== 'null') onChunk(jsonStr);
                    }
                }
            }
            onDone();

        } catch (err) {
            if (err.name === 'AbortError') return;
            onError('Could not reach the server. Is Spring Boot running on port 8081?');
        }
    }

    async function sendMessageSimple({ messages, provider, model, sessionId }) {
        const r = await fetch(`${BASE_URL}/chat`, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ messages, provider, model, sessionId }),
        });
        if (!r.ok) throw new Error(`Server error: ${r.status}`);
        return r.json();
    }

    async function getSessions()          { const r = await fetch(`${BASE_URL}/sessions`); return r.ok ? r.json() : []; }
    async function getSession(id)         { const r = await fetch(`${BASE_URL}/sessions/${id}`); return r.ok ? r.json() : null; }
    async function deleteSession(id)      { await fetch(`${BASE_URL}/sessions/${id}`, { method: 'DELETE' }); }
    async function getModels()            { const r = await fetch(`${BASE_URL}/models`); return r.ok ? r.json() : null; }

    return { sendMessage, sendMessageSimple, getSessions, getSession, deleteSession, getModels };
})();