import express from "express";
import http from "node:http";
import OpenAI from "openai";
import { WebSocket, WebSocketServer } from "ws";

const apiKey = process.env.OPENAI_API_KEY;
if (!apiKey) throw new Error("OPENAI_API_KEY is required");

const port = Number(process.env.PORT || 8787);
const textModel = process.env.OPENAI_TEXT_MODEL || "gpt-5.6";
const realtimeModel = process.env.OPENAI_REALTIME_MODEL || "gpt-realtime-2.1";
const openai = new OpenAI({ apiKey });

const app = express();
app.use(express.json({ limit: "25mb" }));

app.get("/health", (_req, res) => res.json({ ok: true, textModel, realtimeModel }));

app.post("/api/respond", async (req, res) => {
  try {
    const { text = "", imageDataUrl, previousResponseId } = req.body || {};
    const content = [{ type: "input_text", text: text || "Describe the image." }];
    if (imageDataUrl) content.push({ type: "input_image", image_url: imageDataUrl, detail: "auto" });

    const response = await openai.responses.create({
      model: textModel,
      previous_response_id: previousResponseId || undefined,
      input: [{ role: "user", content }]
    });

    res.json({ text: response.output_text || "", responseId: response.id });
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: error?.message || "OpenAI request failed" });
  }
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/api/realtime" });

wss.on("connection", (questSocket) => {
  const upstream = new WebSocket(
    `wss://api.openai.com/v1/realtime?model=${encodeURIComponent(realtimeModel)}`,
    {
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "OpenAI-Beta": "realtime=v1"
      }
    }
  );

  const queued = [];
  upstream.on("open", () => {
    for (const item of queued.splice(0)) upstream.send(item);
  });

  questSocket.on("message", (data) => {
    const payload = data.toString();
    if (upstream.readyState === WebSocket.OPEN) upstream.send(payload);
    else queued.push(payload);
  });

  upstream.on("message", (data) => {
    if (questSocket.readyState === WebSocket.OPEN) questSocket.send(data.toString());
  });

  upstream.on("close", (code, reason) => {
    if (questSocket.readyState === WebSocket.OPEN) questSocket.close(code || 1000, reason.toString().slice(0, 120));
  });

  upstream.on("error", (error) => {
    console.error("Realtime upstream:", error.message);
    if (questSocket.readyState === WebSocket.OPEN) questSocket.close(1011, "Realtime upstream error");
  });

  questSocket.on("close", () => {
    if (upstream.readyState === WebSocket.OPEN || upstream.readyState === WebSocket.CONNECTING) upstream.close(1000, "client closed");
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`QuestGPT backend listening on :${port}`);
});
