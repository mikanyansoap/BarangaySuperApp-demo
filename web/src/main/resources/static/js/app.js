import { DataService, isOfficialProfile, reportCoords, reportLocationText, localDateString } from './db.js';

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

function statusLabel(status) {
  const s = String(status || 'pending').toLowerCase();
  return ({
    pending: 'Pending',
    in_progress: 'In progress',
    ready_for_pickup: 'Ready for pickup',
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
function openCustomModal({ title, contentHtml, onConfirm, confirmText = 'Save', confirmClass = 'btn-small' }) {
  modalRoot.innerHTML = `
    <div class="modal-overlay">
      <div class="modal-box" style="width: 460px;">
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
        <p class="brgy-sub">Official Portal</p>
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
      <div class="main">${mainHtml}</div>
    </div>
  `;
}

async function showScreen(screen, param) {
  closeModal();
  destroyMaps();

  const user = getActiveUser();
  if (!user && screen !== 'login') {
    return renderLogin();
  }

  switch (screen) {
    case 'login': renderLogin(); break;
    case 'dashboard': await renderDashboard(); break;
    case 'approvals': await renderApprovals(); break;
    case 'queue': await renderQueue(); break;
    case 'detail': await renderDetail(param); break;
    case 'documents': await renderDocuments(); break;
    case 'announcements': await renderAnnouncements(); break;
    case 'emergency': await renderEmergency(); break;
  }
}

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

      // Every check below fails CLOSED: no profile / no role / no barangay = no access.
      let denial = '';
      if (!profile) {
        denial = 'No profile was found for this account. Ask your administrator to set it up.';
      } else if (!isOfficialProfile(profile)) {
        denial = String(profile.role || 'resident').toLowerCase() === 'resident'
          ? 'This portal is for barangay officials only. Residents should use the mobile app.'
          : 'Your official account is not active yet. Ask your administrator to approve it.';
      } else if (!profile.psgc_code) {
        denial = 'Your official account has no barangay assigned (psgc_code). Ask your administrator to set it.';
      }

      if (denial) {
        await DataService.logout();
        showLoginError(denial);
        return;
      }

      const fullName = [profile.first_name, profile.last_name].filter(Boolean).join(' ')
        || authResult.user.user_metadata?.full_name || email;
      const barangayName = await DataService.getBarangayName(profile);

      setActiveUser({
        id: authResult.user.id,
        email: authResult.user.email,
        fullName,
        role: profile.role,
        position: profile.position || profile.role || 'Official',
        psgcCode: profile.psgc_code,
        barangayName
      });

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

  try {
    [reports, docs, announcements] = await Promise.all([
      DataService.getReports(user.psgcCode),
      DataService.getDocuments(user.psgcCode),
      DataService.getAnnouncements(user.psgcCode)
    ]);
  } catch (err) {
    console.error('Error fetching dashboard statistics:', err);
  }

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
      val: reports.filter(r => r.created_at && localDateString(new Date(r.created_at)) === key).length
    });
  }

  const maxVal = Math.max(4, ...trendPoints.map(p => p.val));
  const chartHeight = 110;
  const chartWidth = 400;
  const startX = 30;
  const stepX = (chartWidth - startX) / (trendPoints.length - 1);

  const coords = trendPoints.map((p, i) => {
    const x = startX + (i * stepX);
    const y = chartHeight - (p.val / maxVal * (chartHeight - 20)) + 10;
    return { x, y, ...p };
  });

  const linePath = coords.map((c, i) => (i === 0 ? `M ${c.x} ${c.y}` : `L ${c.x} ${c.y}`)).join(' ');
  const areaPath = `${linePath} L ${coords[coords.length - 1].x} ${chartHeight + 15} L ${coords[0].x} ${chartHeight + 15} Z`;

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
            <path d="${linePath}" fill="none" stroke="#0f766e" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
            ${coords.map(c => `
              <circle cx="${c.x}" cy="${c.y}" r="4.5" fill="#0f766e" stroke="#ffffff" stroke-width="2"><title>${c.val} report(s)</title></circle>
              <text x="${c.x}" y="142" font-size="10" font-weight="500" fill="#94a3b8" text-anchor="middle">${esc(c.day)}</text>
            `).join('')}
          </svg>
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
      </div>
    </div>
  `;
  stage.innerHTML = shell(main, 'dashboard');
}

// ---------------------------------------------------------------------
// ACCOUNT APPROVALS
// ---------------------------------------------------------------------
async function renderApprovals() {
  const user = getActiveUser();
  let approvals = [];

  try {
    approvals = await DataService.getApprovals(user.psgcCode);
  } catch (err) {
    console.error('Error fetching approvals:', err);
  }

  const trs = approvals.map(r => `
    <tr class="row-link" data-approval-id="${esc(r.id)}">
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--teal-100);color:var(--teal-800);">${ic('user')}</div>
          <div>
            <div class="t">${esc(r.name)}</div>
            <div class="s">${esc(r.address)}</div>
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
const DOC_STATUSES = ['pending', 'in_progress', 'ready_for_pickup', 'resolved', 'rejected'];

async function renderDocuments() {
  const user = getActiveUser();
  let docs = [];

  try {
    docs = await DataService.getDocuments(user.psgcCode);
  } catch (err) {
    console.error('Error fetching documents:', err);
  }

  const trs = docs.map(d => {
    const rawStatus = String(d.status || 'pending').toLowerCase();
    const id = esc(d.id);
    const options = [...new Set([...DOC_STATUSES, rawStatus])].map(s =>
      `<option value="${esc(s)}" ${rawStatus === s ? 'selected' : ''}>${esc(statusLabel(s))}</option>`
    ).join('');

    return `
      <tr data-doc-row="${id}">
        <td>
          <div class="name-cell">
            <div class="ic" style="background:var(--gold-100);color:var(--gold-600);">${ic('doc')}</div>
            <div>
              <div class="t">${esc(d.name)}</div>
              <div class="s">Requested: ${esc(d.type)}</div>
            </div>
          </div>
        </td>
        <td>
          <select class="field doc-status-select" data-doc-id="${id}" style="width: auto; padding: 4px 8px; font-size: 12px; height: 32px;" ${rawStatus === 'cancelled' ? 'disabled' : ''}>
            ${options}
          </select>
        </td>
        <td>
          <input type="date" class="field doc-date-picker" data-doc-id="${id}" value="${esc(d.pickup)}" style="width: 145px; padding: 4px 8px; font-size: 12px; height: 32px;">
        </td>
        <td>
          <span class="save-indicator text-xs" data-indicator-id="${id}" style="color: var(--teal-800); font-size: 11px;">${rawStatus === 'cancelled' ? 'Cancelled by resident' : 'Saved'}</span>
        </td>
      </tr>
    `;
  }).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Documents &amp; IDs</h3>
        <p>Manage resident barangay clearances, certifications, and permit endorsements.</p>
      </div>
    </div>
    <table class="table">
      <thead><tr><th>Resident &amp; Document</th><th>Status</th><th>Pickup Date</th><th></th></tr></thead>
      <tbody>${trs || '<tr><td colspan="4" style="color:var(--muted);padding:16px 10px;">No document requests found.</td></tr>'}</tbody>
    </table>
  `;
  stage.innerHTML = shell(main, 'documents');

  const saveRow = async (id) => {
    const select = document.querySelector(`.doc-status-select[data-doc-id="${CSS.escape(id)}"]`);
    const dateInput = document.querySelector(`.doc-date-picker[data-doc-id="${CSS.escape(id)}"]`);
    const indicator = document.querySelector(`[data-indicator-id="${CSS.escape(id)}"]`);
    indicator.textContent = 'Saving...';
    indicator.style.color = 'var(--teal-800)';
    try {
      await DataService.updateDocument(id, {
        status: select.value,
        pickup_date: dateInput.value || null
      });
      indicator.textContent = 'Saved';
    } catch (err) {
      console.error(err);
      indicator.textContent = err?.message ? `Error: ${err.message}` : 'Error saving';
      indicator.style.color = 'var(--brick)';
    }
  };

  document.querySelectorAll('.doc-status-select').forEach(select => {
    select.onchange = () => saveRow(select.dataset.docId);
  });
  document.querySelectorAll('.doc-date-picker').forEach(input => {
    input.onchange = () => saveRow(input.dataset.docId);
  });
}

// ---------------------------------------------------------------------
// REPORT QUEUE (public.reports) + overview map
// ---------------------------------------------------------------------
async function renderQueue() {
  const user = getActiveUser();
  let reports = [];

  try {
    reports = await DataService.getReports(user.psgcCode);
  } catch (err) {
    console.error('Error fetching reports from Supabase:', err);
  }

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
    <div class="filters">
      <select id="queue-cat-filter">
        <option value="">All types</option>
        ${categories.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('')}
      </select>
      <select id="queue-stat-filter">
        <option value="">All statuses</option>
        <option value="pending">Pending</option>
        <option value="in_progress">In progress</option>
        <option value="resolved">Resolved</option>
        <option value="rejected">Rejected</option>
        <option value="cancelled">Cancelled</option>
      </select>
      <input id="queue-search" placeholder="Search reports...">
    </div>
    <table class="table">
      <thead><tr><th>Report</th><th>Priority</th><th>Status</th><th></th></tr></thead>
      <tbody id="queue-body"></tbody>
    </table>
  `;
  stage.innerHTML = shell(main, 'queue');

  const renderRows = () => {
    const cat = document.getElementById('queue-cat-filter').value;
    const stat = document.getElementById('queue-stat-filter').value;
    const search = document.getElementById('queue-search').value.toLowerCase();

    const list = reports.filter(q => {
      const matchesCat = !cat || q.category === cat;
      const matchesStat = !stat || String(q.status || '').toLowerCase() === stat;
      const matchesSearch = !search ||
        String(q.title || '').toLowerCase().includes(search) ||
        String(q.description || '').toLowerCase().includes(search);
      return matchesCat && matchesStat && matchesSearch;
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
                <div class="s">${esc(r.category || 'Report')} · ${r.created_at ? esc(new Date(r.created_at).toLocaleDateString()) : 'Recent'}${hasPin ? ' · 📍 pinned' : ''}${r.ai_severity_score ? ` · AI Severity: ${Number(r.ai_severity_score)}/100` : ''}</div>
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
  document.getElementById('queue-search').oninput = renderRows;
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
  try {
    report = await DataService.getReportById(id);
  } catch (err) {
    console.error('Error fetching report details:', err);
  }

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
  return `
    <div class="modal-form-group">
      <label>Title</label>
      <input class="field" id="${prefix}-title" value="${esc(ann.title || '')}" placeholder="e.g. Free Rabies Vaccination" required>
    </div>
    <div class="modal-form-group">
      <label>Description</label>
      <textarea class="field" id="${prefix}-desc" placeholder="Details and instructions..." style="height:80px;resize:vertical;" required>${esc(ann.description || '')}</textarea>
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
  `;
}

function readAnnouncementForm(prefix) {
  return {
    title: document.getElementById(`${prefix}-title`).value.trim(),
    description: document.getElementById(`${prefix}-desc`).value.trim(),
    eventDate: document.getElementById(`${prefix}-date`).value,
    category: document.getElementById(`${prefix}-cat`).value
  };
}

async function renderAnnouncements() {
  const user = getActiveUser();
  let announcements = [];

  try {
    announcements = await DataService.getAnnouncements(user.psgcCode);
  } catch (err) {
    console.error('Error fetching announcements:', err);
  }

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

  const activeItems = activeList.map(a => `
    <div class="ann-item" style="margin-bottom:12px;">
      <div class="top"><span class="tag" style="color:var(--teal-800);background:var(--teal-100);">${esc(a.tag)}</span></div>
      <p class="title" style="margin:6px 0 2px 0;">${esc(a.title)}</p>
      ${a.description ? `<p style="font-size:12px; color:var(--muted); margin:0 0 6px 0; white-space:pre-line;">${esc(a.description)}</p>` : ''}
      <p class="meta">
        ${a.event_date ? `Event: ${esc(a.event_date)} · ` : ''}Posted ${esc(a.posted)} ·
        <a href="#" style="color:var(--teal-800);text-decoration:none;font-weight:600;margin-right:8px;" data-edit-ann="${esc(a.id)}">Edit</a>
        <a href="#" style="color:var(--brick);text-decoration:none;font-weight:600;" data-archive-ann="${esc(a.id)}">Archive</a>
      </p>
    </div>
  `).join('');

  const pastItems = pastList.map(a => `
    <div class="ann-item" style="margin-bottom:10px; opacity:0.85; background:#fbfbfa;">
      <div class="top"><span class="tag" style="color:#64748b;background:#f1f5f9;">${esc(a.tag || 'Archived')}</span></div>
      <p class="title" style="margin:4px 0 2px 0; font-size:13.5px;">${esc(a.title)}</p>
      ${a.description ? `<p style="font-size:11.5px; color:var(--muted); margin:0 0 4px 0; white-space:pre-line;">${esc(a.description)}</p>` : ''}
      <p class="meta">
        ${a.event_date ? `Event: ${esc(a.event_date)} · ` : ''}${esc(a.posted || 'Past')} ·
        <a href="#" style="color:var(--teal-800);text-decoration:none;font-weight:600;margin-right:8px;" data-repost-ann="${esc(a.id)}">Repost</a>
        <a href="#" style="color:var(--charcoal);text-decoration:none;font-weight:600;" data-edit-ann="${esc(a.id)}">Edit</a>
      </p>
    </div>
  `).join('');

  const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
  const firstDayOfWeek = new Date(viewYear, viewMonth, 1).getDay();
  const emptyLeadingDays = [...Array(firstDayOfWeek)].map(() => `<div></div>`).join('');

  const dayCells = [...Array(daysInMonth)].map((_, i) => {
    const dayNum = i + 1;
    const isToday = isCurrentMonthView && (dayNum === realToday.getDate());
    const hasEvent = eventDays.has(dayNum);
    return `
      <div class="d ${isToday ? 'today' : ''}" style="position: relative; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 32px;">
        <span>${dayNum}</span>
        ${hasEvent ? `<span style="width:5px;height:5px;background-color:${isToday ? '#ffffff' : 'var(--teal-800, #0f766e)'};border-radius:50%;position:absolute;bottom:2px;"></span>` : ''}
      </div>
    `;
  }).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Announcements &amp; calendar</h3>
        <p>Bulletins published to residents of ${esc(user.barangayName)} on the mobile app.</p>
      </div>
      <button class="btn-small" id="btn-new-ann">+ New announcement</button>
    </div>

    <div class="ann-layout" style="display:grid; grid-template-columns: 1.4fr 1fr; gap:20px; align-items:flex-start;">
      <div>
        <h4 style="font-size:13px; color:var(--muted); margin-bottom:10px;">Active Announcements</h4>
        <div style="margin-bottom:24px;">
          ${activeItems || '<p style="color:var(--muted);font-size:12px;">No active announcements published.</p>'}
        </div>

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

  document.getElementById('btn-new-ann').onclick = () => {
    openCustomModal({
      title: 'Publish announcement',
      contentHtml: announcementFormHtml('ann'),
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
  `;
}

async function renderEmergency() {
  const user = getActiveUser();
  let contacts = [];

  try {
    contacts = await DataService.getEmergencyContacts(user.psgcCode);
  } catch (err) {
    console.error('Error fetching emergency contacts:', err);
  }

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
        <p>Public hotlines broadcasted to the citizen app.</p>
      </div>
      <button class="btn-small" id="btn-add-em">+ Add contact</button>
    </div>
    <table class="table">
      <thead><tr><th>Name</th><th>Category</th><th>Number</th><th></th></tr></thead>
      <tbody>${trs || '<tr><td colspan="4" style="color:var(--muted);padding:16px 10px;">No contacts registered.</td></tr>'}</tbody>
    </table>
  `;
  stage.innerHTML = shell(main, 'emergency');

  document.getElementById('btn-add-em').onclick = () => {
    openCustomModal({
      title: 'Add emergency contact',
      contentHtml: contactFormHtml('em'),
      confirmText: 'Save Contact',
      onConfirm: async () => {
        await DataService.createEmergencyContact({
          name: document.getElementById('em-name').value.trim(),
          category: document.getElementById('em-cat').value,
          num: document.getElementById('em-num').value.trim(),
          psgcCode: user.psgcCode
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
        onConfirm: async () => {
          await DataService.updateEmergencyContact(contact.id, {
            name: document.getElementById('em-edit-name').value.trim(),
            category: document.getElementById('em-edit-cat').value,
            num: document.getElementById('em-edit-num').value.trim()
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
// START: only trust the saved portal user if Supabase still has a session
// ---------------------------------------------------------------------
(async () => {
  const saved = getActiveUser();
  if (saved && saved.psgcCode && await DataService.hasSession()) {
    showScreen('dashboard');
  } else {
    setActiveUser(null);
    showScreen('login');
  }
})();
