import burp.api.montoya.MontoyaApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * OpenAI-compatible AI backend (vLLM, llama.cpp, etc.).
 *
 * Replaces Burp's built-in {@code api.ai()} backend: it talks directly to an
 * OpenAI {@code /v1/chat/completions} endpoint over HTTP (java.net.http), so the
 * assistant-AI calls bypass Burp's proxy/logging and can reach any local or
 * remote OpenAI-compatible model (vLLM, llama.cpp, Ollama, LM Studio, ...).
 *
 * Two Qwen-specific rules are enforced (both verified against the live models):
 *  1. All {@code system} messages must be first -> they are coalesced into a
 *     single leading system message (otherwise the Qwen chat template returns
 *     HTTP 400 "System message must be at the beginning").
 *  2. Qwen3 is a "thinking" model that otherwise streams its answer into
 *     {@code reasoning_content} and leaves {@code content} empty; sending
 *     {@code chat_template_kwargs.enable_thinking=false} yields a clean answer.
 */
public class aILocal implements aiInterface {

    private final MontoyaApi api;

    // --- connection config ---
    private volatile String baseUrl;      // e.g. http://127.0.0.1:8000/v1  (no trailing /chat/completions)
    private volatile String model;        // model name your server exposes
    private volatile String apiKey;       // Bearer token; blank = no Authorization header
    private volatile double temperature;
    private volatile int maxTokens;
    private volatile boolean disableThinking;
    private volatile int timeoutSeconds;

    private final HttpClient http;

    // Last error from a chat() call, so the UI (Test connection) can show it.
    private volatile String lastError = "";

    // Conversation context for AI-vs-AI ({role, content} pairs).
    private final List<String[]> context = new ArrayList<>();

    public aILocal(MontoyaApi api, String baseUrl, String model, String apiKey,
                   double temperature, int maxTokens, boolean disableThinking, int timeoutSeconds) {
        this.api = api;
        this.baseUrl = trim(baseUrl);
        this.model = trim(model);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.disableThinking = disableThinking;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        this.http = HttpClient.newBuilder()
                // Force HTTP/1.1: the default HTTP/2 cleartext (h2c) upgrade drops the
                // POST body on some OpenAI servers (e.g. vLLM/uvicorn -> HTTP 400 "body
                // Field required"), while llama.cpp tolerates it.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    /** The API key sent as a Bearer token ("" = no Authorization header). */
    public String effectiveApiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    /** Last error from a chat() call ("" if the last call succeeded). */
    public String lastError() { return lastError; }

    @Override
    public boolean isEnabled() {
        return !baseUrl.isBlank() && !model.isBlank();
    }

    // ---- single-shot (used by "Verify responses by AI") ----
    @Override
    public String getSingle_AI_Response(String systemPrompt, String userPrompt, boolean fakeResponse) {
        if (fakeResponse) {
            String[] values = {"Positive", "Negative", "AI error/invalid response"};
            String result = values[new Random().nextInt(values.length)];
            api.logging().logToOutput("[i]: Fake AI Response: " + result);
            return result;
        }
        if (!isEnabled()) {
            lastError = "Base URL and Model are required.";
            api.logging().logToOutput("[i]: Local AI is not configured (set Base URL and Model in AI Settings).");
            return "";
        }
        List<String[]> msgs = new ArrayList<>();
        msgs.add(new String[]{"system", systemPrompt});
        msgs.add(new String[]{"user", userPrompt});
        return chat(msgs);
    }

    // ---- conversation (used by "AI vs AI") ----
    @Override
    public String addUserQueryToConversation(String systemPrompt, String userPrompt) {
        if (!isEnabled()) {
            api.logging().logToOutput("[i]: Local AI is not configured (set Base URL and Model in AI Settings).");
            return "";
        }
        if (context.isEmpty()) {
            context.add(new String[]{"system", systemPrompt});
        }
        context.add(new String[]{"user", userPrompt});
        String result = chat(context);
        if (result != null && !result.isEmpty()) {
            context.add(new String[]{"assistant", result});
        }
        // Keep context bounded (mirror the previous behaviour: cap the growth).
        trimContext();
        return result;
    }

    @Override
    public void resetConversationContext() {
        context.clear();
    }

    private void trimContext() {
        // Preserve the leading system message (index 0); drop oldest user/assistant pairs.
        while (context.size() > 61) {
            if (context.size() > 2) { context.remove(1); }
            if (context.size() > 2) { context.remove(1); }
            else break;
        }
    }

    /**
     * POST the given messages to the chat-completions endpoint and return the
     * assistant text. Returns "" on any error (logged).
     */
    private String chat(List<String[]> rawMessages) {
        lastError = "";
        try {
            List<String[]> messages = coalesceSystemFirst(rawMessages);
            String body = buildRequestJson(messages);

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(chatUrl()))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            String key = effectiveApiKey();
            if (!key.isBlank()) {
                rb.header("Authorization", "Bearer " + key);
            }

            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                lastError = "HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 400);
                api.logging().logToOutput("[e]: AI " + lastError);
                return "";
            }
            String content = extractContent(resp.body());
            if (content.isBlank()) {
                lastError = "HTTP " + resp.statusCode() + " but no content in response.";
                api.logging().logToOutput("[e]: AI " + lastError + " Body: " + truncate(resp.body(), 400));
            }
            return content;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())
                    + "  (URL: " + chatUrl() + ")";
            api.logging().logToOutput("[e]: Error communicating with the AI: " + lastError);
            return "";
        }
    }

    private String chatUrl() {
        String b = baseUrl;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        // Accept either ".../v1" or a full ".../v1/chat/completions".
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    /** Merge every system message into one, place it first, keep the rest in order. */
    static List<String[]> coalesceSystemFirst(List<String[]> msgs) {
        StringBuilder sys = new StringBuilder();
        List<String[]> rest = new ArrayList<>();
        for (String[] m : msgs) {
            if ("system".equals(m[0])) {
                if (sys.length() > 0) sys.append("\n\n");
                sys.append(m[1] == null ? "" : m[1]);
            } else {
                rest.add(m);
            }
        }
        List<String[]> out = new ArrayList<>();
        if (sys.length() > 0) out.add(new String[]{"system", sys.toString()});
        out.addAll(rest);
        return out;
    }

    private String buildRequestJson(List<String[]> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"model\":").append(jsonStr(model)).append(',');
        sb.append("\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(',');
            String[] m = messages.get(i);
            sb.append('{').append("\"role\":").append(jsonStr(m[0]))
              .append(",\"content\":").append(jsonStr(m[1] == null ? "" : m[1])).append('}');
        }
        sb.append(']');
        sb.append(",\"max_tokens\":").append(maxTokens);
        sb.append(",\"temperature\":").append(temperature);
        sb.append(",\"stream\":false");
        if (disableThinking) {
            sb.append(",\"chat_template_kwargs\":{\"enable_thinking\":false}");
        }
        sb.append('}');
        return sb.toString();
    }

    /** Parse choices[0].message.content, with reasoning_content as a fallback, stripping <think> blocks. */
    static String extractContent(String responseBody) {
        Object root = MiniJson.parse(responseBody);
        if (!(root instanceof Map)) return "";
        Map<?, ?> m = (Map<?, ?>) root;
        Object choices = m.get("choices");
        if (choices instanceof List && !((List<?>) choices).isEmpty()) {
            Object c0 = ((List<?>) choices).get(0);
            if (c0 instanceof Map) {
                Object msg = ((Map<?, ?>) c0).get("message");
                if (msg instanceof Map) {
                    Object content = ((Map<?, ?>) msg).get("content");
                    String text = content == null ? "" : content.toString();
                    text = stripThink(text).trim();
                    if (!text.isEmpty()) return text;
                    // Fallback: some configs leave content empty and put text in reasoning_content.
                    Object reasoning = ((Map<?, ?>) msg).get("reasoning_content");
                    if (reasoning != null) return stripThink(reasoning.toString()).trim();
                    return "";
                }
            }
        }
        return "";
    }

    private static String stripThink(String s) {
        if (s == null) return "";
        return s.replaceAll("(?s)<think>.*?</think>", "");
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // --- minimal JSON string encoder for request building ---
    static String jsonStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    /**
     * Tiny dependency-free JSON parser (assumes well-formed input from the server).
     * Returns Map / List / String / Long / Double / Boolean / null.
     */
    static final class MiniJson {
        private final String s;
        private int i;
        private MiniJson(String s) { this.s = s; }

        static Object parse(String s) {
            try {
                MiniJson p = new MiniJson(s);
                p.ws();
                return p.value();
            } catch (Exception e) {
                return null;
            }
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return str();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default:  return num();
            }
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                i++; // :
                Object v = value();
                m.put(k, v);
                ws();
                char c = s.charAt(i++);
                if (c == '}') break; // else ','
            }
            return m;
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') break; // else ','
            }
            return l;
        }

        private String str() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':  b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/':  b.append('/'); break;
                        case 'n':  b.append('\n'); break;
                        case 't':  b.append('\t'); break;
                        case 'r':  b.append('\r'); break;
                        case 'b':  b.append('\b'); break;
                        case 'f':  b.append('\f'); break;
                        case 'u':  b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default:   b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        private Object num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }
    }
}
