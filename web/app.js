const STORAGE_KEY = "bodycam.cameras";
const cfg = window.DISPATCH || {};

const grid = document.querySelector("#grid");
const targets = document.querySelector("#targets");
const micButton = document.querySelector("#micButton");
const addButton = document.querySelector("#addButton");
const addDialog = document.querySelector("#addDialog");
const addForm = document.querySelector("#addForm");
const pttButton = document.querySelector("#ptt");
const pttState = document.querySelector("#pttState");
const deskVu = document.querySelector("#deskVu");

const audioContext = new AudioContext();
let micStream = null;
let micAnalyser = null;
let pttPeer = null;
let pttSessionUrl = null;
let pttActive = false;
const tiles = new Map();

function host() {
  return location.hostname || "127.0.0.1";
}

function whepUrl(path) {
  return `http://${host()}:${cfg.webrtcPort || 8889}/${path}/whep`;
}

function whipUrl() {
  return `http://${host()}:${cfg.webrtcPort || 8889}/${cfg.dispatchPath || "dispatch"}/whip`;
}

function hlsUrl(path) {
  return `http://${host()}:${cfg.hlsPort || 8888}/${path}/index.m3u8`;
}

function loadCameras() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
    if (Array.isArray(saved) && saved.length) return saved;
  } catch (_err) {
    /* use the default camera */
  }
  return [{ id: cfg.cameraPath || "cam-alpha-09", name: cfg.cameraPath || "cam-alpha-09", mode: "webrtc", path: cfg.cameraPath || "cam-alpha-09" }];
}

function saveCameras() {
  const data = [...tiles.values()].map((tile) => ({
    id: tile.id,
    name: tile.name,
    mode: tile.mode,
    path: tile.path
  }));
  localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
}

function renderTargets() {
  const lan = cfg.lanAddress || "127.0.0.1";
  const path = cfg.cameraPath || "cam-alpha-09";
  const rtsp = cfg.rtspPort || 8554;
  targets.innerHTML = `Phone publishes <strong>rtsp://${lan}:${rtsp}/${path}</strong><br>Dispatcher talkback path <strong>${cfg.dispatchPath || "dispatch"}</strong>`;
}

function waitForIce(pc) {
  if (pc.iceGatheringState === "complete") return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, 2500);
    pc.addEventListener("icegatheringstatechange", () => {
      if (pc.iceGatheringState === "complete") {
        clearTimeout(timer);
        resolve();
      }
    });
  });
}

function levelFrom(analyser) {
  const bins = new Uint8Array(analyser.fftSize);
  analyser.getByteTimeDomainData(bins);
  let sum = 0;
  for (let i = 0; i < bins.length; i += 1) {
    const sample = (bins[i] - 128) / 128;
    sum += sample * sample;
  }
  return Math.min(1, Math.sqrt(sum / bins.length) * 3.2);
}

function attachStreamVu(tile, stream) {
  const audioTracks = stream.getAudioTracks();
  if (!audioTracks.length || tile.analyser) return;
  const source = audioContext.createMediaStreamSource(stream);
  const analyser = audioContext.createAnalyser();
  analyser.fftSize = 256;
  source.connect(analyser);
  tile.analyser = analyser;
  tile.audioSource = source;
}

function drawDeskVu() {
  const ctx = deskVu.getContext("2d");
  const width = deskVu.width;
  const height = deskVu.height;
  ctx.clearRect(0, 0, width, height);
  const level = micAnalyser ? levelFrom(micAnalyser) : 0;
  const bars = 18;
  for (let i = 0; i < bars; i += 1) {
    const hot = i / bars < level;
    ctx.fillStyle = hot ? (i > bars * 0.8 ? "#d4533a" : "#e2a53a") : "#243024";
    const x = 4 + i * ((width - 8) / bars);
    ctx.fillRect(x, 6, (width - 8) / bars - 3, height - 12);
  }
  for (const tile of tiles.values()) {
    const bar = tile.root.querySelector(".vu > i");
    const value = tile.analyser ? levelFrom(tile.analyser) : 0;
    bar.style.width = `${Math.round(value * 100)}%`;
  }
  requestAnimationFrame(drawDeskVu);
}

function setSignal(tile, kind, text) {
  const node = tile.root.querySelector(".signal");
  node.className = `signal ${kind}`;
  node.textContent = text;
}

async function playWebrtc(tile) {
  const pc = new RTCPeerConnection({ iceServers: [] });
  tile.pc = pc;
  pc.addTransceiver("video", { direction: "recvonly" });
  pc.addTransceiver("audio", { direction: "recvonly" });
  const stream = new MediaStream();
  pc.ontrack = (event) => {
    stream.addTrack(event.track);
    tile.video.srcObject = stream;
    tile.video.play().catch(() => {});
    attachStreamVu(tile, stream);
  };
  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  await waitForIce(pc);
  const response = await fetch(whepUrl(tile.path), {
    method: "POST",
    headers: { "Content-Type": "application/sdp" },
    body: pc.localDescription.sdp
  });
  if (!response.ok) {
    throw new Error(response.status === 404 ? "No signal yet" : `Signal error ${response.status}`);
  }
  const answer = await response.text();
  const location = response.headers.get("Location");
  tile.sessionUrl = location ? new URL(location, whepUrl(tile.path)).toString() : null;
  await pc.setRemoteDescription({ type: "answer", sdp: answer });
  setSignal(tile, "live", "WebRTC live");
}

function playHls(tile) {
  const url = hlsUrl(tile.path);
  if (tile.video.canPlayType("application/vnd.apple.mpegurl")) {
    tile.video.src = url;
    setSignal(tile, "live", "HLS live");
    return;
  }
  if (!window.Hls || !window.Hls.isSupported()) {
    throw new Error("HLS player unavailable");
  }
  const hls = new window.Hls({ lowLatencyMode: true, liveSyncDuration: 1 });
  tile.hls = hls;
  hls.loadSource(url);
  hls.attachMedia(tile.video);
  hls.on(window.Hls.Events.MANIFEST_PARSED, () => setSignal(tile, "live", "HLS live"));
  hls.on(window.Hls.Events.ERROR, (_event, data) => {
    if (!data.fatal) return;
    setSignal(tile, "wait", "HLS failed");
    hls.destroy();
    tile.hls = null;
    tile.retry = setTimeout(() => connectTile(tile), 2000);
  });
}

async function connectTile(tile) {
  if (!tiles.has(tile.id)) return;
  setSignal(tile, "wait", "Waiting for signal");
  try {
    if (audioContext.state === "suspended") await audioContext.resume();
    if (tile.mode === "hls") playHls(tile);
    else await playWebrtc(tile);
  } catch (error) {
    setSignal(tile, "wait", error.message || "Waiting for signal");
    tile.retry = setTimeout(() => connectTile(tile), 2000);
  }
}

function closeTile(tile) {
  clearTimeout(tile.retry);
  if (tile.sessionUrl) {
    fetch(tile.sessionUrl, { method: "DELETE" }).catch(() => {});
  }
  if (tile.pc) tile.pc.close();
  if (tile.hls) tile.hls.destroy();
  if (tile.audioSource) tile.audioSource.disconnect();
  tile.video.srcObject = null;
}

function addCamera(camera) {
  if (tiles.has(camera.id)) return;
  const root = document.createElement("article");
  root.className = "tile";
  root.innerHTML = `
    <video autoplay playsinline muted></video>
    <div class="vu" aria-hidden="true"><i></i></div>
    <div class="tile-meta">
      <strong></strong>
      <span class="signal wait">Waiting for signal</span>
    </div>
    <div class="tile-tools">
      <span class="mode"></span>
      <span>
        <button type="button" class="unmute">Unmute</button>
        <button type="button" class="remove ghost">Remove</button>
      </span>
    </div>`;
  root.querySelector("strong").textContent = camera.name;
  root.querySelector(".mode").textContent = camera.mode === "hls" ? "HLS" : "WebRTC";
  const video = root.querySelector("video");
  const tile = { ...camera, root, video, analyser: null, pc: null, hls: null, sessionUrl: null, retry: null, audioSource: null };
  root.querySelector(".unmute").addEventListener("click", async () => {
    if (audioContext.state === "suspended") await audioContext.resume();
    video.muted = !video.muted;
    root.querySelector(".unmute").textContent = video.muted ? "Unmute" : "Mute";
  });
  root.querySelector(".remove").addEventListener("click", () => {
    closeTile(tile);
    tiles.delete(tile.id);
    root.remove();
    saveCameras();
    showEmpty();
  });
  tiles.set(tile.id, tile);
  grid.appendChild(root);
  showEmpty();
  connectTile(tile);
}

function showEmpty() {
  const existing = grid.querySelector(".empty");
  if (tiles.size === 0 && !existing) {
    const node = document.createElement("div");
    node.className = "empty";
    node.textContent = "No cameras on the grid. Add the phone stream to start.";
    grid.appendChild(node);
  } else if (tiles.size > 0 && existing) {
    existing.remove();
  }
}

async function enableMic() {
  if (audioContext.state === "suspended") await audioContext.resume();
  if (micStream) return micStream;
  micStream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 },
    video: false
  });
  const source = audioContext.createMediaStreamSource(micStream);
  micAnalyser = audioContext.createAnalyser();
  micAnalyser.fftSize = 256;
  source.connect(micAnalyser);
  micButton.textContent = "Mic ready";
  micButton.classList.add("active");
  return micStream;
}

async function startPtt() {
  if (pttActive) return;
  pttActive = true;
  pttButton.classList.add("hot");
  pttState.textContent = "Transmitting";
  try {
    const stream = await enableMic();
    const pc = new RTCPeerConnection({ iceServers: [] });
    pttPeer = pc;
    stream.getAudioTracks().forEach((track) => pc.addTrack(track, stream));
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    await waitForIce(pc);
    const response = await fetch(whipUrl(), {
      method: "POST",
      headers: { "Content-Type": "application/sdp" },
      body: pc.localDescription.sdp
    });
    if (!response.ok) throw new Error(`Talkback refused (${response.status})`);
    const answer = await response.text();
    const location = response.headers.get("Location");
    pttSessionUrl = location ? new URL(location, whipUrl()).toString() : null;
    await pc.setRemoteDescription({ type: "answer", sdp: answer });
  } catch (error) {
    pttState.textContent = error.message || "Talkback failed";
    await stopPtt();
  }
}

async function stopPtt() {
  pttActive = false;
  pttButton.classList.remove("hot");
  if (pttState.textContent === "Transmitting") pttState.textContent = "Standby";
  if (pttSessionUrl) {
    fetch(pttSessionUrl, { method: "DELETE" }).catch(() => {});
    pttSessionUrl = null;
  }
  if (pttPeer) {
    pttPeer.close();
    pttPeer = null;
  }
}

function isTyping(target) {
  if (!target) return false;
  const tag = target.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable;
}

if (location.protocol === "file:") {
  const warning = document.createElement("p");
  warning.className = "file-warning";
  warning.textContent = "Open this page through start-dispatch.ps1 so the browser can reach MediaMTX.";
  document.body.prepend(warning);
}

renderTargets();
loadCameras().forEach(addCamera);
showEmpty();
requestAnimationFrame(drawDeskVu);

micButton.addEventListener("click", () => {
  enableMic().catch((error) => {
    pttState.textContent = error.message || "Microphone blocked";
  });
});

addButton.addEventListener("click", () => addDialog.showModal());
addForm.addEventListener("submit", (event) => {
  if (event.submitter && event.submitter.value !== "add") return;
  const name = document.querySelector("#camName").value.trim();
  const path = document.querySelector("#camPath").value.trim().replace(/^\/+|\/+$/g, "");
  const mode = document.querySelector("#camMode").value;
  if (!name || !path) return;
  addCamera({ id: `${path}-${mode}`, name, mode, path });
  saveCameras();
});

pttButton.addEventListener("pointerdown", (event) => {
  event.preventDefault();
  startPtt();
});
pttButton.addEventListener("pointerup", stopPtt);
pttButton.addEventListener("pointerleave", () => {
  if (pttActive) stopPtt();
});

window.addEventListener("keydown", (event) => {
  if (event.code !== "Space" || event.repeat || isTyping(event.target)) return;
  event.preventDefault();
  startPtt();
});
window.addEventListener("keyup", (event) => {
  if (event.code !== "Space" || isTyping(event.target)) return;
  event.preventDefault();
  stopPtt();
});
