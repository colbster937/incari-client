// A second Incari player for trying the account screens and the chat menu (ADR 0058, ADR 0059)
// against tools/onlinedev or the online service. Signs up as <username> (password "peer password long"),
// then runs each command in turn; events from the service are printed as they come.
//
//   INCARI_ONLINE_URL=ws://127.0.0.1:8090/v1/ws node tools/onlinedev/peer.mjs Alex_Builds \
//       befriend:FrostTester "say:FrostTester:hello there" wait:2000
//
// Commands: befriend:<username>, accept (every request received), say:<friend>:<text> (waits up to
// two minutes for the friendship), settings:<everyone|friends|nobody>,
// presence:<serverHash>:<name>, wait:<ms>. Development only.
import { createHash } from "node:crypto";
const [, , username, ...commands] = process.argv;
const ws = new WebSocket(process.env.INCARI_ONLINE_URL ?? "ws://127.0.0.1:8090/v1/ws");
let id = 0;
const waiting = new Map();
const ask = (t, body = {}) => new Promise((resolve) => { const n = ++id; waiting.set(n, resolve); ws.send(JSON.stringify({ t, id: n, ...body })); });
ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); } else console.log("event", e.data); };
const zeros = (h) => { let n = 0; for (const b of h) { if (b === 0) { n += 8; continue; } n += Math.clz32(b) - 24; break; } return n; };
const b64 = (s) => Buffer.from(s.replace(/-/g, "+").replace(/_/g, "/"), "base64");
ws.onopen = async () => {
  await ask("hello", { v: 1, client: "peer" });
  const c = await ask("challenge");
  const ch = b64(c.challenge);
  let nonce = 0;
  for (;; nonce++) { const buf = Buffer.alloc(24); ch.copy(buf); buf.writeBigUInt64BE(BigInt(nonce), 16); const h = createHash("sha256").update(buf).digest(); if (zeros(h) >= c.bits) break; }
  const r = await ask("signup", { username, password: "peer password long", challenge: c.challenge, nonce });
  console.log("signup", r.t);
  for (const cmd of commands) {
    const [verb, ...rest] = cmd.split(":");
    if (verb === "befriend") console.log(verb, JSON.stringify(await ask("befriend", { username: rest[0] })));
    if (verb === "settings") console.log(verb, JSON.stringify(await ask("settings", { messages: rest[0] })));
    if (verb === "presence") console.log(verb, JSON.stringify(await ask("presence", { serverHash: rest[0], name: rest[1] })));
    if (verb === "wait") await new Promise((res) => setTimeout(res, Number(rest[0])));
    if (verb === "accept") { const f = await ask("friends"); for (const q of f.requests) if (q.incoming) console.log("accept", JSON.stringify(await ask("accept", { rid: q.rid }))); }
    if (verb === "say") { let fr; for (let i = 0; i < 240 && !fr; i++) { const f = await ask("friends"); fr = f.friends.find((x) => x.username.toLowerCase() === rest[0].toLowerCase()); if (!fr) await new Promise((res) => setTimeout(res, 500)); } console.log("say", JSON.stringify(await ask("send", { fid: fr.fid, text: rest.slice(1).join(":") }))); }
  }
  setTimeout(() => {}, 1 << 30);
};
