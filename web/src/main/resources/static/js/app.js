import { supabase, DataService, isOfficialProfile, isSuperAdminProfile, reportCoords, reportLocationText, localDateString } from './db.js';

let calendarViewDate = new Date();

// ---------------------------------------------------------------------
// SAFETY HELPERS
// Everything that comes from the database (typed by residents) goes through esc()
// before it is put into innerHTML, so a report titled <script>... is shown as text.
// ---------------------------------------------------------------------
function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Only http(s) URLs may be used as image sources / links (blocks javascript: and data: URLs). */
function safeUrl(url) {
  if (!url || !String(url).trim()) return '';
  try {
    const u = new URL(String(url || ''), window.location.href);
    return (u.protocol === 'https:' || u.protocol === 'http:') ? u.href : '';
  } catch {
    return '';
  }
}

/** CSS class from a free-text value (status / priority pills). */
function cssToken(value, fallback) {
  const t = String(value || '').toLowerCase().replace(/[^a-z0-9_-]/g, '');
  return t || fallback;
}

// ---------------------------------------------------------------------
// ANNOUNCEMENT FORMATTING
// Same rules as the mobile app (PreviewActivity.parseAndAddMarkdown), so the preview here is
// what residents see on their phones:
//   # Heading   ## Subheading   **bold**   *italic*   __underline__   - bullet   ![image](https://...)
// Text is HTML-escaped BEFORE formatting, so nothing typed can become real HTML.
// ---------------------------------------------------------------------
const MD_IMAGE_RE = /!\[[^\]]*\]\(([^)\s]+)\)/g;

function formatMarkdownText(raw) {
  let html = esc(raw);
  html = html.replace(/^## (.*)$/gm, '<span class="md-h2">$1</span>');
  html = html.replace(/^# (.*)$/gm, '<span class="md-h1">$1</span>');
  html = html.replace(/^[-*] (.*)$/gm, '<span class="md-li">• $1</span>');
  html = html.replace(/\*\*(.*?)\*\*/g, '<b>$1</b>');
  html = html.replace(/\*(.*?)\*/g, '<i>$1</i>');
  html = html.replace(/__(.*?)__/g, '<u>$1</u>');
  // headings are blocks already, so the line break right after them is dropped
  html = html.replace(/(<span class="md-h[12]">.*?<\/span>)\n/g, '$1');
  return html.replace(/\n/g, '<br>');
}

/** Announcement body (markdown) -> safe HTML. */
function renderMarkdown(md) {
  const text = String(md || '');
  let out = '';
  let last = 0;
  for (const m of text.matchAll(MD_IMAGE_RE)) {
    out += formatMarkdownText(text.slice(last, m.index));
    const url = safeUrl(m[1]);
    out += url
      ? `<img class="md-img" src="${esc(url)}" alt="Announcement image" loading="lazy" style="cursor:zoom-in" onclick="zoomImage(this.src)">`
      : '';
    last = m.index + m[0].length;
  }
  out += formatMarkdownText(text.slice(last));
  return out.replace(/^(<br>)+|(<br>)+$/g, '');
}

function statusLabel(status) {
  const s = String(status || 'pending').toLowerCase();
  return ({
    pending: 'Pending',
    waiting_for_confirmation: 'Waiting for confirmation',
    approved: 'Approved',
    in_progress: 'In progress',
    ready_for_pickup: 'Ready for pickup',
    complete: 'Complete',
    resolved: 'Resolved',
    rejected: 'Rejected',
    cancelled: 'Cancelled'
  })[s] || s;
}

function showError(err, fallback = 'Something went wrong.') {
  console.error(err);
  alert((err && err.message) ? err.message : fallback);
}

const getCurrentDate = () => {
  const now = new Date();
  return {
    day: now.getDate(),
    month: now.toLocaleString('en-US', { month: 'long' }),
    year: now.getFullYear(),
    weekday: now.toLocaleString('en-US', { weekday: 'long' })
  };
};

const I = {
  home: '<path d="M3 11L12 4l9 7"/><path d="M5 10v9a1 1 0 001 1h4v-6h4v6h4a1 1 0 001-1v-9"/>',
  queue: '<path d="M12 9v4M12 16.5h.01"/><path d="M10.3 3.9L2.5 18a1.6 1.6 0 001.4 2.4h16.2a1.6 1.6 0 001.4-2.4L13.7 3.9a1.6 1.6 0 00-2.8 0z"/>',
  doc: '<path d="M6 3h9l4 4v14a1 1 0 01-1 1H6a1 1 0 01-1-1V4a1 1 0 011-1z"/><path d="M9 12h6M9 16h6M9 8h3"/>',
  megaphone: '<path d="M3 10v4a1 1 0 001 1h2l7 4V5L6 9H4a1 1 0 00-1 1z"/><path d="M13 8.5a3.5 3.5 0 010 7"/>',
  phone: '<path d="M22 16.9v3a2 2 0 01-2.2 2 19.8 19.8 0 01-8.6-3.1 19.5 19.5 0 01-6-6A19.8 19.8 0 012.1 4.2 2 2 0 014.1 2h3a2 2 0 012 1.7c.1 1 .3 2 .6 2.9a2 2 0 01-.5 2.1L8 9.9a16 16 0 006 6l1.2-1.2a2 2 0 012.1-.5c.9.3 1.9.5 2.9.6a2 2 0 011.8 2z"/>',
  building: '<path d="M4 21V4a1 1 0 011-1h6a1 1 0 011 1v17M12 21V9a1 1 0 011-1h6a1 1 0 011 1v12M4 21h16M7 7h1M7 11h1M7 15h1"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 20c0-4 4-6 8-6s8 2 8 6"/>',
  chevron: '<path d="M9 18l6-6-6-6"/>',
  pin: '<path d="M12 21s-7-6.2-7-11.5A7 7 0 0119 9.5C19 14.8 12 21 12 21z"/><circle cx="12" cy="9.5" r="2.5"/>',
  logout: '<path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>'
};

function ic(name) {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${I[name]}</svg>`;
}

const stage = document.getElementById('app-stage');
const modalRoot = document.getElementById('modal-root');

// ---------------------------------------------------------------------
// MAP (CARTO basemap + Leaflet). CARTO requires the API key on every tile URL
// (https://carto.com/basemaps/apikey), otherwise tiles say "API KEY REQUIRED".
// ---------------------------------------------------------------------
const CARTO_API_KEY = 'cb1_42nw_1_c5daf23a0f9ab0a9354a9996';
const CARTO_TILES = `https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png?key=${CARTO_API_KEY}`;
const CARTO_ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>';
let activeMaps = [];

function destroyMaps() {
  activeMaps.forEach(m => { try { m.remove(); } catch { /* already gone */ } });
  activeMaps = [];
}

function createMap(elementId, center, zoom) {
  const el = document.getElementById(elementId);
  if (!el || !window.L) return null;
  const map = window.L.map(el, { scrollWheelZoom: false }).setView(center, zoom);
  window.L.tileLayer(CARTO_TILES, { maxZoom: 20, attribution: CARTO_ATTRIBUTION }).addTo(map);
  activeMaps.push(map);
  setTimeout(() => map.invalidateSize(), 0);
  return map;
}

function googleMapsUrl({ lat, lng }) {
  return `https://www.google.com/maps/search/?api=1&query=${lat},${lng}`;
}

// ---------------------------------------------------------------------
// SESSION
// ---------------------------------------------------------------------
function getActiveUser() {
  try {
    const raw = sessionStorage.getItem('portal_user');
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function setActiveUser(userData) {
  if (userData) {
    sessionStorage.setItem('portal_user', JSON.stringify(userData));
  } else {
    sessionStorage.removeItem('portal_user');
    sessionStorage.removeItem('portal_access_token');
  }
}

/** The portal's view of the signed-in person, built from their profiles row. */
async function buildSessionUser(authUser, profile, previous = null) {
  const superAdmin = isSuperAdminProfile(profile);
  // a super admin keeps the barangay they were looking at; officials always see their own
  const psgcCode = superAdmin
    ? (previous && previous.isSuperAdmin ? previous.psgcCode : (profile.psgc_code || null))
    : profile.psgc_code;
  const barangayName = psgcCode
    ? (previous && previous.psgcCode === psgcCode && previous.barangayName ? previous.barangayName
       : await DataService.getBarangayName({ ...profile, psgc_code: psgcCode, barangay: superAdmin ? null : profile.barangay }))
    : 'All barangays (nationwide)';
  return {
    id: authUser.id,
    email: authUser.email || profile.email,
    fullName: [profile.first_name, profile.last_name].filter(Boolean).join(' ') || authUser.user_metadata?.full_name || authUser.email,
    role: profile.role,
    position: superAdmin ? 'Super admin' : (profile.position || profile.role || 'Official'),
    isSuperAdmin: superAdmin,
    homePsgc: profile.psgc_code || null,
    psgcCode,
    barangayName
  };
}

/** Why this profile may not use the portal ('' = allowed). Fails closed. */
function portalDenial(profile) {
  if (!profile) return 'No profile was found for this account. Ask your administrator to set it up.';
  if (isSuperAdminProfile(profile)) return '';
  if (!isOfficialProfile(profile)) {
    return String(profile.role || 'resident').toLowerCase() === 'resident'
      ? 'This portal is for barangay officials only. Residents should use the mobile app.'
      : 'Your official account is not active yet. Ask your administrator to approve it.';
  }
  if (!profile.psgc_code) return 'Your official account has no barangay assigned (psgc_code). Ask your administrator to set it.';
  return '';
}

/**
 * Re-reads the signed-in person's profile so changes made in Supabase (new barangay, new role,
 * account rejected...) show up without logging out and in again. Returns false if they were signed out.
 */
let lastSessionCheck = 0;
async function refreshSession(force = false) {
  const current = getActiveUser();
  if (!current) return false;
  if (!force && Date.now() - lastSessionCheck < 15000) return true;
  lastSessionCheck = Date.now();
  try {
    const { data } = await supabase.auth.getUser();
    if (!data?.user) { await signOutAndShowLogin('Your session has ended. Please sign in again.'); return false; }
    const profile = await DataService.getUserProfile(data.user.id);
    const denial = portalDenial(profile);
    if (denial) { await signOutAndShowLogin(denial); return false; }
    setActiveUser(await buildSessionUser(data.user, profile, current));
    return true;
  } catch (err) {
    console.warn('Could not refresh the session:', err);
    return true; // offline etc. - keep the current view
  }
}

// ---------------------------------------------------------------------
// LOAD ERRORS: shown on screen instead of failing silently
// ---------------------------------------------------------------------
let loadErrors = [];
async function load(label, fn, fallback) {
  try {
    return await fn();
  } catch (err) {
    console.error(`Could not load ${label}:`, err);
    loadErrors.push(`Could not load ${label}: ${err?.message || err}`);
    return fallback;
  }
}

async function signOutAndShowLogin(message) {
  try { await DataService.logout(); } catch (err) { console.warn('Logout error:', err); }
  setActiveUser(null);
  renderLogin(message);
}

// ---------------------------------------------------------------------
// MODALS
// ---------------------------------------------------------------------
function closeModal() {
  if (modalRoot) modalRoot.innerHTML = '';
}
window.closeModal = closeModal;

/** contentHtml must already be escaped by the caller. */
function openCustomModal({ title, contentHtml, onConfirm, onOpen, confirmText = 'Save', confirmClass = 'btn-small', width = 460 }) {
  modalRoot.innerHTML = `
    <div class="modal-overlay">
      <div class="modal-box" style="width: ${Number(width) || 460}px;">
        <div class="modal-head">
          <h4>${esc(title)}</h4>
          <button class="modal-close" id="modal-close-btn">✕</button>
        </div>
        <form id="custom-modal-form">
          <div style="margin: 14px 0;">
            ${contentHtml}
          </div>
          <div id="modal-error" style="display:none; color:var(--brick); font-size:12px; margin-bottom:10px;"></div>
          <div class="modal-btn-row">
            <button type="button" class="btn-small ghost" id="modal-cancel-btn">Cancel</button>
            <button type="submit" class="${confirmClass}" id="modal-submit-btn">${esc(confirmText)}</button>
          </div>
        </form>
      </div>
    </div>
  `;

  document.getElementById('modal-close-btn').onclick = closeModal;
  document.getElementById('modal-cancel-btn').onclick = closeModal;
  if (onOpen) onOpen();
  document.getElementById('custom-modal-form').onsubmit = async (e) => {
    e.preventDefault();
    if (!onConfirm) return;
    const submitBtn = document.getElementById('modal-submit-btn');
    const errorBox = document.getElementById('modal-error');
    submitBtn.disabled = true;
    errorBox.style.display = 'none';
    try {
      await onConfirm();
    } catch (err) {
      console.error(err);
      errorBox.textContent = err?.message || 'Could not save. Please try again.';
      errorBox.style.display = 'block';
      submitBtn.disabled = false;
    }
  };
}

// ---------------------------------------------------------------------
// LAYOUT
// ---------------------------------------------------------------------
function shell(mainHtml, active) {
  const user = getActiveUser();
  if (!user) return '';

  const navItems = [
    ['dashboard', 'home', 'Dashboard'],
    ['approvals', 'user', 'Account approvals'],
    ['queue', 'queue', 'Report queue'],
    ['documents', 'doc', 'Documents & IDs'],
    ['announcements', 'megaphone', 'Announcements'],
    ['emergency', 'phone', 'Emergency contacts']
  ];
  navItems.push(['users', 'user', 'Residents & officials']);
  if (user.isSuperAdmin) {
    navItems.push(['barangays', 'building', 'Barangays']);
  }

  const nav = navItems.map(([key, icon, label]) => `
    <div class="sb-item ${active === key ? 'active' : ''}" data-nav="${key}">
      ${ic(icon)} ${esc(label)}
    </div>
  `).join('');

  const who = user.fullName || user.email;

  return `
    <div class="shell">
      <div class="sidebar">
        <p class="brgy-name">${esc(user.barangayName)}</p>
        <p class="brgy-sub">${user.isSuperAdmin ? 'Super admin · all barangays' : 'Official Portal'}</p>
        ${user.isSuperAdmin ? `<button class="sb-scope-btn" id="sb-scope" type="button">Switch barangay</button>` : ''}
        <div class="sb-nav">${nav}</div>
        <div class="sb-foot">
          <div class="sb-avatar">${ic('user')}</div>
          <div style="flex:1; min-width:0;">
            <p class="who" style="white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" title="${esc(who)}">
              ${esc(who)}
            </p>
            <p class="role">${esc(user.position || user.role || 'Official')}</p>
          </div>
          <button class="sb-logout-btn" id="sb-logout" title="Sign out">
            ${ic('logout')}
          </button>
        </div>
      </div>
      <div class="main">${loadErrors.length ? `
        <div class="load-error" role="alert">
          <b>Some data could not be loaded.</b>
          <ul>${loadErrors.map(m => `<li>${esc(m)}</li>`).join('')}</ul>
          <span>Check that the latest SQL files were run in Supabase, then reload the page.</span>
        </div>` : ''}${mainHtml}</div>
    </div>
  `;
}

let currentScreen = 'login';
let currentParam = null;

async function showScreen(screen, param) {
  closeModal();
  destroyMaps();
  loadErrors = [];

  if (!getActiveUser() && screen !== 'login') {
    return renderLogin();
  }
  if (screen !== 'login' && !(await refreshSession())) return;
  const user = getActiveUser();
  if (screen === 'barangays' && !user.isSuperAdmin) screen = 'dashboard';
  currentScreen = screen;
  currentParam = param;

  switch (screen) {
    case 'login': renderLogin(); break;
    case 'dashboard': await renderDashboard(); break;
    case 'approvals': await renderApprovals(); break;
    case 'queue': await renderQueue(); break;
    case 'detail': await renderDetail(param); break;
    case 'documents': await renderDocuments(); break;
    case 'announcements': await renderAnnouncements(); break;
    case 'emergency': await renderEmergency(); break;
    case 'users': await renderUsers(); break;
    case 'barangays': await renderBarangays(); break;
  }
}

// Data changed somewhere else (mobile app, another official, Supabase)? Reload when the tab is used again.
let lastAutoRefresh = Date.now();
function refreshCurrentScreen() {
  if (currentScreen === 'login' || !getActiveUser()) return;
  if (modalRoot && modalRoot.innerHTML.trim()) return;                  // don't wipe an open form
  if (document.activeElement && ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName)) return;
  if (Date.now() - lastAutoRefresh < 10000) return;
  lastAutoRefresh = Date.now();
  showScreen(currentScreen, currentParam);
}
window.addEventListener('focus', refreshCurrentScreen);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refreshCurrentScreen(); });
setInterval(() => { if (!document.hidden) refreshCurrentScreen(); }, 60000);

document.addEventListener('click', (e) => {
  if (e.target.closest('.modal-close') || e.target.classList.contains('modal-overlay')) {
    closeModal();
    return;
  }

  const navItem = e.target.closest('[data-nav]');
  if (navItem) {
    showScreen(navItem.dataset.nav);
    return;
  }

  if (e.target.closest('#sb-scope')) {
    openScopePicker();
    return;
  }

  if (e.target.closest('#sb-logout')) {
    openCustomModal({
      title: 'Sign out',
      contentHtml: '<p style="font-size:13px; color:var(--muted); margin:0;">Are you sure you want to end your session?</p>',
      confirmText: 'Sign out',
      confirmClass: 'btn-small ghost',
      onConfirm: async () => {
        closeModal();
        await signOutAndShowLogin();
      }
    });
  }
});

// ---------------------------------------------------------------------
// LOGIN - officials only
// ---------------------------------------------------------------------
function renderLogin(message) {
  destroyMaps();
  stage.innerHTML = `
    <div class="login-wrap">
      <form class="login-card" id="login-form">
        <div class="login-mark">${ic('building')}</div>
        <h2>Barangay official sign in</h2>
        <p class="sub">Sign in using your authorized community personnel account.</p>

        <div id="login-error" style="display:none; color:var(--brick); font-size:12px; margin-bottom:12px;"></div>

        <label class="field-label">Email</label>
        <input class="field" type="email" id="login-email" required placeholder="name@barangay.gov.ph" autocomplete="email">

        <label class="field-label">Password</label>
        <input class="field" type="password" id="login-password" required placeholder="Enter password" autocomplete="current-password">

        <button class="btn-primary" type="submit" id="login-btn">Sign in</button>
        <p class="helper">Authorized municipal and barangay personnel only.</p>
      </form>
    </div>
  `;

  const form = document.getElementById('login-form');
  const errorBox = document.getElementById('login-error');
  const submitBtn = document.getElementById('login-btn');

  const showLoginError = (msg) => {
    errorBox.textContent = msg;
    errorBox.style.display = 'block';
    submitBtn.disabled = false;
    submitBtn.textContent = 'Sign in';
  };

  if (message) showLoginError(message);

  form.onsubmit = async (e) => {
    e.preventDefault();
    errorBox.style.display = 'none';
    submitBtn.disabled = true;
    submitBtn.textContent = 'Authenticating...';

    const email = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;

    try {
      const authResult = await DataService.login(email, password);
      if (!authResult || !authResult.user) {
        throw new Error('Authentication failed. Verify your email and password.');
      }

      let profile = null;
      try {
        profile = await DataService.getUserProfile(authResult.user.id);
      } catch (err) {
        console.error('Profile lookup failed:', err);
      }

      // Fails CLOSED: no profile / no role / no barangay = no access (super admins need no barangay)
      const denial = portalDenial(profile);
      if (denial) {
        await DataService.logout();
        showLoginError(denial);
        return;
      }

      setActiveUser(await buildSessionUser(authResult.user, profile));
      lastSessionCheck = Date.now();

      showScreen('dashboard');
    } catch (err) {
      showLoginError(err.message || 'Unable to authenticate credentials.');
    }
  };
}

// ---------------------------------------------------------------------
// DASHBOARD
// ---------------------------------------------------------------------
async function renderDashboard() {
  const user = getActiveUser();
  const dateInfo = getCurrentDate();

  let reports = [];
  let docs = [];
  let announcements = [];

  [reports, docs, announcements] = await Promise.all([
    load('reports', () => DataService.getReports(user.psgcCode), []),
    load('document requests', () => DataService.getDocuments(user.psgcCode), []),
    load('announcements', () => DataService.getAnnouncements(user.psgcCode), [])
  ]);

  const isStatus = (row, s) => String(row.status || '').toLowerCase() === s;
  const newReports = reports.filter(q => isStatus(q, 'pending')).length;
  const pendingDocs = docs.filter(d => isStatus(d, 'pending')).length;
  const resolved = reports.filter(q => isStatus(q, 'resolved')).length + docs.filter(d => isStatus(d, 'resolved')).length;
  const activeAnn = announcements.filter(a => !a.isArchived).length;

  // Real counts: reports submitted on each of the last 7 days
  const trendPoints = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date();
    d.setDate(d.getDate() - i);
    const key = localDateString(d);
    trendPoints.push({
      day: d.toLocaleString('en-US', { weekday: 'short' }),
      valC: reports.filter(r => r.created_at && localDateString(new Date(r.created_at)) === key && (r.category || '').toLowerCase() === 'complaint').length,
      valI: reports.filter(r => r.created_at && localDateString(new Date(r.created_at)) === key && (r.category || '').toLowerCase() === 'incident').length
    });
  }

  const maxVal = Math.max(4, ...trendPoints.map(p => Math.max(p.valC, p.valI)));
  const chartHeight = 110;
  const chartWidth = 400;
  const startX = 30;
  const stepX = (chartWidth - startX) / (trendPoints.length - 1);

  const coords = trendPoints.map((p, i) => {
    const x = startX + (i * stepX);
    const yC = chartHeight - (p.valC / maxVal * (chartHeight - 20)) + 10;
    const yI = chartHeight - (p.valI / maxVal * (chartHeight - 20)) + 10;
    return { x, yC, yI, ...p };
  });

  const linePathC = coords.map((c, i) => (i === 0 ? `M ${c.x} ${c.yC}` : `L ${c.x} ${c.yC}`)).join(' ');
  const linePathI = coords.map((c, i) => (i === 0 ? `M ${c.x} ${c.yI}` : `L ${c.x} ${c.yI}`)).join(' ');
  const areaPath = `${linePathC} L ${coords[coords.length - 1].x} ${chartHeight + 15} L ${coords[0].x} ${chartHeight + 15} Z`;

  const donutTotal = newReports + resolved;
  const pendingArc = donutTotal ? (newReports / donutTotal) * 97.4 : 0;

  const main = `
    <div class="main-head">
      <div>
        <h3>Dashboard</h3>
        <p>${esc(dateInfo.weekday)}, ${esc(dateInfo.month)} ${dateInfo.day}, ${dateInfo.year} · ${esc(user.barangayName)}</p>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="top"><div class="ic" style="background:var(--brick-100);color:var(--brick);">${ic('queue')}</div></div>
        <p class="num">${newReports}</p>
        <p class="lbl">Pending Reports</p>
      </div>
      <div class="stat-card">
        <div class="top"><div class="ic" style="background:var(--gold-100);color:var(--gold-600);">${ic('doc')}</div></div>
        <p class="num">${pendingDocs}</p>
        <p class="lbl">Pending Document Requests</p>
      </div>
      <div class="stat-card">
        <div class="top"><div class="ic" style="background:var(--sage-100);color:#3E6552;">${ic('doc')}</div></div>
        <p class="num">${resolved}</p>
        <p class="lbl">Resolved Items</p>
      </div>
      <div class="stat-card">
        <div class="top"><div class="ic" style="background:var(--teal-100);color:var(--teal-800);">${ic('megaphone')}</div></div>
        <p class="num">${activeAnn}</p>
        <p class="lbl">Active Announcements</p>
      </div>
    </div>

    <div class="chart-row">
      <div class="panel" style="display:flex; flex-direction:column;">
        <h4>Reports submitted, last 7 days</h4>
        <div style="flex:1; width:100%; margin-top:8px;">
          <svg viewBox="0 0 420 155" style="width:100%; height:auto; overflow:visible;">
            <defs>
              <linearGradient id="lineGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#0f766e" stop-opacity="0.28" />
                <stop offset="100%" stop-color="#0f766e" stop-opacity="0.0" />
              </linearGradient>
            </defs>
            <line x1="25" y1="30" x2="410" y2="30" stroke="#f1f5f9" stroke-width="1.5" />
            <line x1="25" y1="75" x2="410" y2="75" stroke="#f1f5f9" stroke-width="1.5" />
            <line x1="25" y1="120" x2="410" y2="120" stroke="#f1f5f9" stroke-width="1.5" />
            <text x="12" y="34" font-size="10" fill="#94a3b8">${maxVal}</text>
            <text x="12" y="79" font-size="10" fill="#94a3b8">${Math.round(maxVal / 2)}</text>
            <text x="12" y="124" font-size="10" fill="#94a3b8">0</text>
            <path d="${areaPath}" fill="url(#lineGrad)" />
            <path d="${linePathC}" fill="none" stroke="#0f766e" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
            <path d="${linePathI}" fill="none" stroke="#C1483A" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
            ${coords.map(c => `
              <circle cx="${c.x}" cy="${c.yC}" r="4" fill="#0f766e" stroke="#ffffff" stroke-width="1.5"><title>${c.valC} report(s)</title></circle>
              <circle cx="${c.x}" cy="${c.yI}" r="4" fill="#C1483A" stroke="#ffffff" stroke-width="1.5"><title>${c.valI} incident(s)</title></circle>
              <text x="${c.x}" y="142" font-size="10" font-weight="500" fill="#94a3b8" text-anchor="middle">${esc(c.day)}</text>
            `).join('')}
          </svg>
        </div>
        <div style="display:flex; gap:16px; font-size:11px; margin-top:6px; color:var(--muted); justify-content:center;">
          <div style="display:flex; align-items:center; gap:6px;"><span style="width:10px; height:10px; background:#0f766e; border-radius:50%;"></span> Reports</div>
          <div style="display:flex; align-items:center; gap:6px;"><span style="width:10px; height:10px; background:#C1483A; border-radius:50%;"></span> Incidents</div>
        </div>
      </div>

      <div class="panel">
        <h4>Current Operational Breakdown</h4>
        <div class="donut-row">
          <svg width="88" height="88" viewBox="0 0 36 36">
            <circle cx="18" cy="18" r="15.5" fill="none" stroke="#5C8A72" stroke-width="4"/>
            <circle cx="18" cy="18" r="15.5" fill="none" stroke="#C1483A" stroke-width="4" stroke-dasharray="${pendingArc} 97.4" stroke-dashoffset="0" transform="rotate(-90 18 18)"/>
          </svg>
          <div class="legend">
            <div class="li"><span class="sw" style="background:#C1483A;"></span>Pending Review (${newReports})</div>
            <div class="li"><span class="sw" style="background:#5C8A72;"></span>Resolved (${resolved})</div>
          </div>
        </div>
        ${!user.isSuperAdmin ? `<div id="ai-summary-box" style="margin-top:20px; padding:12px; background:#f8fafc; border-radius:8px; font-size:12px; color:#334155; line-height:1.5;">✨ Generating AI Summary...</div>` : ''}
      </div>
    </div>
  `;
  stage.innerHTML = shell(main, 'dashboard');

  if (!user.isSuperAdmin) {
    const summaryBox = document.getElementById('ai-summary-box');
    if (summaryBox) {
      try {
        const textData = \`Reports past 7 days: \${JSON.stringify(trendPoints)}\`;
        const res = await fetch('/api/admin/ai-summary', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ dataText: textData })
        });
        const d = await res.json();
        summaryBox.innerHTML = \`<b>✨ AI Summary:</b> <br>\${esc(d.summary)}\`;
      } catch (err) {
        summaryBox.innerHTML = \`<span style="color:red">Failed to load AI summary.</span>\`;
      }
    }
  }
}

// ---------------------------------------------------------------------
// ACCOUNT APPROVALS
// ---------------------------------------------------------------------
async function renderApprovals() {
  const user = getActiveUser();
  let approvals = [];

  approvals = await load('pending registrations', () => DataService.getApprovals(user.psgcCode), []);
  const brgyLabels = user.psgcCode ? {} : await DataService.getBarangayLabels(approvals.map(a => a.psgc_code));

  const trs = approvals.map(r => `
    <tr class="row-link" data-approval-id="${esc(r.id)}">
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--teal-100);color:var(--teal-800);">${ic('user')}</div>
          <div>
            <div class="t">${esc(r.name)}</div>
            <div class="s">${esc(r.address)}${user.psgcCode ? '' : ` · ${esc(brgyLabels[r.psgc_code] || r.psgc_code || 'no barangay')}`}</div>
          </div>
        </div>
      </td>
      <td>
        <div style="font-weight:600; color:var(--charcoal);">${esc(r.idType)}</div>
        <div class="s">${r.idNumber ? `ID No: ${esc(r.idNumber)}` : 'Number pending verification'}</div>
      </td>
      <td>${esc(r.date)}</td>
      <td style="text-align:right;color:var(--muted);">${ic('chevron')}</td>
    </tr>
  `).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Account approvals</h3>
        <p>Pending resident registrations. Verify the uploaded valid ID image and resident address before authorizing access.</p>
      </div>
    </div>
    <table class="table">
      <thead><tr><th>Resident</th><th>ID submitted</th><th>Registered</th><th></th></tr></thead>
      <tbody>${trs || '<tr><td colspan="4" style="color:var(--muted);padding:16px 10px;">No pending resident registrations found.</td></tr>'}</tbody>
    </table>
  `;
  stage.innerHTML = shell(main, 'approvals');

  document.querySelectorAll('[data-approval-id]').forEach(row => {
    row.onclick = () => {
      const selected = approvals.find(item => String(item.id) === row.dataset.approvalId);
      if (selected) openApprovalModal(selected);
    };
  });
}

function openApprovalModal(record) {
  const photo = safeUrl(record.idPhotoUrl);
  modalRoot.innerHTML = `
    <div class="modal-overlay">
      <div class="modal-box" style="width: 480px;">
        <div class="modal-head">
          <div>
            <h4>${esc(record.name)}</h4>
            <span style="font-size:11px;color:var(--muted);">Resident ID Verification</span>
          </div>
          <button class="modal-close" id="approval-modal-close">✕</button>
        </div>

        <div class="modal-field"><span class="modal-label">Address</span><span class="modal-value">${esc(record.address || 'Not specified')}</span></div>
        <div class="modal-field"><span class="modal-label">Email</span><span class="modal-value">${esc(record.email || '—')}</span></div>
        <div class="modal-field"><span class="modal-label">Mobile</span><span class="modal-value">${esc(record.phone || '—')}</span></div>
        <div class="modal-field"><span class="modal-label">ID type</span><span class="modal-value">${esc(record.idType || 'Valid Government ID')}</span></div>
        <div class="modal-field"><span class="modal-label">ID number</span><span class="modal-value">${esc(record.idNumber || 'N/A')}</span></div>

        <div style="margin-top:14px;">
          <label class="modal-label" style="display:block; margin-bottom:6px;">Uploaded ID Photo</label>
          ${photo
            ? `<div style="border:1px solid var(--sand); border-radius:6px; overflow:hidden; background:#000; text-align:center;">
                 <img src="${esc(photo)}" alt="Resident ID" style="max-width:100%; max-height:240px; display:inline-block; object-fit:contain;">
               </div>`
            : `<div class="evidence-thumb">No ID photo uploaded</div>`
          }
        </div>

        <div class="modal-actions" style="margin-top:18px;">
          <button class="btn-small" id="btn-approve">Approve resident</button>
          <button class="btn-small ghost" style="color:var(--brick);border-color:var(--brick);" id="btn-reject">Reject</button>
        </div>
      </div>
    </div>
  `;

  document.getElementById('approval-modal-close').onclick = closeModal;

  const decide = async (status, btn) => {
    btn.disabled = true;
    try {
      await DataService.updateApprovalStatus(record.id, status);
      closeModal();
      renderApprovals();
    } catch (err) {
      btn.disabled = false;
      showError(err, 'Could not update this account.');
    }
  };

  const approveBtn = document.getElementById('btn-approve');
  const rejectBtn = document.getElementById('btn-reject');
  approveBtn.onclick = () => decide('approved', approveBtn);
  rejectBtn.onclick = () => decide('rejected', rejectBtn);
}

// ---------------------------------------------------------------------
// DOCUMENTS & IDs (public.requests)
// ---------------------------------------------------------------------
const DOC_STATUSES = ['waiting_for_confirmation', 'in_progress', 'ready_for_pickup', 'complete', 'cancelled', 'rejected'];

async function renderDocuments() {
  const user = getActiveUser();
  let docs = [];

  docs = await load('document requests', () => DataService.getDocuments(user.psgcCode), []);
  const brgyLabels = user.psgcCode ? {} : await DataService.getBarangayLabels(docs.map(d => d.psgc_code));

  let currentDocTab = 'active';
  const main = `
    <div class="main-head">
      <div>
        <h3>Documents &amp; IDs</h3>
        <p>Manage resident barangay clearances, certifications, and permit endorsements.</p>
      </div>
    </div>
    <div class="tabs" style="margin-bottom:12px; display:flex; gap:16px;">
      <div id="tab-active" class="tab active" style="cursor:pointer; font-weight:600; padding:4px 0; border-bottom:2px solid #0f766e; color:#0f766e;">Active</div>
      <div id="tab-archived" class="tab" style="cursor:pointer; font-weight:500; padding:4px 0; border-bottom:2px solid transparent; color:var(--muted);">Archived (Resolved/Cancelled)</div>
    </div>
    <table class="table">
      <thead><tr><th>Resident &amp; Document</th><th>Status</th><th>Pickup Date</th><th></th></tr></thead>
      <tbody id="docs-body"></tbody>
    </table>
  `;
  stage.innerHTML = shell(main, 'documents');

  const renderRows = () => {
    const isArchived = currentDocTab === 'archived';
    const list = docs.filter(d => {
      const stat = String(d.status || 'waiting_for_confirmation').toLowerCase();
      const arch = stat === 'complete' || stat === 'resolved' || stat === 'cancelled' || stat === 'rejected';
      return isArchived ? arch : !arch;
    });

    const trs = list.map(d => {
      const rawStatus = String(d.status || 'waiting_for_confirmation').toLowerCase();
      const id = esc(d.id);
      const options = [...new Set([...DOC_STATUSES, rawStatus])].map(s =>
        `<option value="${esc(s)}" ${rawStatus === s ? 'selected' : ''}>${esc(statusLabel(s))}</option>`
      ).join('');

      return `
        <tr data-doc-row="${id}">
          <td style="vertical-align: top;">
            <div class="name-cell" style="align-items: flex-start;">
              <div class="ic" style="background:var(--gold-100);color:var(--gold-600); margin-top: 4px;">${ic('doc')}</div>
              <div style="flex:1;">
                <div class="t">${esc(d.name)}</div>
                <div class="s">Requested: ${esc(d.type)}${user.psgcCode ? '' : ` · ${esc(brgyLabels[d.psgc_code] || d.psgc_code || '')}`}</div>
                <div style="font-size: 11.5px; color: var(--charcoal); margin-top: 4px;"><b>Purpose:</b> ${esc(d.purpose || 'None specified')}</div>
                <div style="font-size: 11.5px; color: var(--charcoal); margin-top: 2px;"><b>Contact:</b> ${esc(d.contact || 'No contact info available')}</div>
                <div style="margin-top: 8px;">
                  <input type="text" class="field doc-remarks-input" data-doc-id="${id}" value="${esc(d.remarks || '')}" placeholder="Add remarks / notifications to resident..." style="font-size: 11.5px; padding: 4px 8px; width: 100%;" ${rawStatus === 'cancelled' ? 'disabled' : ''}>
                </div>
              </div>
            </div>
          </td>
          <td style="vertical-align: top; padding-top: 16px;">
            <select class="field doc-status-select" data-doc-id="${id}" style="width: auto; padding: 4px 8px; font-size: 12px; height: 32px;" ${rawStatus === 'cancelled' ? 'disabled' : ''}>
              ${options}
            </select>
          </td>
          <td style="vertical-align: top; padding-top: 16px;">
            <input type="date" class="field doc-date-picker" data-doc-id="${id}" value="${esc(d.pickup)}" style="width: 145px; padding: 4px 8px; font-size: 12px; height: 32px;">
          </td>
          <td style="vertical-align: top; padding-top: 16px;">
            <span class="save-indicator text-xs" data-indicator-id="${id}" style="color: var(--teal-800); font-size: 11px;">${rawStatus === 'cancelled' ? 'Cancelled by resident' : 'Saved'}</span>
          </td>
        </tr>
      `;
    }).join('');
    
    document.getElementById('docs-body').innerHTML = trs || '<tr><td colspan="4" style="color:var(--muted);padding:16px 10px;">No document requests found.</td></tr>';
    
    document.querySelectorAll('.doc-status-select').forEach(select => {
      select.onchange = () => saveRow(select.dataset.docId);
    });
    document.querySelectorAll('.doc-date-picker').forEach(input => {
      input.onchange = () => saveRow(input.dataset.docId);
    });
    document.querySelectorAll('.doc-remarks-input').forEach(input => {
      input.onchange = () => saveRow(input.dataset.docId);
    });
  };

  const saveRow = async (id) => {
    const select = document.querySelector(`.doc-status-select[data-doc-id="${CSS.escape(id)}"]`);
    const dateInput = document.querySelector(`.doc-date-picker[data-doc-id="${CSS.escape(id)}"]`);
    const remarksInput = document.querySelector(`.doc-remarks-input[data-doc-id="${CSS.escape(id)}"]`);
    const indicator = document.querySelector(`[data-indicator-id="${CSS.escape(id)}"]`);
    indicator.textContent = 'Saving...';
    indicator.style.color = 'var(--teal-800)';
    try {
      await DataService.updateDocument(id, {
        status: select.value,
        pickup_date: dateInput.value || null,
        admin_remarks: remarksInput ? remarksInput.value : null
      });
      indicator.textContent = 'Saved';
      
      const doc = docs.find(d => d.id === id);
      if (doc) {
        doc.status = select.value;
        if (remarksInput) doc.remarks = remarksInput.value;
      }
      if (doc && (doc.status === 'complete' || doc.status === 'resolved' || doc.status === 'cancelled' || doc.status === 'rejected')) {
        setTimeout(renderRows, 1000); // refresh list to move to archived
      }
    } catch (err) {
      console.error(err);
      indicator.textContent = err?.message ? `Error: ${err.message}` : 'Error saving';
      indicator.style.color = 'var(--brick)';
    }
  };

  document.getElementById('tab-active').onclick = () => {
    currentDocTab = 'active';
    document.getElementById('tab-active').style.borderBottomColor = '#0f766e';
    document.getElementById('tab-active').style.color = '#0f766e';
    document.getElementById('tab-active').style.fontWeight = '600';
    document.getElementById('tab-archived').style.borderBottomColor = 'transparent';
    document.getElementById('tab-archived').style.color = 'var(--muted)';
    document.getElementById('tab-archived').style.fontWeight = '500';
    renderRows();
  };
  document.getElementById('tab-archived').onclick = () => {
    currentDocTab = 'archived';
    document.getElementById('tab-archived').style.borderBottomColor = '#0f766e';
    document.getElementById('tab-archived').style.color = '#0f766e';
    document.getElementById('tab-archived').style.fontWeight = '600';
    document.getElementById('tab-active').style.borderBottomColor = 'transparent';
    document.getElementById('tab-active').style.color = 'var(--muted)';
    document.getElementById('tab-active').style.fontWeight = '500';
    renderRows();
  };

  renderRows();
}

// ---------------------------------------------------------------------
// REPORT QUEUE (public.reports) + overview map
// ---------------------------------------------------------------------
async function renderQueue() {
  const user = getActiveUser();
  let reports = [];

  reports = await load('reports', () => DataService.getReports(user.psgcCode), []);
  const brgyLabels = user.psgcCode ? {} : await DataService.getBarangayLabels(reports.map(r => r.psgc_code));

  const categories = [...new Set(reports.map(r => r.category).filter(Boolean))].sort();
  const pinned = reports.map(r => ({ r, c: reportCoords(r) })).filter(x => x.c);

  const main = `
    <div class="main-head">
      <div>
        <h3>Report &amp; request queue</h3>
        <p>Live municipal concerns submitted by residents.</p>
      </div>
    </div>
    ${pinned.length ? `
      <div class="panel" style="margin-bottom:14px; padding:0; overflow:hidden;">
        <div id="queue-map" style="height:260px; width:100%;"></div>
      </div>` : ''}
    <div class="tabs" style="margin-bottom:12px; display:flex; gap:16px;">
      <div id="tab-queue-active" class="tab active" style="cursor:pointer; font-weight:600; padding:4px 0; border-bottom:2px solid #0f766e; color:#0f766e;">Active</div>
      <div id="tab-queue-archived" class="tab" style="cursor:pointer; font-weight:500; padding:4px 0; border-bottom:2px solid transparent; color:var(--muted);">Archived (Resolved/Cancelled)</div>
    </div>
    <div class="filters">
      <select id="queue-cat-filter">
        <option value="">All types</option>
        ${categories.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('')}
      </select>
      <select id="queue-stat-filter">
        <option value="">All statuses</option>
        <option value="pending">Pending</option>
        <option value="approved">Approved</option>
        <option value="in_progress">In progress</option>
        <option value="resolved">Resolved</option>
        <option value="rejected">Rejected</option>
        <option value="cancelled">Cancelled</option>
      </select>
      <select id="queue-sort">
        <option value="priority">Highest priority</option>
        <option value="newest">Newest first</option>
      </select>
      <input id="queue-search" placeholder="Search reports...">
    </div>
    <table class="table">
      <thead><tr><th>Report</th><th>Priority</th><th>Status</th><th></th></tr></thead>
      <tbody id="queue-body"></tbody>
    </table>
  `;
  stage.innerHTML = shell(main, 'queue');

    let currentQueueTab = 'active';
    const renderRows = () => {
    const isArchived = currentQueueTab === 'archived';
    const cat = document.getElementById('queue-cat-filter').value;
    const stat = document.getElementById('queue-stat-filter').value;
    const search = document.getElementById('queue-search').value.toLowerCase();
    const sortVal = document.getElementById('queue-sort').value;

    const list = reports.filter(q => {
      const qStat = String(q.status || 'pending').toLowerCase();
      const arch = qStat === 'resolved' || qStat === 'cancelled';
      if (isArchived ? !arch : arch) return false;

      const matchesCat = !cat || q.category === cat;
      const matchesStat = !stat || qStat === stat;
      const matchesSearch = !search ||
        String(q.title || '').toLowerCase().includes(search) ||
        String(q.description || '').toLowerCase().includes(search);
      return matchesCat && matchesStat && matchesSearch;
    }).sort((a, b) => {
      if (sortVal === 'priority') {
        const getPrio = (p) => p === 'high' ? 3 : (p === 'medium' ? 2 : 1);
        const pA = getPrio(String(a.priority || 'medium').toLowerCase());
        const pB = getPrio(String(b.priority || 'medium').toLowerCase());
        if (pA !== pB) return pB - pA;
      }
      const tA = a.created_at ? new Date(a.created_at).getTime() : 0;
      const tB = b.created_at ? new Date(b.created_at).getTime() : 0;
      return tB - tA;
    });

    const trs = list.map(r => {
      const priority = String(r.priority || 'medium').toLowerCase();
      const high = priority === 'high';
      const hasPin = Boolean(reportCoords(r));
      return `
        <tr>
          <td>
            <div class="name-cell">
              <div class="ic" style="background:var(--${high ? 'brick-100' : 'sage-100'});color:var(--${high ? 'brick' : 'teal-800'});">${ic(hasPin ? 'pin' : 'queue')}</div>
              <div>
                <div class="t">${esc(r.title)}</div>
                <div class="s">${user.psgcCode ? '' : `${esc(brgyLabels[r.psgc_code] || r.psgc_code || '')} · `}${esc(r.category || 'Report')} · ${r.created_at ? esc(new Date(r.created_at).toLocaleDateString()) : 'Recent'}${hasPin ? ' · 📍 pinned' : ''}${r.ai_severity_score ? ` · AI Severity: ${Number(r.ai_severity_score)}/100` : ''}</div>
              </div>
            </div>
          </td>
          <td><span class="pill ${cssToken(priority, 'medium')}">${esc(priority.toUpperCase())} priority</span></td>
          <td><span class="pill ${cssToken(r.status, 'pending')}">${esc(statusLabel(r.status))}</span></td>
          <td>
            <div class="table-actions">
              <button class="link-btn" data-view-report="${esc(r.id)}">View</button>
            </div>
          </td>
        </tr>
      `;
    }).join('');

    document.getElementById('queue-body').innerHTML =
      trs || '<tr><td colspan="4" style="color:var(--muted);padding:16px 10px;">No records match the current filters.</td></tr>';

    document.querySelectorAll('[data-view-report]').forEach(btn => {
      btn.onclick = () => showScreen('detail', btn.dataset.viewReport);
    });
  };

  // Filters only re-render the table, so the search box keeps focus while typing
  document.getElementById('queue-cat-filter').onchange = renderRows;
  document.getElementById('queue-stat-filter').onchange = renderRows;
  document.getElementById('queue-sort').onchange = renderRows;
  document.getElementById('queue-search').oninput = renderRows;
  
  document.getElementById('tab-queue-active').onclick = () => {
    currentQueueTab = 'active';
    document.getElementById('tab-queue-active').style.borderBottomColor = '#0f766e';
    document.getElementById('tab-queue-active').style.color = '#0f766e';
    document.getElementById('tab-queue-active').style.fontWeight = '600';
    document.getElementById('tab-queue-archived').style.borderBottomColor = 'transparent';
    document.getElementById('tab-queue-archived').style.color = 'var(--muted)';
    document.getElementById('tab-queue-archived').style.fontWeight = '500';
    renderRows();
  };
  document.getElementById('tab-queue-archived').onclick = () => {
    currentQueueTab = 'archived';
    document.getElementById('tab-queue-archived').style.borderBottomColor = '#0f766e';
    document.getElementById('tab-queue-archived').style.color = '#0f766e';
    document.getElementById('tab-queue-archived').style.fontWeight = '600';
    document.getElementById('tab-queue-active').style.borderBottomColor = 'transparent';
    document.getElementById('tab-queue-active').style.color = 'var(--muted)';
    document.getElementById('tab-queue-active').style.fontWeight = '500';
    renderRows();
  };
  renderRows();
  // Overview map: every pinned report, click a pin to open it
  if (pinned.length) {
    const map = createMap('queue-map', [pinned[0].c.lat, pinned[0].c.lng], 15);
    if (map) {
      const bounds = [];
      pinned.forEach(({ r, c }) => {
        bounds.push([c.lat, c.lng]);
        const marker = window.L.marker([c.lat, c.lng]).addTo(map);
        marker.bindTooltip(esc(r.title || 'Report'));
        marker.on('click', () => showScreen('detail', r.id));
      });
      if (bounds.length > 1) map.fitBounds(bounds, { padding: [30, 30], maxZoom: 17 });
    }
  }
}

async function renderDetail(id) {
  let report = null;
  report = await load('this report', () => DataService.getReportById(id), null);

  if (!report) return showScreen('queue');

  const coords = reportCoords(report);
  const locationText = reportLocationText(report);
  const photo = safeUrl(report.photo_url);
  const priority = String(report.priority || 'medium').toLowerCase();

  const aiStatusBadge = report.ai_valid === false
    ? `<span class="pill" style="background:var(--brick-100);color:var(--brick);font-weight:700;">AI FLAGGED: TROLL / SPAM</span>`
    : `<span class="pill" style="background:var(--teal-100);color:var(--teal-900);font-weight:700;">AI VERIFIED GENUINE</span>`;

  const aiCard = (report.ai_severity_score != null || report.ai_triage_reason) ? `
    <div class="panel" style="margin-top: 14px; border-left: 4px solid ${report.ai_valid === false ? 'var(--brick)' : 'var(--teal-700)'};">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
        <h4 style="margin:0;">Automated AI Triage</h4>
        ${aiStatusBadge}
      </div>
      <p style="font-size: 12px; margin: 4px 0;"><b>Calculated Severity:</b> ${Number(report.ai_severity_score ?? 0)}/100</p>
      <p style="font-size: 11.5px; color: var(--muted); margin-top: 6px; line-height: 1.4;">
        <b>Assessment:</b> ${esc(report.ai_triage_reason || 'AI analysis completed without flags.')}
      </p>
    </div>
  ` : '';

  const locationPanel = `
    <div class="panel" style="margin-top:14px;">
      <h4>Reported location</h4>
      ${locationText ? `<p style="font-size:12.5px; margin:0 0 10px 0;">${esc(locationText)}</p>` : ''}
      ${coords ? `
        <div id="report-map" style="height:300px; width:100%; border-radius:8px; overflow:hidden;"></div>
        <div style="display:flex; gap:12px; align-items:center; margin-top:10px; flex-wrap:wrap; font-size:12px;">
          <span style="color:var(--muted);">${coords.lat.toFixed(6)}, ${coords.lng.toFixed(6)}</span>
          <button class="link-btn" id="btn-recenter-map" type="button">Center on pin</button>
          <a class="link-btn" href="${esc(googleMapsUrl(coords))}" target="_blank" rel="noopener noreferrer">Open in Google Maps ↗</a>
        </div>
      ` : '<div class="evidence-thumb">The resident did not pin a location on the map.</div>'}
    </div>
  `;

  const main = `
    <div class="main-head">
      <div>
        <div style="display:flex;align-items:center;gap:10px;">
          <h3>${esc(report.title)}</h3>
          <span class="pill ${cssToken(priority, 'medium')}">${esc(priority.toUpperCase())}</span>
        </div>
        <p>${esc(report.category || 'Report')} · Status: ${esc(statusLabel(report.status))}</p>
      </div>
      <button class="btn-small ghost" id="btn-back-queue">Back to queue</button>
    </div>
    <div style="display:grid;grid-template-columns:1.4fr 1fr;gap:16px;">
      <div>
        <div class="panel">
          <h4>Incident Details</h4>
          <p style="font-size:12.5px;line-height:1.6;margin:0;white-space:pre-line;">${esc(report.description || 'No description provided.')}</p>
          ${photo ? `<div style="margin-top:14px;"><img src="${esc(photo)}" alt="Evidence" style="max-width:100%; border-radius:6px;"></div>` : '<div class="evidence-thumb" style="margin-top:14px;">No photo attached</div>'}
        </div>
        ${locationPanel}
        ${aiCard}
      </div>
      <div>
        <div class="panel">
          <h4>Triage &amp; Management</h4>
          <label class="field-label">Internal notes</label>
          <textarea class="field" id="report-notes" style="height:76px;resize:none;">${esc(report.internal_notes || '')}</textarea>
          <div style="display:flex;gap:8px;margin-top:14px;flex-wrap:wrap;">
            <button class="btn-small" id="btn-mark-progress">Mark in progress</button>
            <button class="btn-small ghost" style="color:var(--sage);" id="btn-mark-resolved">Resolve</button>
            <button class="btn-small ghost" style="color:var(--brick);" id="btn-mark-rejected">Reject</button>
          </div>
        </div>
      </div>
    </div>
  `;
  stage.innerHTML = shell(main, 'queue');

  if (coords) {
    const map = createMap('report-map', [coords.lat, coords.lng], 18);
    if (map) {
      window.L.marker([coords.lat, coords.lng]).addTo(map)
        .bindPopup(esc(locationText || report.title || 'Reported location'))
        .openPopup();
      document.getElementById('btn-recenter-map').onclick = () => map.setView([coords.lat, coords.lng], 18);
    }
  }

  document.getElementById('btn-back-queue').onclick = () => showScreen('queue');

  const setStatus = async (status, btn) => {
    btn.disabled = true;
    try {
      await DataService.updateReport(id, { status, internal_notes: document.getElementById('report-notes').value });
      showScreen('queue');
    } catch (err) {
      btn.disabled = false;
      showError(err, 'Could not update this report.');
    }
  };
  const progressBtn = document.getElementById('btn-mark-progress');
  const resolveBtn = document.getElementById('btn-mark-resolved');
  const rejectBtn = document.getElementById('btn-mark-rejected');
  progressBtn.onclick = () => setStatus('in_progress', progressBtn);
  resolveBtn.onclick = () => setStatus('resolved', resolveBtn);
  rejectBtn.onclick = () => setStatus('rejected', rejectBtn);
}

// ---------------------------------------------------------------------
// ANNOUNCEMENTS
// ---------------------------------------------------------------------
const ANN_CATEGORIES = ['Advisory', 'Health', 'Event'];

function announcementFormHtml(prefix, ann = {}) {
  const current = ann.category || ann.tag || 'Advisory';
  const user = getActiveUser();
  const scopeHtml = user.isSuperAdmin ? `
    <div class="modal-form-group">
      <label>Target Audience (Scope)</label>
      <select class="field" id="${prefix}-scope-type" onchange="document.getElementById('${prefix}-scope-brgy-wrap').style.display = this.value === 'BARANGAY' ? 'block' : 'none'">
        <option value="" ${!ann.psgc_code ? 'selected' : ''}>Nationwide (All Users)</option>
        <option value="REGION:Metro Manila (NCR)" ${ann.psgc_code === 'REGION:Metro Manila (NCR)' ? 'selected' : ''}>Region: Metro Manila (NCR)</option>
        <option value="CITY:City of Makati" ${ann.psgc_code === 'CITY:City of Makati' ? 'selected' : ''}>City: Makati</option>
        <option value="CITY:City of Manila" ${ann.psgc_code === 'CITY:City of Manila' ? 'selected' : ''}>City: Manila</option>
        <option value="CITY:City of Taguig" ${ann.psgc_code === 'CITY:City of Taguig' ? 'selected' : ''}>City: Taguig</option>
        <option value="CITY:Quezon City" ${ann.psgc_code === 'CITY:Quezon City' ? 'selected' : ''}>City: Quezon City</option>
        <option value="CITY:City of Las Piñas" ${ann.psgc_code === 'CITY:City of Las Piñas' ? 'selected' : ''}>City: Las Piñas</option>
        <option value="CITY:City of Parañaque" ${ann.psgc_code === 'CITY:City of Parañaque' ? 'selected' : ''}>City: Parañaque</option>
        <option value="CITY:City of Pasay" ${ann.psgc_code === 'CITY:City of Pasay' ? 'selected' : ''}>City: Pasay</option>
        <option value="CITY:City of Muntinlupa" ${ann.psgc_code === 'CITY:City of Muntinlupa' ? 'selected' : ''}>City: Muntinlupa</option>
        <option value="CITY:City of Pasig" ${ann.psgc_code === 'CITY:City of Pasig' ? 'selected' : ''}>City: Pasig</option>
        <option value="CITY:City of Mandaluyong" ${ann.psgc_code === 'CITY:City of Mandaluyong' ? 'selected' : ''}>City: Mandaluyong</option>
        <option value="CITY:City of Marikina" ${ann.psgc_code === 'CITY:City of Marikina' ? 'selected' : ''}>City: Marikina</option>
        <option value="CITY:City of San Juan" ${ann.psgc_code === 'CITY:City of San Juan' ? 'selected' : ''}>City: San Juan</option>
        <option value="CITY:City of Caloocan" ${ann.psgc_code === 'CITY:City of Caloocan' ? 'selected' : ''}>City: Caloocan</option>
        <option value="CITY:City of Malabon" ${ann.psgc_code === 'CITY:City of Malabon' ? 'selected' : ''}>City: Malabon</option>
        <option value="CITY:City of Navotas" ${ann.psgc_code === 'CITY:City of Navotas' ? 'selected' : ''}>City: Navotas</option>
        <option value="CITY:City of Valenzuela" ${ann.psgc_code === 'CITY:City of Valenzuela' ? 'selected' : ''}>City: Valenzuela</option>
        <option value="CITY:Pateros" ${ann.psgc_code === 'CITY:Pateros' ? 'selected' : ''}>Municipality: Pateros</option>
        <option value="BARANGAY" ${ann.psgc_code && /^[0-9]+$/.test(ann.psgc_code) ? 'selected' : ''}>Specific Barangay...</option>
      </select>
    </div>
    <div class="modal-form-group" id="${prefix}-scope-brgy-wrap" style="display:${ann.psgc_code && /^[0-9]+$/.test(ann.psgc_code) ? 'block' : 'none'};">
      <div id="${prefix}-brgy-current" style="margin-bottom:6px; font-size:13px; font-weight:500;"></div>
      ${barangaySearchHtml(prefix, 'Search to choose a specific barangay...')}
    </div>
  ` : '';

  const tools = [
    ['h1', 'Heading', '<b>H1</b>'],
    ['h2', 'Subheading', '<b>H2</b>'],
    ['bold', 'Bold (Ctrl+B)', '<b>B</b>'],
    ['italic', 'Italic (Ctrl+I)', '<i>I</i>'],
    ['underline', 'Underline (Ctrl+U)', '<u>U</u>'],
    ['list', 'Bullet list', '• List'],
    ['image', 'Insert image (or paste / drag an image into the text)', '🖼 Image']
  ];
  return `
    <div class="modal-form-group">
      <label>Title</label>
      <input class="field" id="${prefix}-title" value="${esc(ann.title || '')}" placeholder="e.g. Free Rabies Vaccination" required>
    </div>
    <div class="modal-form-group">
      <label>Description</label>
      <div class="md-editor">
        <div class="md-toolbar" role="toolbar" aria-label="Formatting">
          ${tools.map(([cmd, label, inner]) => `<button type="button" class="md-btn" data-md="${cmd}" title="${label}" aria-label="${label}">${inner}</button>`).join('')}
          <span class="md-status" id="${prefix}-md-status" aria-live="polite"></span>
        </div>
        <div class="md-panes">
          <textarea class="field md-input" id="${prefix}-desc" placeholder="Details and instructions...&#10;&#10;Tip: paste or drag a photo here to add it." required>${esc(ann.description || '')}</textarea>
          <div class="md-preview-wrap">
            <div class="md-preview-label">Preview on residents' phones</div>
            <div class="md-preview md-body" id="${prefix}-md-preview"></div>
          </div>
        </div>
        <input type="file" id="${prefix}-md-file" accept="image/png,image/jpeg,image/webp,image/gif" hidden>
      </div>
    </div>
    <div class="modal-form-group">
      <label>Event Date <span style="font-weight:400;color:var(--muted);">(optional; it moves to "Past" after this date)</span></label>
      <input class="field" type="date" id="${prefix}-date" value="${esc(ann.event_date || '')}">
    </div>
    <div class="modal-form-group">
      <label>Category</label>
      <select class="field" id="${prefix}-cat">
        ${ANN_CATEGORIES.map(c => `<option value="${c}" ${current === c ? 'selected' : ''}>${c}</option>`).join('')}
      </select>
    </div>
    ${scopeHtml}
  `;
}

/** Wires the toolbar, live preview and image upload of an announcement form. */
function setupAnnouncementEditor(prefix, psgcCode) {
  const ta = document.getElementById(`${prefix}-desc`);
  const preview = document.getElementById(`${prefix}-md-preview`);
  const fileInput = document.getElementById(`${prefix}-md-file`);
  const status = document.getElementById(`${prefix}-md-status`);
  if (!ta) return;

  const refresh = () => {
    preview.innerHTML = renderMarkdown(ta.value) || '<span class="md-empty">Nothing to preview yet.</span>';
  };

  const replaceSelection = (text, selectFrom, selectTo) => {
    const start = ta.selectionStart;
    ta.setRangeText(text, start, ta.selectionEnd, 'end');
    if (selectFrom != null) ta.setSelectionRange(start + selectFrom, start + selectTo);
    ta.focus();
    refresh();
  };

  const wrap = (marker, placeholder) => {
    const sel = ta.value.slice(ta.selectionStart, ta.selectionEnd) || placeholder;
    replaceSelection(marker + sel + marker, marker.length, marker.length + sel.length);
  };

  const prefixLines = (pre, placeholder) => {
    const lineStart = ta.value.lastIndexOf('\n', ta.selectionStart - 1) + 1;
    ta.setSelectionRange(lineStart, ta.selectionEnd);
    const block = ta.value.slice(lineStart, ta.selectionEnd) || placeholder;
    const lines = block.split('\n').map(l => pre + l.replace(/^(#{1,2} |[-*] )/, ''));
    replaceSelection(lines.join('\n'), pre.length, lines.join('\n').length);
  };

  const insertBlock = (text) => {
    const before = ta.value.slice(0, ta.selectionStart);
    const lead = before && !before.endsWith('\n') ? '\n' : '';
    replaceSelection(`${lead}${text}\n`);
  };

  const uploadImages = async (files) => {
    const images = [...files].filter(f => f.type.startsWith('image/'));
    if (!images.length) return;
    for (const file of images) {
      status.textContent = `Uploading ${file.name || 'image'}...`;
      status.classList.remove('err');
      try {
        const url = await DataService.uploadAnnouncementImage(file, psgcCode);
        insertBlock(`![image](${url})`);
        status.textContent = 'Image added';
      } catch (err) {
        console.error(err);
        status.textContent = err?.message || 'Upload failed';
        status.classList.add('err');
      }
    }
  };

  document.querySelectorAll('.md-btn').forEach(btn => {
    btn.onclick = () => {
      switch (btn.dataset.md) {
        case 'h1': return prefixLines('# ', 'Heading');
        case 'h2': return prefixLines('## ', 'Subheading');
        case 'bold': return wrap('**', 'bold text');
        case 'italic': return wrap('*', 'italic text');
        case 'underline': return wrap('__', 'underlined text');
        case 'list': return prefixLines('- ', 'List item');
        case 'image': return fileInput.click();
      }
    };
  });

  ta.addEventListener('keydown', (e) => {
    if (!(e.ctrlKey || e.metaKey)) return;
    const k = e.key.toLowerCase();
    if (k === 'b') { e.preventDefault(); wrap('**', 'bold text'); }
    if (k === 'i') { e.preventDefault(); wrap('*', 'italic text'); }
    if (k === 'u') { e.preventDefault(); wrap('__', 'underlined text'); }
  });
  ta.addEventListener('input', refresh);
  ta.addEventListener('paste', (e) => {
    const files = e.clipboardData?.files;
    if (files && files.length) { e.preventDefault(); uploadImages(files); }
  });
  ta.addEventListener('dragover', (e) => { e.preventDefault(); ta.classList.add('drag'); });
  ta.addEventListener('dragleave', () => ta.classList.remove('drag'));
  ta.addEventListener('drop', (e) => {
    ta.classList.remove('drag');
    if (e.dataTransfer?.files?.length) { e.preventDefault(); uploadImages(e.dataTransfer.files); }
  });
  fileInput.onchange = () => { uploadImages(fileInput.files); fileInput.value = ''; };

  const user = getActiveUser();
  if (user.isSuperAdmin) {
    wireBarangaySearch(prefix, (b) => {
      window[`${prefix}ChosenBrgy`] = b.psgc_code;
      document.getElementById(`${prefix}-brgy-current`).innerHTML = `<b>${esc(b.name)}</b>, ${esc(b.city_municipality)}`;
    });
  }

  refresh();
}

function readAnnouncementForm(prefix) {
  const t = document.getElementById(`${prefix}-title`);
  const d = document.getElementById(`${prefix}-desc`);
  const c = document.getElementById(`${prefix}-cat`);
  const dt = document.getElementById(`${prefix}-date`);
  if (!t.value.trim() || !d.value.trim()) throw new Error('Please provide a title and description.');
  
  const user = getActiveUser();
  let finalPsgcCode = undefined;
  if (user.isSuperAdmin) {
    const scopeType = document.getElementById(`${prefix}-scope-type`).value;
    if (scopeType === 'BARANGAY') {
      finalPsgcCode = window[`${prefix}ChosenBrgy`] || null;
      if (!finalPsgcCode) throw new Error('Please select a specific barangay for the target scope.');
    } else {
      finalPsgcCode = scopeType || null;
    }
  }

  return {
    title: t.value.trim(),
    description: d.value.trim(),
    category: c.value,
    eventDate: dt.value || null,
    psgcCode: finalPsgcCode
  };
}

async function renderAnnouncements() {
  const user = getActiveUser();
  let announcements = [];

  announcements = await load('announcements', () => DataService.getAnnouncements(user.isSuperAdmin ? 'ALL' : user.psgcCode), []);
  // nationwide posts (by the super admin) also reach every resident; show them read-only for officials
  const nationwide = (user.psgcCode && !user.isSuperAdmin)
    ? (await load('nationwide announcements', () => DataService.getAnnouncements(null), [])).filter(a => !a.isArchived)
    : [];

  const isAdminPost = (a) => {
    const role = (a.profiles?.role || '').toLowerCase();
    return role === 'db_admin' || role === 'super_admin' || role === 'superadmin' || !a.psgc_code || String(a.psgc_code).startsWith('CITY:') || String(a.psgc_code).startsWith('REGION:');
  };

  const activeList = announcements.filter(a => !a.isArchived);
  const pastList = announcements.filter(a => a.isArchived);

  const viewYear = calendarViewDate.getFullYear();
  const viewMonth = calendarViewDate.getMonth();
  const viewMonthName = calendarViewDate.toLocaleString('en-US', { month: 'long' });

  const realToday = new Date();
  const isCurrentMonthView = realToday.getFullYear() === viewYear && realToday.getMonth() === viewMonth;

  const eventDays = new Set();
  announcements.forEach(a => {
    if (a.event_date) {
      const [year, month, day] = String(a.event_date).split('-').map(Number);
      if (year === viewYear && month === (viewMonth + 1)) eventDays.add(day);
    }
  });

  const activeItems = activeList.map(a => {
    const canEdit = user.isSuperAdmin || !isAdminPost(a);
    let tagHtml = esc(a.tag || '');
    if (user.isSuperAdmin && a.psgc_code && a.psgc_code.startsWith('CITY:')) tagHtml += ` · ${a.psgc_code.split(':')[1]}`;
    else if (user.isSuperAdmin && a.psgc_code && a.psgc_code.startsWith('REGION:')) tagHtml += ` · NCR`;
    else if (user.isSuperAdmin && !a.psgc_code) tagHtml += ` · Nationwide`;
    
    return `
      <div class="ann-item" style="margin-bottom:12px;">
        <div class="top"><span class="tag" style="color:var(--teal-800);background:var(--teal-100);">${tagHtml}</span></div>
        <p class="title" style="margin:6px 0 2px 0;">${esc(a.title)}</p>
        ${a.description ? `<div class="md-body md-card" style="font-size:12px; color:var(--muted); margin:0 0 6px 0;">${renderMarkdown(a.description)}</div>` : ''}
        <p class="meta">
          ${a.event_date ? `Event: ${esc(a.event_date)} · ` : ''}Posted ${esc(a.posted)}
          ${canEdit ? ` · <a href="#" style="color:var(--teal-800);text-decoration:none;font-weight:600;margin-right:8px;" data-edit-ann="${esc(a.id)}">Edit</a>` : ''}
          ${canEdit ? `<a href="#" style="color:var(--brick);text-decoration:none;font-weight:600;" data-archive-ann="${esc(a.id)}">Archive</a>` : ''}
        </p>
      </div>
    `;
  }).join('');

  const pastItems = pastList.map(a => {
    const canEdit = user.isSuperAdmin || !isAdminPost(a);
    return `
      <div class="ann-item" style="margin-bottom:10px; opacity:0.85; background:#fbfbfa;">
        <div class="top"><span class="tag" style="color:#64748b;background:#f1f5f9;">${esc(a.tag || 'Archived')}</span></div>
        <p class="title" style="margin:4px 0 2px 0; font-size:13.5px;">${esc(a.title)}</p>
        ${a.description ? `<div class="md-body md-card" style="font-size:11.5px; color:var(--muted); margin:0 0 4px 0;">${renderMarkdown(a.description)}</div>` : ''}
        <p class="meta">
          ${a.event_date ? `Event: ${esc(a.event_date)} · ` : ''}${esc(a.posted || 'Past')}
          ${canEdit ? ` · <a href="#" style="color:var(--teal-800);text-decoration:none;font-weight:600;margin-right:8px;" data-repost-ann="${esc(a.id)}">Repost</a>` : ''}
          ${canEdit ? `<a href="#" style="color:var(--charcoal);text-decoration:none;font-weight:600;" data-edit-ann="${esc(a.id)}">Edit</a>` : ''}
        </p>
      </div>
    `;
  }).join('');

  const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
  const firstDayOfWeek = new Date(viewYear, viewMonth, 1).getDay();
  const emptyLeadingDays = [...Array(firstDayOfWeek)].map(() => `<div></div>`).join('');

  const dayCells = [...Array(daysInMonth)].map((_, i) => {
    const dayNum = i + 1;
    const isToday = isCurrentMonthView && (dayNum === realToday.getDate());
    const hasEvent = eventDays.has(dayNum);
    const fullDate = `${viewYear}-${String(viewMonth + 1).padStart(2, '0')}-${String(dayNum).padStart(2, '0')}`;
    return `
      <div class="d ${isToday ? 'today' : ''}" data-day="${fullDate}" style="position: relative; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 32px; ${hasEvent ? 'cursor: pointer;' : ''}">
        <span>${dayNum}</span>
        ${hasEvent ? `<span style="width:5px;height:5px;background-color:${isToday ? '#ffffff' : 'var(--teal-800, #0f766e)'};border-radius:50%;position:absolute;bottom:2px;"></span>` : ''}
      </div>
    `;
  }).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Announcements &amp; calendar</h3>
        <p>${user.isSuperAdmin 
          ? 'Manage all announcements across barangays, cities, and regions.' 
          : `Bulletins published to residents of ${esc(user.barangayName)} on the mobile app.`}</p>
      </div>
      <button class="btn-small" id="btn-new-ann">+ New announcement</button>
    </div>

    <div class="ann-layout" style="display:grid; grid-template-columns: 1.4fr 1fr; gap:20px; align-items:flex-start;">
      <div>
        <h4 style="font-size:13px; color:var(--muted); margin-bottom:10px;">Active Announcements</h4>
        <div style="margin-bottom:24px;">
          ${activeItems || '<p style="color:var(--muted);font-size:12px;">No active announcements published.</p>'}
        </div>

        ${nationwide.length ? `
        <h4 style="font-size:13px; color:var(--muted); margin-bottom:10px;">Nationwide announcements <span style="font-weight:400;">(posted by the super admin, also shown to your residents)</span></h4>
        <div style="margin-bottom:24px;">
          ${nationwide.map(a => `
            <div class="ann-item" style="margin-bottom:10px; background:#fbfbfa;">
              <div class="top"><span class="tag" style="color:#64748b;background:#f1f5f9;">${esc(a.tag)} · Nationwide</span></div>
              <p class="title" style="margin:4px 0 2px 0;">${esc(a.title)}</p>
              ${a.description ? `<div class="md-body md-card" style="font-size:11.5px; color:var(--muted);">${renderMarkdown(a.description)}</div>` : ''}
            </div>`).join('')}
        </div>` : ''}

        <h4 style="font-size:13px; color:var(--muted); margin-bottom:10px;">Past Announcements</h4>
        <div style="max-height: 480px; overflow-y: auto; padding-right: 6px;">
          ${pastItems || '<p style="color:var(--muted);font-size:12px;">No past archived announcements.</p>'}
        </div>
      </div>

      <div class="panel" style="height:fit-content; position:sticky; top:16px;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
          <h4 style="margin: 0;">${esc(viewMonthName)} ${viewYear}</h4>
          <div style="display: flex; gap: 4px;">
            <button id="cal-prev" class="btn-small ghost" style="padding: 2px 8px; font-size: 11px;">◀</button>
            <button id="cal-next" class="btn-small ghost" style="padding: 2px 8px; font-size: 11px;">▶</button>
          </div>
        </div>
        <div class="cal">
          <div class="cal-head">S</div><div class="cal-head">M</div><div class="cal-head">T</div>
          <div class="cal-head">W</div><div class="cal-head">T</div><div class="cal-head">F</div><div class="cal-head">S</div>
          ${emptyLeadingDays}
          ${dayCells}
        </div>
      </div>
    </div>
  `;
  stage.innerHTML = shell(main, 'announcements');

  document.getElementById('cal-prev').onclick = () => {
    calendarViewDate = new Date(viewYear, viewMonth - 1, 1);
    renderAnnouncements();
  };
  document.getElementById('cal-next').onclick = () => {
    calendarViewDate = new Date(viewYear, viewMonth + 1, 1);
    renderAnnouncements();
  };

  document.querySelectorAll('.d[data-day]').forEach(cell => {
    cell.onclick = () => {
      const date = cell.dataset.day;
      const dayEvents = announcements.filter(a => String(a.event_date) === date);
      if (!dayEvents.length) return;
      
      const eventsHtml = dayEvents.map(a => `
        <div style="margin-bottom:12px; padding:12px; border:1px solid #e2e8f0; border-radius:8px; background:#f8fafc;">
          <span style="font-size:11px; font-weight:600; color:var(--teal-800); text-transform:uppercase;">${esc(a.category)}</span>
          <p style="margin:4px 0; font-weight:600; color:#0f172a;">${esc(a.title)}</p>
          ${a.description ? `<div class="md-body md-card" style="font-size:12px; color:var(--muted); margin:0 0 6px 0;">${renderMarkdown(a.description)}</div>` : ''}
        </div>
      `).join('');
      
      openCustomModal({
        title: `Events on ${date}`,
        contentHtml: eventsHtml,
        confirmText: 'Close',
        hideCancel: true
      });
    };
  });

  document.getElementById('btn-new-ann').onclick = () => {
    openCustomModal({
      title: 'Publish announcement',
      contentHtml: announcementFormHtml('ann'),
      width: 860,
      onOpen: () => setupAnnouncementEditor('ann', user.psgcCode),
      confirmText: 'Publish',
      onConfirm: async () => {
        await DataService.createAnnouncement({
          ...readAnnouncementForm('ann'),
          psgcCode: user.psgcCode,
          authorId: user.id
        });
        closeModal();
        renderAnnouncements();
      }
    });
  };

  document.querySelectorAll('[data-archive-ann]').forEach(btn => {
    btn.onclick = async (e) => {
      e.preventDefault();
      try {
        await DataService.archiveAnnouncement(btn.dataset.archiveAnn);
        renderAnnouncements();
      } catch (err) {
        showError(err, 'Could not archive this announcement.');
      }
    };
  });

  const openEditor = (ann, { repost = false } = {}) => {
    openCustomModal({
      title: repost ? 'Repost announcement' : 'Edit announcement',
      contentHtml: (repost && ann.isPastEvent
        ? '<p style="font-size:12px;color:var(--muted);margin:0 0 12px 0;">This event date has passed. Set a new date (or clear it) so residents see it again.</p>'
        : '') + announcementFormHtml('edit-ann', ann),
      width: 860,
      onOpen: () => setupAnnouncementEditor('edit-ann', user.psgcCode),
      confirmText: repost ? 'Repost' : 'Save Changes',
      onConfirm: async () => {
        const form = readAnnouncementForm('edit-ann');
        if (repost && form.eventDate && form.eventDate < localDateString(new Date())) {
          throw new Error('Pick today or a future date, or clear the date, so the announcement is visible again.');
        }
        await DataService.updateAnnouncement(ann.id, { ...form, ...(repost ? { isArchived: false } : {}) });
        closeModal();
        renderAnnouncements();
      }
    });
  };

  document.querySelectorAll('[data-repost-ann]').forEach(btn => {
    btn.onclick = (e) => {
      e.preventDefault();
      const ann = announcements.find(a => String(a.id) === btn.dataset.repostAnn);
      if (ann) openEditor(ann, { repost: true });
    };
  });

  document.querySelectorAll('[data-edit-ann]').forEach(btn => {
    btn.onclick = (e) => {
      e.preventDefault();
      const ann = announcements.find(a => String(a.id) === btn.dataset.editAnn);
      if (ann) openEditor(ann);
    };
  });
}

// ---------------------------------------------------------------------
// EMERGENCY CONTACTS
// ---------------------------------------------------------------------
function contactFormHtml(prefix, contact = {}) {
  const current = contact.category || 'Barangay';
  const user = getActiveUser();
  let scopeHtml = '';
  
  if (user.isSuperAdmin) {
    const isNationwide = !contact.psgc_code;
    const isCity = contact.psgc_code && contact.psgc_code.startsWith('CITY:');
    const isRegion = contact.psgc_code && contact.psgc_code.startsWith('REGION:');
    const isBrgy = contact.psgc_code && !isCity && !isRegion;
    const scopeType = isNationwide ? '' : (isCity ? 'CITY' : (isRegion ? 'REGION' : 'BARANGAY'));
    
    scopeHtml = `
      <div class="modal-form-group">
        <label>Target Scope</label>
        <select class="field" id="${prefix}-scope-type" style="margin-bottom:8px;">
          <option value="">Nationwide (All users)</option>
          <option value="REGION" ${scopeType === 'REGION' ? 'selected' : ''}>Region-wide</option>
          <option value="CITY" ${scopeType === 'CITY' ? 'selected' : ''}>City-wide</option>
          <option value="BARANGAY" ${scopeType === 'BARANGAY' ? 'selected' : ''}>Specific Barangay</option>
        </select>
        <div id="${prefix}-scope-region" style="display:${scopeType === 'REGION' ? 'block' : 'none'};">
          <select class="field" id="${prefix}-region-val">
            ${['NCR', 'CAR', 'Region I', 'Region II', 'Region III', 'Region IV-A', 'MIMAROPA', 'Region V', 'Region VI', 'Region VII', 'Region VIII', 'Region IX', 'Region X', 'Region XI', 'Region XII', 'Region XIII', 'BARMM'].map(r => 
              `<option value="REGION:${r}" ${contact.psgc_code === `REGION:${r}` ? 'selected' : ''}>${r}</option>`
            ).join('')}
          </select>
        </div>
        <div id="${prefix}-scope-city" style="display:${scopeType === 'CITY' ? 'block' : 'none'};">
          <input type="text" class="field" id="${prefix}-city-val" placeholder="e.g. Makati" value="${isCity ? esc(contact.psgc_code.split(':')[1]) : ''}">
        </div>
        <div id="${prefix}-scope-brgy" style="display:${scopeType === 'BARANGAY' ? 'block' : 'none'}; padding:12px; background:#f8fafc; border:1px solid #e2e8f0; border-radius:6px;">
          <p style="font-size:12px;color:var(--muted);margin-bottom:6px;">Search and select the target barangay:</p>
          ${barangaySearchHtml(`${prefix}-brgy`)}
          <p style="font-size:12px;margin-top:6px;">Current: <span id="${prefix}-brgy-current" style="color:var(--charcoal);">${isBrgy ? '<i>Loading...</i>' : 'None'}</span></p>
        </div>
      </div>
    `;
  }

  return `
    <div class="modal-form-group">
      <label>Agency / Contact Name</label>
      <input class="field" id="${prefix}-name" value="${esc(contact.name || '')}" required placeholder="e.g. Police Action Center">
    </div>
    <div class="modal-form-group">
      <label>Category</label>
      <select class="field" id="${prefix}-cat">
        ${['Barangay', 'City', 'National'].map(c => `<option value="${c}" ${current === c ? 'selected' : ''}>${c}</option>`).join('')}
      </select>
    </div>
    <div class="modal-form-group">
      <label>Contact Number</label>
      <input class="field" id="${prefix}-num" value="${esc(contact.num || '')}" required placeholder="e.g. 911 or 0917-XXX-XXXX">
    </div>
    ${scopeHtml}
  `;
}

async function renderEmergency() {
  const user = getActiveUser();
  let contacts = [];

  contacts = await load('emergency contacts', () => DataService.getEmergencyContacts(user.psgcCode), []);
  const nationwideContacts = user.psgcCode
    ? await load('nationwide hotlines', () => DataService.getEmergencyContacts(null), [])
    : [];

  const trs = contacts.map(r => `
    <tr>
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--sage-100);color:#3E6552;">${ic('phone')}</div>
          <div class="t">${esc(r.name)}</div>
        </div>
      </td>
      <td>${esc(r.category)}</td>
      <td><b>${esc(r.num || 'No number')}</b></td>
      <td>
        <div class="table-actions">
          <button class="link-btn" data-edit-em="${esc(r.id)}">Edit</button>
          <button class="link-btn danger" data-del-em="${esc(r.id)}">Delete</button>
        </div>
      </td>
    </tr>
  `).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Emergency contacts</h3>
        <p>${user.psgcCode
          ? `Hotlines shown in the mobile app to residents of ${esc(user.barangayName)}.`
          : 'Nationwide hotlines: shown to residents of <b>every</b> barangay.'}</p>
      </div>
      <button class="btn-small" id="btn-add-em">+ Add contact</button>
    </div>
    <table class="table">
      <thead><tr><th>Name</th><th>Category</th><th>Number</th><th></th></tr></thead>
      <tbody>${trs || '<tr><td colspan="4" style="color:var(--muted);padding:16px 10px;">No contacts registered.</td></tr>'}</tbody>
    </table>
    ${nationwideContacts.length ? `
      <h4 style="font-size:13px; color:var(--muted); margin:22px 0 8px;">Nationwide hotlines <span style="font-weight:400;">(managed by the super admin, also shown to your residents)</span></h4>
      <table class="table">
        <tbody>${nationwideContacts.map(c => `
          <tr><td><div class="name-cell"><div class="ic" style="background:#f1f5f9;color:#64748b;">${ic('phone')}</div><div class="t">${esc(c.name)}</div></div></td>
              <td>${esc(c.category)}</td><td><b>${esc(c.num || 'No number')}</b></td><td></td></tr>`).join('')}
        </tbody>
      </table>` : ''}
  `;
  stage.innerHTML = shell(main, 'emergency');

  const setupEmergencyScope = (prefix, contact) => {
    if (!user.isSuperAdmin) return;
    const sel = document.getElementById(`${prefix}-scope-type`);
    const reg = document.getElementById(`${prefix}-scope-region`);
    const cty = document.getElementById(`${prefix}-scope-city`);
    const bgy = document.getElementById(`${prefix}-scope-brgy`);
    
    sel.onchange = (e) => {
      reg.style.display = e.target.value === 'REGION' ? 'block' : 'none';
      cty.style.display = e.target.value === 'CITY' ? 'block' : 'none';
      bgy.style.display = e.target.value === 'BARANGAY' ? 'block' : 'none';
    };

    wireBarangaySearch(`${prefix}-brgy`, (b) => {
      window[`${prefix}ChosenBrgy`] = b.psgc_code;
      document.getElementById(`${prefix}-brgy-current`).innerHTML = `<b>${esc(b.name)}</b>, ${esc(b.city_municipality)}`;
    });
    
    if (contact?.psgc_code && !contact.psgc_code.startsWith('CITY:') && !contact.psgc_code.startsWith('REGION:')) {
      window[`${prefix}ChosenBrgy`] = contact.psgc_code;
      DataService.getBarangayLabels([contact.psgc_code]).then(lbls => {
        const c = document.getElementById(`${prefix}-brgy-current`);
        if (c) c.innerHTML = `<b>${esc(lbls[contact.psgc_code] || contact.psgc_code)}</b>`;
      });
    } else {
      window[`${prefix}ChosenBrgy`] = null;
    }
  };

  const getEmergencyScope = (prefix) => {
    if (!user.isSuperAdmin) return user.psgcCode;
    const type = document.getElementById(`${prefix}-scope-type`).value;
    if (type === 'REGION') return document.getElementById(`${prefix}-region-val`).value;
    if (type === 'CITY') return `CITY:${document.getElementById(`${prefix}-city-val`).value.trim()}`;
    if (type === 'BARANGAY') return window[`${prefix}ChosenBrgy`] || null;
    return null;
  };

  document.getElementById('btn-add-em').onclick = () => {
    openCustomModal({
      title: 'Add emergency contact',
      contentHtml: contactFormHtml('em'),
      confirmText: 'Save Contact',
      onOpen: () => setupEmergencyScope('em'),
      onConfirm: async () => {
        let finalPsgcCode = getEmergencyScope('em');
        if (user.isSuperAdmin && document.getElementById('em-scope-type').value === 'BARANGAY' && !finalPsgcCode) {
          throw new Error('Please select a specific barangay for the target scope.');
        }
        await DataService.createEmergencyContact({
          name: document.getElementById('em-name').value.trim(),
          category: document.getElementById('em-cat').value,
          num: document.getElementById('em-num').value.trim(),
          psgcCode: finalPsgcCode
        });
        closeModal();
        renderEmergency();
      }
    });
  };

  document.querySelectorAll('[data-edit-em]').forEach(b => {
    b.onclick = () => {
      const contact = contacts.find(c => String(c.id) === b.dataset.editEm);
      if (!contact) return;
      openCustomModal({
        title: 'Edit emergency contact',
        contentHtml: contactFormHtml('em-edit', contact),
        confirmText: 'Update Contact',
        onOpen: () => setupEmergencyScope('em-edit', contact),
        onConfirm: async () => {
          let finalPsgcCode = getEmergencyScope('em-edit');
          if (user.isSuperAdmin && document.getElementById('em-edit-scope-type').value === 'BARANGAY' && !finalPsgcCode) {
            throw new Error('Please select a specific barangay for the target scope.');
          }
          await DataService.updateEmergencyContact(contact.id, {
            name: document.getElementById('em-edit-name').value.trim(),
            category: document.getElementById('em-edit-cat').value,
            num: document.getElementById('em-edit-num').value.trim(),
            psgcCode: finalPsgcCode
          });
          closeModal();
          renderEmergency();
        }
      });
    };
  });

  document.querySelectorAll('[data-del-em]').forEach(b => {
    b.onclick = async () => {
      if (!confirm('Delete this emergency contact?')) return;
      try {
        await DataService.deleteEmergencyContact(b.dataset.delEm);
        renderEmergency();
      } catch (err) {
        showError(err, 'Could not delete this contact.');
      }
    };
  });
}


// ---------------------------------------------------------------------
// SUPER ADMIN: barangay switcher
// ---------------------------------------------------------------------
function barangayLabel(b) {
  const city = String(b.city_municipality || b.city || '').replace(/^City of\s+/i, '');
  return `${b.name}${city ? `, ${city}` : ''}`;
}

/** Search box over the barangays table. onPick(row | null). */
function barangaySearchHtml(prefix, placeholder = 'Search barangay, city or PSGC code...') {
  return `
    <input class="field" id="${prefix}-q" placeholder="${esc(placeholder)}" autocomplete="off">
    <div class="brgy-results" id="${prefix}-results" role="listbox"></div>`;
}

function wireBarangaySearch(prefix, onPick) {
  const q = document.getElementById(`${prefix}-q`);
  const results = document.getElementById(`${prefix}-results`);
  let timer = null;
  let seq = 0;
  const run = async () => {
    const mine = ++seq;
    results.innerHTML = '<div class="brgy-hint">Searching...</div>';
    try {
      const rows = await DataService.searchBarangays(q.value, 40);
      if (mine !== seq) return;
      results.innerHTML = rows.length
        ? rows.map(b => `
            <button type="button" class="brgy-opt" data-code="${esc(b.psgc_code)}">
              <span>${esc(barangayLabel(b))}</span><small>${esc([b.province, b.psgc_code].filter(Boolean).join(' · '))}</small>
            </button>`).join('')
        : '<div class="brgy-hint">No barangay found. Super admins can add one under Barangays.</div>';
      results.querySelectorAll('.brgy-opt').forEach(btn => {
        btn.onclick = () => onPick(rows.find(r => r.psgc_code === btn.dataset.code));
      });
    } catch (err) {
      results.innerHTML = `<div class="brgy-hint err">${esc(err.message || 'Search failed')}</div>`;
    }
  };
  q.oninput = () => { clearTimeout(timer); timer = setTimeout(run, 250); };
  run();
  setTimeout(() => q.focus(), 0);
}

function openScopePicker() {
  const user = getActiveUser();
  openCustomModal({
    title: 'Switch barangay',
    width: 520,
    contentHtml: `
      <p style="font-size:12px;color:var(--muted);margin:0 0 10px;">You are viewing <b>${esc(user.barangayName)}</b>.
        Every screen (reports, requests, approvals, announcements, contacts) will show the barangay you pick.</p>
      <button type="button" class="brgy-opt brgy-all" id="scope-all">
        <span>🌏 All barangays (nationwide)</span><small>Reports &amp; requests from every barangay · nationwide announcements &amp; hotlines</small>
      </button>
      ${barangaySearchHtml('scope')}`,
    confirmText: 'Close',
    confirmClass: 'btn-small ghost',
    onConfirm: async () => closeModal(),
    onOpen: () => {
      const pick = (b) => {
        const u = getActiveUser();
        u.psgcCode = b ? b.psgc_code : null;
        u.barangayName = b ? `Barangay ${barangayLabel(b)}` : 'All barangays (nationwide)';
        setActiveUser(u);
        closeModal();
        showScreen(currentScreen === 'detail' ? 'queue' : currentScreen);
      };
      document.getElementById('scope-all').onclick = () => pick(null);
      wireBarangaySearch('scope', pick);
    }
  });
}

// ---------------------------------------------------------------------
// SUPER ADMIN: users & officials
// ---------------------------------------------------------------------
const ROLE_OPTIONS = [
  ['resident', 'Resident (mobile app)'],
  ['official', 'Barangay official (this portal, one barangay)'],
  ['db_admin', 'Super admin (all barangays)']
];
const STATUS_OPTIONS = [['pending', 'Pending'], ['approved', 'Approved'], ['rejected', 'Rejected']];
let userFilters = { search: '', role: '', status: '', allBarangays: false };

function roleBadge(role) {
  const r = String(role || 'resident').toLowerCase();
  if (['db_admin', 'super_admin', 'superadmin'].includes(r)) return '<span class="pill high">Super admin</span>';
  if (r === 'resident') return '<span class="pill low">Resident</span>';
  return `<span class="pill medium">${esc(r === 'official' ? 'Official' : r)}</span>`;
}

async function renderUsers() {
  const user = getActiveUser();
  const scope = userFilters.allBarangays ? null : user.psgcCode;
  const people = await load('users', () => DataService.listProfiles({ ...userFilters, psgcCode: scope }), []);
  const labels = await DataService.getBarangayLabels(people.map(p => p.psgc_code));
  
  // Sort by Region > City > Barangay (using the labels alphabetically)
  people.sort((a, b) => (labels[a.psgc_code] || '').localeCompare(labels[b.psgc_code] || ''));

  const rows = people.map(p => {
    const name = [p.first_name, p.last_name].filter(Boolean).join(' ') || '(no name)';
    return `
      <tr>
        <td><div class="name-cell"><div class="ic" style="background:var(--teal-100);color:var(--teal-800);">${ic('user')}</div>
          <div><div class="t">${esc(name)}</div><div class="s">${esc(p.email || '')}</div></div></div></td>
        <td>${roleBadge(p.role)}</td>
        <td><span class="pill ${cssToken(p.account_status, 'pending')}">${esc(statusLabel(p.account_status))}</span></td>
        <td style="font-size:12px;">${p.psgc_code ? esc(labels[p.psgc_code] || p.psgc_code) : '<span style="color:var(--muted);">— none —</span>'}</td>
        <td style="text-align:right;">
          ${user.isSuperAdmin ? `
            <select class="user-action-select field" data-id="${esc(p.id)}" style="padding: 4px 8px; font-size:12px; height:auto; display:inline-block; width:auto;">
              <option value="">Manage...</option>
              <option value="edit">Edit roles &amp; info</option>
            </select>
          ` : `
            <button class="btn-small ghost view-user-btn" data-id="${esc(p.id)}">View</button>
          `}
        </td>
      </tr>`;
  }).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Users &amp; officials</h3>
        <p>Set who is a resident, a barangay official or a super admin, approve accounts, and move people to another barangay.
           New accounts are created in Supabase (Authentication → Add user) or by signing up in the app, then managed here.</p>
      </div>
      ${user.isSuperAdmin ? `<button class="btn-small" id="btn-add-user">+ Add User</button>` : ''}
    </div>
    <div class="filters">
      <input id="u-search" placeholder="Search name or email..." value="${esc(userFilters.search)}">
      <select id="u-role">
        <option value="">All roles</option>
        <option value="resident" ${userFilters.role === 'resident' ? 'selected' : ''}>Residents</option>
        <option value="official" ${userFilters.role === 'official' ? 'selected' : ''}>Officials</option>
        <option value="db_admin" ${userFilters.role === 'db_admin' ? 'selected' : ''}>Super admins</option>
      </select>
      <select id="u-status">
        <option value="">All statuses</option>
        ${STATUS_OPTIONS.map(([v, l]) => `<option value="${v}" ${userFilters.status === v ? 'selected' : ''}>${l}</option>`).join('')}
      </select>
      ${user.psgcCode ? `<label class="u-all"><input type="checkbox" id="u-all" ${userFilters.allBarangays ? 'checked' : ''}> All barangays</label>` : ''}
    </div>
    <p style="font-size:11.5px;color:var(--muted);margin:0 0 8px;">${people.length >= 300 ? 'Showing the newest 300 - narrow the search.' : `${people.length} account(s)`}
      ${scope ? ` in ${esc(user.barangayName)}` : ' in all barangays'}</p>
    <table class="table">
      <thead><tr><th>Person</th><th>Role</th><th>Status</th><th>Barangay</th><th></th></tr></thead>
      <tbody>${rows || '<tr><td colspan="5" style="color:var(--muted);padding:16px 10px;">No accounts match.</td></tr>'}</tbody>
    </table>`;
  stage.innerHTML = shell(main, 'users');

  let timer = null;
  document.getElementById('u-search').oninput = (e) => {
    clearTimeout(timer);
    timer = setTimeout(() => { userFilters.search = e.target.value; renderUsers().then(() => {
      const el = document.getElementById('u-search'); el.focus(); el.setSelectionRange(el.value.length, el.value.length);
    }); }, 350);
  };
  document.getElementById('u-role').onchange = (e) => { userFilters.role = e.target.value; renderUsers(); };
  document.getElementById('u-status').onchange = (e) => { userFilters.status = e.target.value; renderUsers(); };
  const all = document.getElementById('u-all');
  if (all) all.onchange = (e) => { userFilters.allBarangays = e.target.checked; renderUsers(); };

  if (user.isSuperAdmin) {
    document.querySelectorAll('.user-action-select').forEach(sel => {
      sel.onchange = (e) => {
        const action = e.target.value;
        e.target.value = ''; // reset
        if (!action) return;
        const person = people.find(p => p.id === sel.dataset.id);
        if (person && action === 'edit') openUserEditor(person, labels[person.psgc_code], false);
      };
    });
    document.getElementById('btn-add-user').onclick = () => {
      openCustomModal({
        title: 'Add a new user',
        contentHtml: '<p style="font-size:13px;color:var(--charcoal);line-height:1.5;">To create a new user account, please use the <b>Supabase Dashboard (Authentication &rarr; Add User)</b> or have the user sign up via the mobile app. Once they have an account, you can manage their roles and barangay here.</p>',
        hideCancel: true,
        confirmText: 'Got it'
      });
    };
  } else {
    document.querySelectorAll('.view-user-btn').forEach(btn => {
      btn.onclick = () => {
        const person = people.find(p => p.id === btn.dataset.id);
        if (person) openUserEditor(person, labels[person.psgc_code], true);
      };
    });
  }
}

function openUserEditor(person, currentLabel, readOnly = false) {
  const me = getActiveUser();
  const isMe = person.id === me.id;
  let chosen = person.psgc_code ? { psgc_code: person.psgc_code, label: currentLabel || person.psgc_code } : null;
  const role = String(person.role || 'resident').toLowerCase();
  const name = [person.first_name, person.last_name].filter(Boolean).join(' ') || person.email;

  openCustomModal({
    title: `Edit ${name}`,
    width: 560,
    contentHtml: `
      <p style="font-size:12px;color:var(--muted);margin:0 0 12px;">${esc(person.email || '')}${person.mobile_number ? ` · ${esc(person.mobile_number)}` : ''}</p>
      <div class="modal-form-group">
        <label>Role</label>
        <select class="field" id="ue-role" ${isMe || readOnly ? 'disabled' : ''}>
          ${ROLE_OPTIONS.map(([v, l]) => `<option value="${v}" ${role === v || (v === 'official' && !['resident', 'db_admin', 'super_admin', 'superadmin'].includes(role)) ? 'selected' : ''}>${l}</option>`).join('')}
        </select>
        ${isMe && !readOnly ? '<p class="brgy-hint">You can\'t change your own role.</p>' : ''}
      </div>
      <div class="modal-form-group">
        <label>Account status</label>
        <select class="field" id="ue-status" ${readOnly ? 'disabled' : ''}>
          ${STATUS_OPTIONS.map(([v, l]) => `<option value="${v}" ${String(person.account_status || 'pending').toLowerCase() === v ? 'selected' : ''}>${l}</option>`).join('')}
        </select>
      </div>
      <div class="modal-form-group">
        <label>Barangay</label>
        <div class="brgy-current" id="ue-brgy-current"></div>
        ${readOnly ? '' : barangaySearchHtml('ue', 'Search to change the barangay...')}
      </div>`,
    confirmText: readOnly ? 'Close' : 'Save changes',
    hideCancel: readOnly,
    onOpen: () => {
      const cur = document.getElementById('ue-brgy-current');
      const paint = () => {
        cur.innerHTML = chosen
          ? `<b>${esc(chosen.label)}</b> <small>${esc(chosen.psgc_code)}</small> ${readOnly ? '' : `<button type="button" class="link-btn" id="ue-clear">Remove</button>`}`
          : '<span style="color:var(--muted);">No barangay (only allowed for super admins)</span>';
        const clr = document.getElementById('ue-clear');
        if (clr && !readOnly) clr.onclick = () => { chosen = null; paint(); };
      };
      paint();
      if (!readOnly) wireBarangaySearch('ue', (b) => { chosen = { psgc_code: b.psgc_code, label: barangayLabel(b) }; paint(); });
    },
    onConfirm: async () => {
      if (readOnly) { closeModal(); return; }
      const newRole = isMe ? person.role : document.getElementById('ue-role').value;
      const newStatus = document.getElementById('ue-status').value;
      if (newRole !== 'db_admin' && !chosen) throw new Error('Residents and officials need a barangay.');
      
      let reason = null;
      if (newStatus === 'rejected') {
        reason = prompt('Please enter the reason for rejection:');
        if (reason === null) return; // User cancelled
      }

      await DataService.updateProfileAdmin(person.id, {
        role: newRole,
        account_status: newStatus,
        psgc_code: chosen ? chosen.psgc_code : null,
        rejection_reason: reason
      });
      closeModal();
      renderUsers();
    }
  });
}

// ---------------------------------------------------------------------
// SUPER ADMIN: barangays list
// ---------------------------------------------------------------------
let barangayQuery = '';

async function renderBarangays() {
  const rows = await load('barangays', () => DataService.searchBarangays(barangayQuery, 100), []);
  const trs = rows.map(b => `
    <tr>
      <td><div class="t" style="font-weight:600;">${esc(b.name)}</div><div class="s">${esc(b.psgc_code)}</div></td>
      <td>${esc(b.city_municipality || b.city || '')}</td>
      <td>${esc(b.province || '')}</td>
      <td>${esc(b.region || '')}</td>
      <td><div class="table-actions">
        <button class="link-btn" data-view-brgy="${esc(b.psgc_code)}">View</button>
        <button class="link-btn" data-edit-brgy="${esc(b.psgc_code)}">Edit</button>
      </div></td>
    </tr>`).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Barangays</h3>
        <p>The barangay list used for portal headers and the switcher. Codes are 10-digit PSGC codes.</p>
      </div>
      <button class="btn-small" id="btn-add-brgy">+ Add barangay</button>
    </div>
    <div class="filters"><input id="b-search" placeholder="Search name, city, province or code..." value="${esc(barangayQuery)}"></div>
    <p style="font-size:11.5px;color:var(--muted);margin:0 0 8px;">${rows.length >= 100 ? 'Showing the first 100 matches - narrow the search.' : `${rows.length} barangay(s)`}</p>
    <table class="table">
      <thead><tr><th>Barangay</th><th>City / municipality</th><th>Province</th><th>Region</th><th></th></tr></thead>
      <tbody>${trs || '<tr><td colspan="5" style="color:var(--muted);padding:16px 10px;">No barangays match.</td></tr>'}</tbody>
    </table>`;
  stage.innerHTML = shell(main, 'barangays');

  let timer = null;
  document.getElementById('b-search').oninput = (e) => {
    clearTimeout(timer);
    timer = setTimeout(() => { barangayQuery = e.target.value; renderBarangays().then(() => {
      const el = document.getElementById('b-search'); el.focus(); el.setSelectionRange(el.value.length, el.value.length);
    }); }, 350);
  };

  const editor = (b) => {
    const isNew = !b;
    b = b || {};
    openCustomModal({
      title: isNew ? 'Add barangay' : `Edit ${b.name}`,
      contentHtml: `
        <div class="modal-form-group"><label>PSGC code (10 digits)</label>
          <input class="field" id="bf-code" value="${esc(b.psgc_code || '')}" ${isNew ? '' : 'readonly'} required pattern="\\d{10}" inputmode="numeric"></div>
        <div class="modal-form-group"><label>Barangay name</label><input class="field" id="bf-name" value="${esc(b.name || '')}" required></div>
        <div class="modal-form-group"><label>City / municipality</label><input class="field" id="bf-city" value="${esc(b.city_municipality || b.city || '')}" required></div>
        <div class="modal-form-group"><label>Province</label><input class="field" id="bf-prov" value="${esc(b.province || '')}"></div>
        <div class="modal-form-group"><label>Region</label><input class="field" id="bf-region" value="${esc(b.region || '')}"></div>`,
      confirmText: isNew ? 'Add' : 'Save',
      onConfirm: async () => {
        await DataService.saveBarangay({
          psgc_code: document.getElementById('bf-code').value,
          name: document.getElementById('bf-name').value,
          city_municipality: document.getElementById('bf-city').value,
          province: document.getElementById('bf-prov').value,
          region: document.getElementById('bf-region').value
        }, isNew);
        closeModal();
        renderBarangays();
      }
    });
  };
  document.getElementById('btn-add-brgy').onclick = () => editor(null);
  document.querySelectorAll('[data-edit-brgy]').forEach(btn => {
    btn.onclick = () => editor(rows.find(r => r.psgc_code === btn.dataset.editBrgy));
  });
  document.querySelectorAll('[data-view-brgy]').forEach(btn => {
    btn.onclick = () => {
      const b = rows.find(r => r.psgc_code === btn.dataset.viewBrgy);
      const u = getActiveUser();
      u.psgcCode = b.psgc_code;
      u.barangayName = `Barangay ${barangayLabel(b)}`;
      setActiveUser(u);
      showScreen('dashboard');
    };
  });
}

// ---------------------------------------------------------------------
// START: only trust the saved portal user if Supabase still has a session
// ---------------------------------------------------------------------
(async () => {
  const saved = getActiveUser();
  if (saved && await DataService.hasSession()) {
    lastSessionCheck = 0;
    showScreen('dashboard');
  } else {
    setActiveUser(null);
    showScreen('login');
  }
})();

// Image zooming functionality
window.zoomImage = function(src) {
  const overlay = document.createElement('div');
  overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.85);z-index:999999;display:flex;align-items:center;justify-content:center;cursor:zoom-out;opacity:0;transition:opacity 0.2s;';
  
  const img = document.createElement('img');
  img.src = src;
  img.style.cssText = 'max-width:90%;max-height:90%;object-fit:contain;border-radius:8px;box-shadow:0 10px 25px rgba(0,0,0,0.5);transform:scale(0.95);transition:transform 0.2s;';
  
  overlay.appendChild(img);
  document.body.appendChild(overlay);
  
  // Trigger animation
  requestAnimationFrame(() => {
    overlay.style.opacity = '1';
    img.style.transform = 'scale(1)';
  });
  
  overlay.onclick = () => {
    overlay.style.opacity = '0';
    img.style.transform = 'scale(0.95)';
    setTimeout(() => overlay.remove(), 200);
  };
};
