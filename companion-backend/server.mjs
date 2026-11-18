import http from "node:http";
import { URL } from "node:url";
import { TelegramClient, Api } from "telegram";
import { StringSession } from "telegram/sessions/index.js";

const PORT = Number(process.env.PORT || 8787);
const API_ID = Number(process.env.TELEGRAM_API_ID || 0);
const API_HASH = process.env.TELEGRAM_API_HASH || "";
const STRING_SESSION = process.env.TELEGRAM_STRING_SESSION || "";
const AUTH_TOKEN = process.env.BACKEND_AUTH_TOKEN || "";

if (!API_ID || !API_HASH) {
  console.error("Missing TELEGRAM_API_ID or TELEGRAM_API_HASH environment variables.");
  process.exit(1);
}

let clientPromise = null;

async function getClient() {
  if (!clientPromise) {
    clientPromise = (async () => {
      const client = new TelegramClient(
        new StringSession(STRING_SESSION),
        API_ID,
        API_HASH,
        { connectionRetries: 5 }
      );
      await client.connect();
      return client;
    })();
  }
  return clientPromise;
}

function normalizeUsername(raw) {
  return String(raw || "")
    .trim()
    .replace(/^https?:\/\/(t\.me|telegram\.me)\//i, "")
    .replace(/^@/, "")
    .split("/")[0]
    .toLowerCase();
}

function isAudioDocument(doc) {
  if (!doc) return false;
  const mime = String(doc.mimeType || "").toLowerCase();
  if (mime.startsWith("audio/")) return true;
  const attrs = Array.isArray(doc.attributes) ? doc.attributes : [];
  if (attrs.some((a) => a instanceof Api.DocumentAttributeAudio)) return true;
  const filenameAttr = attrs.find((a) => a instanceof Api.DocumentAttributeFilename);
  const name = String(filenameAttr?.fileName || "").toLowerCase();
  return /\.(flac|alac|wav|mp3|m4a|aac|opus|ogg)$/.test(name);
}

function extractAudioTrack(req, username, message) {
  const doc = message?.media?.document;
  if (!isAudioDocument(doc)) return null;

  const attrs = Array.isArray(doc.attributes) ? doc.attributes : [];
  const audioAttr = attrs.find((a) => a instanceof Api.DocumentAttributeAudio);
  const fileAttr = attrs.find((a) => a instanceof Api.DocumentAttributeFilename);
  const fileName = String(fileAttr?.fileName || `telegram_${message.id}.mp3`);
  const extMatch = /\.([a-z0-9]+)$/i.exec(fileName);
  const ext = (extMatch?.[1] || "").toLowerCase();

  const title =
    audioAttr?.title?.trim() ||
    fileName.replace(/\.[^.]+$/, "").trim() ||
    `Track #${message.id}`;
  const artist = audioAttr?.performer?.trim() || `@${username}`;

  const proto = req.headers["x-forwarded-proto"] || "http";
  const host = req.headers.host || `127.0.0.1:${PORT}`;
  const streamUrl = `${proto}://${host}/v1/telegram/public/channels/${encodeURIComponent(username)}/stream/${message.id}`;

  return {
    messageId: Number(message.id),
    title,
    artist,
    album: `@${username}`,
    fileName,
    mimeType: String(doc.mimeType || "audio/mpeg"),
    format: ext || (String(doc.mimeType || "").includes("flac") ? "flac" : "mp3"),
    durationMs: Number(audioAttr?.duration || 0) * 1000,
    bitrateKbps: 0,
    fileSizeBytes: Number(doc.size || 0),
    streamUrl,
    publishedAtEpochMs: Number(message.date || 0) * 1000
  };
}

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body)
  });
  res.end(body);
}

function isAuthorized(req) {
  if (!AUTH_TOKEN) return true;
  const auth = req.headers.authorization || "";
  return auth === `Bearer ${AUTH_TOKEN}`;
}

const server = http.createServer(async (req, res) => {
  try {
    if (!isAuthorized(req)) {
      return sendJson(res, 401, { error: "Unauthorized" });
    }

    const reqUrl = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
    const pathname = reqUrl.pathname;

    if (req.method === "GET" && pathname === "/v1/telegram/public/resolve") {
      const username = normalizeUsername(reqUrl.searchParams.get("username"));
      if (!username) {
        return sendJson(res, 400, { error: "Missing username" });
      }
      const client = await getClient();
      const entity = await client.getEntity(username);
      const isPublic = Boolean(entity?.username);
      if (!isPublic) {
        return sendJson(res, 404, { error: "Channel is not public" });
      }
      return sendJson(res, 200, {
        username: normalizeUsername(entity.username),
        title: entity.title || `@${username}`,
        avatarUrl: null,
        channelId: Number(entity.id)
      });
    }

    const audioListMatch = /^\/v1\/telegram\/public\/channels\/([^/]+)\/audio$/.exec(pathname);
    if (req.method === "GET" && audioListMatch) {
      const username = normalizeUsername(decodeURIComponent(audioListMatch[1]));
      const cursor = Number(reqUrl.searchParams.get("cursor") || 0);
      const limit = Math.min(Math.max(Number(reqUrl.searchParams.get("limit") || 50), 1), 100);

      const client = await getClient();
      const entity = await client.getEntity(username);
      const messages = await client.getMessages(entity, {
        limit,
        offsetId: cursor > 0 ? cursor : 0
      });

      const items = [];
      let lowestId = 0;
      for (const msg of messages) {
        if (!lowestId || msg.id < lowestId) lowestId = Number(msg.id);
        const track = extractAudioTrack(req, username, msg);
        if (track) items.push(track);
      }

      const nextCursor = messages.length === limit && lowestId > 1 ? String(lowestId) : null;
      return sendJson(res, 200, { items, nextCursor });
    }

    const streamMatch = /^\/v1\/telegram\/public\/channels\/([^/]+)\/stream\/(\d+)$/.exec(pathname);
    if (req.method === "GET" && streamMatch) {
      const username = normalizeUsername(decodeURIComponent(streamMatch[1]));
      const messageId = Number(streamMatch[2]);
      const client = await getClient();
      const entity = await client.getEntity(username);
      const [message] = await client.getMessages(entity, { ids: [messageId] });
      const doc = message?.media?.document;
      if (!isAudioDocument(doc)) {
        return sendJson(res, 404, { error: "Audio message not found" });
      }

      const totalSize = Number(doc.size || 0);
      const mimeType = String(doc.mimeType || "audio/mpeg");
      const rangeHeader = req.headers.range;
      let start = 0;
      let end = totalSize > 0 ? totalSize - 1 : 0;
      let statusCode = 200;

      if (rangeHeader && totalSize > 0) {
        const match = /bytes=(\d+)-(\d*)/.exec(rangeHeader);
        if (match) {
          start = Number(match[1]);
          if (match[2]) end = Math.min(Number(match[2]), totalSize - 1);
          if (start >= totalSize || start > end) {
            res.writeHead(416, { "Content-Range": `bytes */${totalSize}` });
            res.end();
            return;
          }
          statusCode = 206;
        }
      }

      const contentLength = totalSize > 0 ? end - start + 1 : undefined;
      const headers = {
        "Content-Type": mimeType,
        "Accept-Ranges": "bytes",
        "Cache-Control": "private, max-age=300"
      };
      if (contentLength !== undefined) headers["Content-Length"] = String(contentLength);
      if (statusCode === 206) headers["Content-Range"] = `bytes ${start}-${end}/${totalSize}`;

      res.writeHead(statusCode, headers);

      let written = 0;
      const maxBytes = contentLength ?? Infinity;
      for await (const chunk of client.iterDownload({
        file: doc,
        offset: BigInt(start),
        requestSize: 512 * 1024
      })) {
        if (res.writableEnded || res.destroyed) break;
        const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        const remaining = maxBytes - written;
        if (remaining <= 0) break;
        const slice = buf.length > remaining ? buf.subarray(0, remaining) : buf;
        res.write(slice);
        written += slice.length;
        if (written >= maxBytes) break;
      }
      res.end();
      return;
    }

    sendJson(res, 404, { error: "Not found" });
  } catch (err) {
    console.error("Request failed:", err);
    sendJson(res, 500, { error: err?.message || "Internal server error" });
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Telegram Public Channel Backend listening on http://0.0.0.0:${PORT}`);
});
