/* Radio gateway — redesign v3 review mock.
   Every sentence here is the shipped FlashRadioCopy text, so the review page cannot drift from the app's words. */

const COPY = {
  title: 'Radio gateway',
  stationNoRadio: 'No radio paired yet',
  off: 'Radio gateway is off.',
  nextOff: 'Turn on to discover or connect to your radio.',
  connecting: 'Radio gateway is on.',
  nextChecklist: 'Make sure your radio is on and KISS TNC is enabled.',
  scanning: 'Scanning for nearby radios…',
  nextScanning: 'Make sure your radio is discoverable.',
  connected: (n) => `Connected to ${n}.`,
  nextConnected: 'Ready to send and receive radio chat.',
  linkLost: 'Radio disconnected, trying again…',
  nextLinkLost: 'Keeping link open. Check radio battery and range.',
  permissionTitle: 'Permission required',
  permissionText: 'Flash needs Nearby devices permission to find and connect to your radio over Bluetooth.',
  allowNearby: 'Allow Nearby devices',
  openSettings: 'Open Bluetooth settings',
  candidateTitle: 'Paired radio found',
  candidateQuestion: (n) => `Use ${n} for your radio gateway?`,
  useThisRadio: 'Use this radio',
  differentRadio: 'Different radio',
  yourRadios: 'Your radios',
  pairNew: 'Pair a new radio',
  pair: 'Pair',
  inUse: 'In use',
  linkLostChip: 'Link lost',
  use: 'Use',
  reconnect: 'Reconnect',
  notShowing: 'Radio not showing up?',
  intro: 'Connect a handheld radio with KISS TNC over Bluetooth, then send and receive chat where there is no network.',
  readyTitleOff: 'Before you turn it on',
  readyTitleEmpty: 'Before you pair',
  step1: 'Turn the radio on and check the battery is charged (over 50%).',
  step2: 'In the radio menu: General Settings → KISS TNC → Enable.',
  step3: 'Bluetooth is on for this device, and the radio is discoverable.',
  batteryNote: 'Scanning uses extra battery and slows active links.',
  pairTitle: 'Pair a radio',
  pairSub: 'Nearby Bluetooth devices. Pick your radio.',
  stop: (s) => `Stop (${s} s)`,
  settingsTitle: 'Radio settings',
  security: 'Security profile',
  profileM: 'Profile M (Encrypted, tactical)',
  profileMSub: 'AES-256-GCM encryption with rotating station tags. Requires frequency authorization.',
  authorised: 'Authorised',
  profileA: 'Profile A (Signed cleartext)',
  profileASub: 'Plaintext AX.25 frames with digital signatures and callsign identification for amateur bands.',
  modes: 'Operational mode',
  active: 'Active',
  comingLater: 'Coming later',
  mode1: 'Mode 1: Field Companion',
  mode1Sub: 'Individual operator text chat with cryptographic delivery receipts over VHF/UHF.',
  mode2: 'Mode 2: Base Station Relay',
  mode2Sub: 'Forwards messages between a local Wi-Fi mesh and distant radio stations.',
  mode4: 'Mode 4: Tactical Radar',
  mode4Sub: 'Periodic offline location reports shown as distance and bearing.',
  mode5: 'Mode 5: All-in-One Dual Mode', // taste-skill-ignore shipped radio mode name
  mode5Sub: 'Simultaneous data chat and voice bridge.',
  thisRadio: 'This radio',
  factRadio: 'Radio',
  factAddress: 'Address',
  factCallsign: 'Callsign',
  factProfile: 'Profile',
  factMode: 'Mode',
  thisLink: 'This link',
  testMessage: 'Send a test message',
  testWarning: 'Keys the transmitter and broadcasts RF. Attach an antenna or dummy load first.',
  send: 'Send',
  forget: 'Forget this radio',
  forgetSub: 'Flash stops using VR-N76. You can pair it again at any time.',
  audio: 'Radio audio',
  audioOff: 'Turn on listening to hear the radio and talk.',
  audioNoLink: 'No link',
  audioNeedsLink: 'Reconnect the radio to talk.',
  listening: 'Listening',
  holdToTalk: 'Hold to talk',
  talking: 'Talking…',
  stationNote: 'Talking keys the transmitter and goes out as clear audio. Say your callsign as your licence requires.',
};

const RADIO = { name: 'VR-N76', address: '38:D2:00:01:35:B5', profile: 'M · Encrypted' };
const PAIRED = [{ name: 'UV-Pro', address: '38:D2:00:01:34:FD' }];
const DISCOVERED = [
  { name: 'VR-N76', category: 'RADIO', rssi: -47, paired: true, radio: true },
  { name: 'UV-Pro', category: 'RADIO', rssi: -78, paired: true },
  { name: 'Serial-TNC-SPP', category: 'SERIAL SPP', rssi: -60 },
  { name: 'Office-Headset', category: 'OTHER', rssi: -85 },
];

const params = new URLSearchParams(location.search);
const state = {
  view: params.get('state') || 'connected', // off | empty | connected | lost | permission | candidate | scan | settings
  enabled: params.get('state') !== 'off',
  voice: params.get('voice') || 'listening', // off | listening | talking
  sheet: null,
};

if (state.view === 'scan' || state.view === 'settings') state.sheet = state.view;

const icon = {
  radio: `<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><rect x="3" y="8" width="18" height="12" rx="2.5" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M7 8V5.5M7 5.5l4-2.5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><circle cx="16" cy="14" r="2.4" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M7 12v4M10 12v4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>`,
  bluetooth: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M7 7l10 10-5 4V3l5 4L7 17" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  retry: `<svg viewBox="0 0 24 24" width="17" height="17" aria-hidden="true"><path d="M4 12a8 8 0 1 1 3 6.2" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/><path d="M4 6v6h6" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  plus: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M12 5v14M5 12h14" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"/></svg>`,
  chevron: `<svg class="chev" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M9 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  mic: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><rect x="9" y="3" width="6" height="10" rx="3" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M6 11a6 6 0 0 0 12 0M12 17v4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>`,
  speaker: `<svg viewBox="0 0 24 24" width="17" height="17" aria-hidden="true"><path d="M4 10v4h3l4 3V7l-4 3H4Z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M15 9a4 4 0 0 1 0 6M17.5 6.5a8 8 0 0 1 0 11" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>`,
  close: `<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>`,
};

const sw = (on, label) => `<button class="switch-hit" role="switch" aria-checked="${on}" aria-label="${label}"><span class="switch"></span></button>`;
const chip = (text, kind = '') => `<span class="chip ${kind}">${text}</span>`;
const wideScreen = () => window.matchMedia('(min-width: 901px)').matches;

function stationPlate() {
  const lost = state.view === 'lost';
  const hasRadio = state.enabled && state.view !== 'empty';
  const live = hasRadio && !lost;

  let name = COPY.stationNoRadio;
  let tone = '';
  let sentence;
  let next;
  let facts = '';

  if (!state.enabled) {
    sentence = COPY.off;
    next = COPY.nextOff;
  } else if (!hasRadio) {
    sentence = COPY.connecting;
    next = COPY.nextChecklist;
  } else if (lost) {
    name = RADIO.name;
    tone = 'dot-warn';
    sentence = COPY.linkLost;
    next = COPY.nextLinkLost;
    facts = `<span>Bluetooth</span><span>${RADIO.address}</span>`;
  } else {
    name = RADIO.name;
    tone = 'dot-live';
    sentence = COPY.connected(RADIO.name);
    next = COPY.nextConnected;
    facts = `<span>Bluetooth</span><span>${RADIO.address}</span>`;
  }

  const cls = !state.enabled ? 'is-idle' : lost ? 'is-lost' : live ? 'is-live' : 'is-idle';

  return `
  <section class="plate ${cls}" aria-label="Station">
    <div class="plate-row">
      <span class="plate-mark">${hasRadio ? icon.radio : icon.bluetooth}</span>
      <div class="plate-text">
        <h2 class="plate-name">${name}</h2>
        <div class="plate-state"><span class="dot ${tone}"></span>${sentence}</div>
      </div>
      ${sw(state.enabled, 'Radio gateway master toggle')}
    </div>
    ${next ? `<p class="plate-next">${next}</p>` : ''}
    ${facts ? `<p class="facts">${facts}</p>` : ''}
    ${!state.enabled ? `<div class="plate-actions"><button class="primary" id="turn-on">${icon.bluetooth}Turn on</button></div>` : ''}
    ${lost ? `<div class="plate-actions"><button class="reconnect">${icon.retry}${COPY.reconnect}</button></div>` : ''}
  </section>`;
}

function permissionStrip() {
  if (state.view !== 'permission') return '';
  return `
  <section class="strip strip-warn" aria-label="Permission">
    <span class="strip-icon">${icon.bluetooth}</span>
    <div class="strip-body">
      <span class="strip-title">${COPY.permissionTitle}</span>
      <p class="strip-text">${COPY.permissionText}</p>
    </div>
    <button class="text-action">${COPY.allowNearby}</button>
  </section>`;
}

function candidateStrip() {
  if (state.view !== 'candidate') return '';
  return `
  <section class="strip strip-accent" aria-label="Paired radio found">
    <span class="strip-icon">${icon.bluetooth}</span>
    <div class="strip-body">
      <span class="strip-title">${COPY.candidateTitle}</span>
      <p class="strip-text">${COPY.candidateQuestion(RADIO.name)}</p>
      <div class="strip-actions">
        <button class="secondary">${COPY.useThisRadio}</button>
        <button class="text-action">${COPY.differentRadio}</button>
      </div>
    </div>
  </section>`;
}

function deviceRow({ name, sub, trailing, active }) {
  return `
  <li class="device ${active ? 'is-active' : ''}">
    <span class="device-mark">${icon.radio}</span>
    <div class="device-text">
      <span class="device-name">${name}</span>
      <span class="device-sub">${sub}</span>
    </div>
    ${trailing || ''}
  </li>`;
}

function radiosBlock() {
  if (!state.enabled) return '';

  const rows = [];
  if (state.view !== 'empty') {
    const lost = state.view === 'lost';
    rows.push(deviceRow({
      name: RADIO.name,
      sub: `<span>${RADIO.address}</span>${chip(lost ? COPY.linkLostChip : COPY.inUse, lost ? 'chip-warn' : 'chip')}`,
      active: !lost,
    }));
    PAIRED.forEach((p) => rows.push(deviceRow({
      name: p.name,
      sub: `<span>${p.address}</span>`,
      trailing: `<button class="text-action" aria-label="Use ${p.name} as the radio">${COPY.use}</button>`,
    })));
  }

  return `
  <section class="block" aria-labelledby="radios-heading">
    <div class="block-head">
      <h2 id="radios-heading">${COPY.yourRadios}</h2>
      <span class="block-note">${rows.length === 0 ? 'None paired yet' : rows.length + (rows.length === 1 ? ' radio' : ' radios')}</span>
    </div>
    ${rows.length ? `<ul class="devices">${rows.join('')}</ul>` : ''}
    <button class="${state.view === 'empty' ? 'primary' : 'secondary'}" id="pair-new">${icon.plus}${COPY.pairNew}</button>
  </section>`;
}

function stepsList() {
  return `<ul class="steps">
    <li class="step"><span class="step-num">1.</span><span>${COPY.step1}</span></li>
    <li class="step"><span class="step-num">2.</span><span>${COPY.step2}</span></li>
    <li class="step"><span class="step-num">3.</span><span>${COPY.step3}</span></li>
  </ul>`;
}

function introBlock() {
  return `<p class="intro">${COPY.intro}</p>`;
}

function helpBlock() {
  if (!state.enabled || state.view === 'empty') {
    const title = state.enabled ? COPY.readyTitleEmpty : COPY.readyTitleOff;
    return `<section class="block" aria-labelledby="ready-heading">
      <div class="block-head"><h2 id="ready-heading">${title}</h2></div>
      ${stepsList()}
    </section>`;
  }
  return `
  <details class="help">
    <summary>${COPY.notShowing}${icon.chevron}</summary>
    ${stepsList()}
  </details>`;
}

function audioPanel() {
  if (!state.enabled || state.view === 'empty') return '';

  if (state.voice === 'off') {
    return `
    <section class="audio" aria-label="Radio audio">
      <div class="audio-head">
        <span class="plate-mark">${icon.speaker}</span>
        <span class="audio-title">${COPY.audio}</span>
        ${sw(false, 'Listen to the radio switch')}
      </div>
      <p class="audio-off-note">${COPY.audioOff}</p>
      <div class="audio-foot">${COPY.stationNote}</div>
    </section>`;
  }

  const talking = state.voice === 'talking';
  const linkLive = state.view !== 'lost';
  const canTalk = linkLive;
  const stateText = talking ? COPY.talking : linkLive ? COPY.listening : COPY.audioNoLink;
  const bars = [1, 2, 3, 4, 5]
    .map((i) => `<i class="${linkLive && i <= (talking ? 5 : 2) ? 'on' : ''}"></i>`)
    .join('');

  return `
  <section class="audio" aria-label="Radio audio">
    <div class="audio-head">
      <span class="audio-title">${COPY.audio}</span>
      ${sw(true, 'Listen to the radio switch')}
    </div>
    <div class="audio-row">
      <div class="audio-state">
        <span class="${linkLive ? '' : 'is-bad'}">${stateText}</span>
        <span class="meter" aria-hidden="true">${bars}</span>
      </div>
      <button class="ptt ${canTalk ? '' : 'is-off'}" id="ptt" ${canTalk ? '' : 'aria-disabled="true"'} aria-label="Hold to talk through the radio">${icon.mic}${talking ? COPY.talking : linkLive ? COPY.holdToTalk : COPY.audioNeedsLink}</button>
    </div>
    <div class="audio-foot">${COPY.stationNote}</div>
  </section>`;
}


function pairSheet() {
  const devices = DISCOVERED.map((d) => `
    <li class="device">
      <span class="device-mark">${d.radio ? icon.radio : icon.bluetooth}</span>
      <div class="device-text">
        <span class="device-name">${d.name}</span>
        <span class="device-sub">${chip(d.category, d.radio ? 'chip' : 'chip-muted')}<span>${d.rssi} dBm</span></span>
      </div>
      <button class="text-action" aria-label="${d.paired ? 'Use ' + d.name + ' as the radio' : 'Pair with ' + d.name}">${d.paired ? COPY.useThisRadio : COPY.pair}</button>
    </li>`).join('');

  return `
  <div class="sheet-head">
    <h2 id="sheet-title">${COPY.pairTitle}</h2>
    <button class="icon-btn" id="sheet-close" aria-label="Close">${icon.close}</button>
  </div>
  <p class="sheet-sub">${COPY.pairSub}</p>
  <div class="scan-row">
    <span class="pulse" aria-hidden="true"></span>
    <span class="scan-text">${COPY.scanning}</span>
    <button class="text-action">${COPY.stop(9)}</button>
  </div>
  <ul class="devices" style="margin-top:4px">${devices}</ul>
  <div class="row" style="border-top:0">
    <span class="row-sub" style="flex:1 1 auto">${COPY.batteryNote}</span>
    <button class="text-action">${COPY.openSettings}</button>
  </div>`;
}

function settingsSheet() {
  return `
  <div class="sheet-head">
    <h2 id="sheet-title">${COPY.settingsTitle}</h2>
    <button class="icon-btn" id="sheet-close" aria-label="Close">${icon.close}</button>
  </div>

  <div class="sheet-group">
    <h3>${COPY.security}</h3>
    <div class="choice" role="radio" aria-checked="true" tabindex="0">
      <span class="ring on"></span>
      <div class="choice-text">
        <span class="choice-title">${COPY.profileM}${chip(COPY.authorised)}</span>
        <span class="choice-sub">${COPY.profileMSub}</span>
      </div>
    </div>
    <div class="choice" role="radio" aria-checked="false" tabindex="0">
      <span class="ring"></span>
      <div class="choice-text">
        <span class="choice-title">${COPY.profileA}</span>
        <span class="choice-sub">${COPY.profileASub}</span>
      </div>
    </div>
  </div>

  <div class="sheet-group">
    <h3>${COPY.modes}</h3>
    <div class="choice" role="radio" aria-checked="true" tabindex="0">
      <span class="ring on"></span>
      <div class="choice-text">
        <span class="choice-title">${COPY.mode1}${chip(COPY.active)}</span>
        <span class="choice-sub">${COPY.mode1Sub}</span>
      </div>
    </div>
    <div class="choice" role="radio" aria-checked="false" disabled>
      <span class="ring"></span>
      <div class="choice-text">
        <span class="choice-title">${COPY.mode2}${chip(COPY.comingLater, 'chip-muted')}</span>
        <span class="choice-sub">${COPY.mode2Sub}</span>
      </div>
    </div>
    <div class="choice" role="radio" aria-checked="false" disabled>
      <span class="ring"></span>
      <div class="choice-text">
        <span class="choice-title">${COPY.mode4}${chip(COPY.comingLater, 'chip-muted')}</span>
        <span class="choice-sub">${COPY.mode4Sub}</span>
      </div>
    </div>
    <div class="choice" role="radio" aria-checked="false" disabled>
      <span class="ring"></span>
      <div class="choice-text">
        <span class="choice-title">${COPY.mode5}${chip(COPY.comingLater, 'chip-muted')}</span>
        <span class="choice-sub">${COPY.mode5Sub}</span>
      </div>
    </div>
  </div>

  <div class="sheet-group">
    <h3>${COPY.thisRadio}</h3>
    <dl class="facts-row">
      <div class="fact"><dt>${COPY.factRadio}</dt><dd>${RADIO.name}</dd></div>
      <div class="fact"><dt>${COPY.factAddress}</dt><dd>${RADIO.address}</dd></div>
      <div class="fact"><dt>${COPY.factCallsign}</dt><dd>W1AW-7</dd></div>
    </dl>
    <div class="row">
      <div class="row-text">
        <span class="row-title">${COPY.testMessage}</span>
        <span class="row-sub">${COPY.testWarning}</span>
      </div>
      <button class="text-action">${COPY.send}</button>
    </div>
    <div class="row">
      <div class="row-text">
        <span class="row-title">${COPY.forget}</span>
        <span class="row-sub">${COPY.forgetSub}</span>
      </div>
      <button class="text-action danger">${COPY.forget}</button>
    </div>
  </div>`;
}

function linkBlock() {
  if (!state.enabled || state.view === 'empty') return '';
  return `
  <section class="block" aria-labelledby="link-heading">
    <div class="block-head"><h2 id="link-heading">${COPY.thisLink}</h2></div>
    <dl class="facts-row">
      <div class="fact"><dt>${COPY.factProfile}</dt><dd>${RADIO.profile} ${chip(COPY.authorised)}</dd></div>
      <div class="fact"><dt>${COPY.factCallsign}</dt><dd>W1AW-7</dd></div>
      <div class="fact"><dt>${COPY.factMode}</dt><dd>Field Companion</dd></div>
    </dl>
  </section>`;
}

function render() {
  document.getElementById('column').innerHTML = `
    ${stationPlate()}
    ${state.enabled ? '' : introBlock()}
    ${permissionStrip()}
    ${candidateStrip()}
    ${wideScreen() ? audioPanel() : ''}
    ${radiosBlock()}
    ${helpBlock()}
    ${linkBlock()}`;

  // On a phone the audio panel is the dock above the bottom bar, a layout sibling of the scroll area, so it
  // never covers a row; on a wide window it sits in the flow right under the station plate.
  document.getElementById('dock-slot').innerHTML = wideScreen() ? '' : audioPanel();

  const layer = document.getElementById('sheet-layer');
  if (state.sheet) {
    layer.hidden = false;
    document.getElementById('sheet').innerHTML = state.sheet === 'scan' ? pairSheet() : settingsSheet();
  } else {
    layer.hidden = true;
    document.getElementById('sheet').innerHTML = '';
  }

  document.getElementById('open-settings').onclick = () => { state.sheet = 'settings'; render(); };
  document.getElementById('back').onclick = () => history.back();
  const pairNew = document.getElementById('pair-new');
  if (pairNew) pairNew.onclick = () => { state.sheet = 'scan'; render(); };
  const turnOn = document.getElementById('turn-on');
  if (turnOn) turnOn.onclick = () => { state.enabled = true; state.view = 'empty'; render(); };
  const close = document.getElementById('sheet-close');
  if (close) close.onclick = () => { state.sheet = null; render(); };
  document.getElementById('scrim').onclick = () => { state.sheet = null; render(); };

  const ptt = document.getElementById('ptt');
  if (ptt) {
    ptt.addEventListener('pointerdown', () => { state.voice = 'talking'; render(); });
    ptt.addEventListener('pointerup', () => { state.voice = 'listening'; render(); });
    ptt.addEventListener('pointerleave', () => { if (state.voice === 'talking') { state.voice = 'listening'; render(); } });
  }
  document.querySelectorAll('[role="switch"]').forEach((el) => {
    el.onclick = () => {
      const on = el.getAttribute('aria-checked') === 'true';
      el.setAttribute('aria-checked', String(!on));
    };
  });
}

render();
