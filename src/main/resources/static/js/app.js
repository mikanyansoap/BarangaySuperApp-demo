import { DB, saveDB, auth, signInWithEmailAndPassword, signOut, onAuthStateChanged } from './db.js';
import { fetchReports, updateReportStatus, fetchDocumentRequests, updateDocumentStatus } from './api.js';

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
  logout: '<path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>'
};

function ic(name) {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${I[name]}</svg>`;
}

/* =========================================================================
   Data Service (Spring Boot Backend API with DB.js Mock Fallback)
   ========================================================================= */
const DataService = {
  async getReports() {
    try {
      const live = await fetchReports();
      if (Array.isArray(live) && live.length > 0) {
        return live.map(r => ({
          id: r.id,
          title: r.title,
          meta: `${r.reporterName || 'Citizen'} · ${r.createdAt ? new Date(r.createdAt).toLocaleDateString() : 'Recent'}`,
          priority: (r.status === 'PENDING' ? 'high' : 'medium'),
          status: (r.status || 'pending').toLowerCase().replace('_', ''),
          category: r.category || 'General',
          desc: r.description || 'No additional description provided.',
          bg: r.status === 'PENDING' ? 'brick-100' : 'gold-100',
          fg: r.status === 'PENDING' ? 'brick' : 'gold-600',
          notes: r.adminNotes || ''
        }));
      }
    } catch (_) {}
    return DB.reports;
  },

  async updateReportTriage(id, { status, notes }) {
    try {
      const backendStatus = status === 'progress' ? 'IN_PROGRESS' : status === 'resolved' ? 'RESOLVED' : 'PENDING';
      await updateReportStatus(id, backendStatus, notes);
    } catch (_) {}
    const r = DB.reports.find(q => q.id === id);
    if (r) {
      r.status = status;
      r.notes = notes;
      saveDB();
    }
  },

  async getDocuments() {
    try {
      const live = await fetchDocumentRequests();
      if (Array.isArray(live) && live.length > 0) {
        return live.map(d => ({
          id: d.id,
          type: d.documentType,
          name: d.requesterName || 'Requester',
          date: d.requestedAt ? new Date(d.requestedAt).toLocaleDateString() : 'Recent',
          status: (d.status || 'pending').toLowerCase(),
          pickup: d.pickupDate || null
        }));
      }
    } catch (_) {}
    return DB.documents;
  },

  async updateDocStatus(id, status, pickup = null, remarks = '') {
    try {
      await updateDocumentStatus(id, status.toUpperCase(), pickup, remarks);
    } catch (_) {}
    const doc = DB.documents.find(d => d.id === id);
    if (doc) {
      doc.status = status;
      if (pickup) doc.pickup = pickup;
      saveDB();
    }
  },

  async getApprovals() {
    return DB.approvals;
  },

  async getAnnouncements() {
    return {
      active: DB.announcements,
      past: DB.pastAnnouncements
    };
  }
};

/* =========================================================================
   Shell & Modal Infrastructure
   ========================================================================= */
const stage = document.getElementById('app-stage');
const modalRoot = document.getElementById('modal-root');

function closeModal() {
  if (modalRoot) modalRoot.innerHTML = '';
}
window.closeModal = closeModal;

function openCustomModal({ title, contentHtml, onConfirm, confirmText = 'Save', confirmClass = 'btn-small' }) {
  modalRoot.innerHTML = `
    <div class="modal-overlay">
      <div class="modal-box" style="width: 440px;">
        <div class="modal-head">
          <h4>${title}</h4>
          <button class="modal-close" id="modal-close-btn">✕</button>
        </div>
        <form id="custom-modal-form">
          <div style="margin: 14px 0;">
            ${contentHtml}
          </div>
          <div class="modal-btn-row">
            <button type="button" class="btn-small ghost" id="modal-cancel-btn">Cancel</button>
            <button type="submit" class="${confirmClass}">${confirmText}</button>
          </div>
        </form>
      </div>
    </div>
  `;

  document.getElementById('modal-close-btn').onclick = closeModal;
  document.getElementById('modal-cancel-btn').onclick = closeModal;
  document.getElementById('custom-modal-form').onsubmit = (e) => {
    e.preventDefault();
    if (onConfirm) onConfirm();
  };
}

function shell(mainHtml, active) {
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
      ${ic(icon)} ${label}
    </div>
  `).join('');

  return `
    <div class="shell">
      <div class="sidebar">
        <p class="brgy-name">Barangay San Isidro</p>
        <p class="brgy-sub">Official portal</p>
        <div class="sb-nav">${nav}</div>
        <div class="sb-foot">
          <div class="sb-avatar">${ic('user')}</div>
          <div style="flex:1; min-width:0;">
            <p class="who" style="white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" id="active-user-name">Admin Staff</p>
            <p class="role">Barangay staff</p>
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

// Universal click delegation for modals, nav, and logout
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
      contentHtml: '<p style="font-size:13px; color:var(--muted); margin:0;">Are you sure you want to end your official session?</p>',
      confirmText: 'Sign out',
      confirmClass: 'btn-small ghost',
      onConfirm: async () => {
        closeModal();
        userExplicitlyLoggedIn = false;
        await signOut(auth);
        showScreen('login');
      }
    });
    return;
  }
});

/* =========================================================================
   Screen Implementations
   ========================================================================= */

// Screen 1: Sign in
function renderLogin() {
  stage.innerHTML = `
    <div class="login-wrap">
      <form class="login-card" id="login-form">
        <div class="login-mark">${ic('building')}</div>
        <h2>Barangay official sign in</h2>
        <p class="sub">Sign in with the account your barangay's system administrator created for you.</p>
        <label class="field-label">Email</label>
        <input class="field" type="email" id="login-email-input" required placeholder="admin@barangay.gov.ph" value="admin@test.com">
        <label class="field-label">Password</label>
        <input class="field" type="password" id="login-pass-input" required placeholder="••••••••">
        <button class="btn-primary" type="submit" id="login-btn">Sign in</button>
        <p id="login-err-msg" style="color:var(--brick); font-size:12px; margin-top:10px; text-align:center;"></p>
        <p class="helper">Authorized personnel only.</p>
      </form>
    </div>
  `;

  document.getElementById('login-form').onsubmit = async (e) => {
    e.preventDefault();
    const email = document.getElementById('login-email-input').value;
    const pass = document.getElementById('login-pass-input').value;
    const btn = document.getElementById('login-btn');
    const err = document.getElementById('login-err-msg');

    btn.disabled = true;
    btn.innerText = 'Signing in...';
    err.innerText = '';

    try {
      userExplicitlyLoggedIn = true;
      await signInWithEmailAndPassword(auth, email, pass);
      showScreen('dashboard');
    } catch (error) {
      userExplicitlyLoggedIn = false;
      err.innerText = error.message;
      btn.disabled = false;
      btn.innerText = 'Sign in';
    }
  };
}

// Screen 2: Dashboard
async function renderDashboard() {
  const reports = await DataService.getReports();
  const docs = await DataService.getDocuments();
  const annData = await DataService.getAnnouncements();

  const newReports = reports.filter(q => q.status === 'pending').length;
  const pendingDocs = docs.filter(d => d.status === 'pending').length;
  const resolved = reports.filter(q => q.status === 'resolved').length + docs.filter(d => d.status === 'resolved').length;
  const activeAnn = (annData.active || []).length;

  const dateInfo = getCurrentDate();

  const main = `
    <div class="main-head">
      <div>
        <h3>Dashboard</h3>
        <p>${dateInfo.weekday}, ${dateInfo.month} ${dateInfo.day}, ${dateInfo.year} · Barangay San Isidro</p>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="top"><div class="ic" style="background:var(--brick-100);color:var(--brick);">${ic('queue')}</div></div>
        <p class="num">${newReports}</p>
        <p class="lbl">New reports today</p>
      </div>
      <div class="stat-card">
        <div class="top"><div class="ic" style="background:var(--gold-100);color:var(--gold-600);">${ic('doc')}</div></div>
        <p class="num">${pendingDocs}</p>
        <p class="lbl">Pending document requests</p>
      </div>
      <div class="stat-card">
        <div class="top"><div class="ic" style="background:var(--sage-100);color:#3E6552;">${ic('doc')}</div></div>
        <p class="num">${resolved}</p>
        <p class="lbl">Resolved this week</p>
      </div>
      <div class="stat-card">
        <div class="top"><div class="ic" style="background:var(--teal-100);color:var(--teal-800);">${ic('megaphone')}</div></div>
        <p class="num">${activeAnn}</p>
        <p class="lbl">Active announcements</p>
      </div>
    </div>

    <div class="chart-row">
      <div class="panel">
        <h4>Reports by category, last 30 days</h4>
        <div class="bars">
          <div class="b" style="height:45%;background:var(--brick);"><span>Disturbance</span></div>
          <div class="b" style="height:90%;background:var(--gold);"><span>Drainage</span></div>
          <div class="b" style="height:60%;background:var(--teal-700);"><span>Sanitation</span></div>
          <div class="b" style="height:30%;background:var(--sage);"><span>Delinquency</span></div>
          <div class="b" style="height:75%;background:var(--teal-900);"><span>Other</span></div>
        </div>
      </div>
      <div class="panel">
        <h4>Status breakdown</h4>
        <div class="donut-row">
          <svg width="88" height="88" viewBox="0 0 36 36">
            <circle cx="18" cy="18" r="15.5" fill="none" stroke="#E7E9E1" stroke-width="4"/>
            <circle cx="18" cy="18" r="15.5" fill="none" stroke="#5C8A72" stroke-width="4" stroke-dasharray="58 97" stroke-dashoffset="0" transform="rotate(-90 18 18)"/>
            <circle cx="18" cy="18" r="15.5" fill="none" stroke="#E0A62E" stroke-width="4" stroke-dasharray="24 97" stroke-dashoffset="-58" transform="rotate(-90 18 18)"/>
            <circle cx="18" cy="18" r="15.5" fill="none" stroke="#C1483A" stroke-width="4" stroke-dasharray="15 97" stroke-dashoffset="-82" transform="rotate(-90 18 18)"/>
          </svg>
          <div class="legend">
            <div class="li"><span class="sw" style="background:#5C8A72;"></span>Resolved · 60%</div>
            <div class="li"><span class="sw" style="background:#E0A62E;"></span>In progress · 25%</div>
            <div class="li"><span class="sw" style="background:#C1483A;"></span>Pending · 15%</div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <h4>Reports over time</h4>
      <svg viewBox="0 0 500 60" style="width:100%; height:80px; stroke:var(--teal-900); fill:none; stroke-width:3;">
        <path d="M 0 50 Q 80 40, 150 48 T 300 35 T 420 20 T 500 30" />
      </svg>
    </div>
  `;
  stage.innerHTML = shell(main, 'dashboard');
  syncUserLabel();
}

// Screen 3: Account Approvals
async function renderApprovals() {
  const approvals = await DataService.getApprovals();

  const trs = approvals.map(r => `
    <tr class="row-link" data-approval-id="${r.id}">
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--teal-100);color:var(--teal-800);">${ic('user')}</div>
          <div><div class="t">${r.name}</div><div class="s">${r.address}</div></div>
        </div>
      </td>
      <td>
        <span style="font-size:9px;font-weight:700;background:var(--teal-100);color:var(--teal-800);padding:2px 7px;border-radius:999px;">AI-scanned</span>
        <div style="margin-top:3px;">${r.idType} · ${r.idNumber}</div>
      </td>
      <td>${r.date}</td>
      <td style="text-align:right;color:var(--muted);">${ic('chevron')}</td>
    </tr>
  `).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Account approvals</h3>
        <p>New resident registrations. Tap a row to review the AI-scanned ID and approve or reject.</p>
      </div>
    </div>
    <div class="table-responsive">
      <table class="table">
        <thead><tr><th>Resident</th><th>ID submitted</th><th>Registered</th><th></th></tr></thead>
        <tbody>${trs || '<tr><td colspan="4" style="color:var(--muted);padding:16px 10px;">No pending resident accounts.</td></tr>'}</tbody>
      </table>
    </div>
  `;
  stage.innerHTML = shell(main, 'approvals');
  syncUserLabel();

  document.querySelectorAll('[data-approval-id]').forEach(row => {
    row.onclick = () => openApprovalModal(Number(row.dataset.approvalId));
  });
}

function openApprovalModal(id) {
  const r = DB.approvals.find(item => item.id === id);
  if (!r) return;

  modalRoot.innerHTML = `
    <div class="modal-overlay">
      <div class="modal-box">
        <div class="modal-head">
          <div>
            <h4>${r.name}</h4>
            <span style="font-size:9px;font-weight:700;background:var(--teal-100);color:var(--teal-800);padding:2px 7px;border-radius:999px;">AI-scanned</span>
          </div>
          <button class="modal-close" id="approval-modal-close">✕</button>
        </div>
        <div class="modal-field"><span class="modal-label">Address</span><span class="modal-value">${r.address}</span></div>
        <div class="modal-field"><span class="modal-label">ID type</span><span class="modal-value">${r.idType}</span></div>
        <div class="modal-field"><span class="modal-label">ID number</span><span class="modal-value">${r.idNumber}</span></div>
        <div class="modal-field"><span class="modal-label">Registered</span><span class="modal-value">${r.date}</span></div>
        <div class="evidence-thumb" style="margin-top:12px;">[ AI-Verified Government ID Scan Attached ]</div>
        <div class="modal-actions">
          <button class="btn-small" id="btn-approve">Approve account</button>
          <button class="btn-small ghost" style="color:var(--brick);border-color:var(--brick);" id="btn-reject">Reject</button>
        </div>
        <p class="modal-warning">Confirm that ID details match resident records before activation.</p>
      </div>
    </div>
  `;

  document.getElementById('approval-modal-close').onclick = closeModal;

  document.getElementById('btn-approve').onclick = () => {
    DB.approvals = DB.approvals.filter(a => a.id !== id);
    saveDB();
    closeModal();
    renderApprovals();
  };

  document.getElementById('btn-reject').onclick = () => {
    DB.approvals = DB.approvals.filter(a => a.id !== id);
    saveDB();
    closeModal();
    renderApprovals();
  };
}

// Screen 4: Report Queue
async function renderQueue() {
  const reports = await DataService.getReports();

  const cat = document.getElementById('queue-cat-filter')?.value || '';
  const stat = document.getElementById('queue-stat-filter')?.value || '';
  const search = (document.getElementById('queue-search')?.value || '').toLowerCase();

  const list = reports.filter(q => {
    const matchesCat = !cat || q.category === cat;
    const matchesStat = !stat || q.status === stat;
    const matchesSearch = !search ||
      (q.title && q.title.toLowerCase().includes(search)) ||
      (q.meta && q.meta.toLowerCase().includes(search));
    return matchesCat && matchesStat && matchesSearch;
  });

  const trs = list.map(r => `
    <tr>
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--${r.bg});color:${r.fg.startsWith('#') ? r.fg : 'var(--' + r.fg + ')'};">${ic('queue')}</div>
          <div>
            <div class="t">${r.title}</div>
            <div class="s">${r.meta}</div>
          </div>
        </div>
      </td>
      <td><span class="pill ${r.priority}">${r.priority} priority</span></td>
      <td><span class="pill ${r.status}">${r.status === 'pending' ? 'Pending' : r.status === 'progress' ? 'In progress' : 'Resolved'}</span></td>
      <td>
        <div class="table-actions">
          <button class="link-btn" data-view-report="${r.id}">View</button>
        </div>
      </td>
    </tr>
  `).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Report &amp; request queue</h3>
        <p>Sorted by AI-assessed priority and severity.</p>
      </div>
    </div>
    <div class="filters">
      <select id="queue-cat-filter">
        <option value="">All categories</option>
        <option value="Disturbance" ${cat === 'Disturbance' ? 'selected' : ''}>Disturbance</option>
        <option value="Drainage" ${cat === 'Drainage' ? 'selected' : ''}>Drainage</option>
        <option value="Sanitation" ${cat === 'Sanitation' ? 'selected' : ''}>Sanitation</option>
        <option value="Other" ${cat === 'Other' ? 'selected' : ''}>Other</option>
      </select>
      <select id="queue-stat-filter">
        <option value="">All statuses</option>
        <option value="pending" ${stat === 'pending' ? 'selected' : ''}>Pending</option>
        <option value="progress" ${stat === 'progress' ? 'selected' : ''}>In progress</option>
        <option value="resolved" ${stat === 'resolved' ? 'selected' : ''}>Resolved</option>
      </select>
      <input id="queue-search" placeholder="Search reports..." value="${search}">
    </div>
    <div class="table-responsive">
      <table class="table">
        <thead><tr><th>Report</th><th>Priority</th><th>Status</th><th></th></tr></thead>
        <tbody>${trs || '<tr><td colspan="4" style="color:var(--muted);padding:16px 10px;">No reports match your filters.</td></tr>'}</tbody>
      </table>
    </div>
  `;
  stage.innerHTML = shell(main, 'queue');
  syncUserLabel();

  document.getElementById('queue-cat-filter').onchange = renderQueue;
  document.getElementById('queue-stat-filter').onchange = renderQueue;
  document.getElementById('queue-search').oninput = renderQueue;
  document.querySelectorAll('[data-view-report]').forEach(btn => {
    btn.onclick = () => showScreen('detail', Number(btn.dataset.viewReport));
  });
}

// Screen 5: Detail Screen
async function renderDetail(id) {
  const reports = await DataService.getReports();
  const r = reports.find(q => q.id === id);
  if (!r) return showScreen('queue');

  const main = `
    <div class="main-head">
      <div>
        <div style="display:flex;align-items:center;gap:10px;">
          <h3>${r.title}</h3>
          <span class="pill ${r.priority}">${r.priority} priority</span>
        </div>
        <p>${r.meta}</p>
      </div>
      <button class="btn-small ghost" id="btn-back-queue">Back to queue</button>
    </div>
    <div style="display:grid;grid-template-columns:1.4fr 1fr;gap:16px;">
      <div>
        <div class="panel">
          <h4>Description</h4>
          <p style="font-size:12.5px;line-height:1.6;margin:0;">${r.desc}</p>
          <div class="evidence-thumb" style="margin-top:14px;">Attached Photo Evidence</div>
        </div>
      </div>
      <div>
        <div class="panel">
          <h4>Status &amp; Triage</h4>
          <label class="field-label">Internal staff notes</label>
          <textarea class="field" id="report-notes" style="height:76px;resize:none;">${r.notes || ''}</textarea>
          <div style="display:flex;gap:8px;margin-top:14px;flex-wrap:wrap;">
            <button class="btn-small" id="btn-mark-progress">Mark in progress</button>
            <button class="btn-small ghost" style="color:var(--sage);" id="btn-mark-resolved">Resolve</button>
          </div>
        </div>
      </div>
    </div>
  `;
  stage.innerHTML = shell(main, 'queue');
  syncUserLabel();

  document.getElementById('btn-back-queue').onclick = () => showScreen('queue');
  document.getElementById('btn-mark-progress').onclick = () => updateReportTriage(id, 'progress');
  document.getElementById('btn-mark-resolved').onclick = () => updateReportTriage(id, 'resolved');
}

async function updateReportTriage(id, newStatus) {
  const notes = document.getElementById('report-notes').value;
  await DataService.updateReportTriage(id, { status: newStatus, notes });
  showScreen('queue');
}

// Screen 6: Documents & IDs
async function renderDocuments() {
  const documents = await DataService.getDocuments();

  const trs = documents.map(r => `
    <tr>
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--teal-100);color:var(--teal-800);">${ic('doc')}</div>
          <div>
            <div class="t">${r.type}</div>
            <div class="s">${r.name} · requested ${r.date} ${r.pickup ? '· Pickup: ' + r.pickup : ''}</div>
          </div>
        </div>
      </td>
      <td><span class="pill ${r.status}">${r.status === 'pending' ? 'Pending' : r.status === 'progress' ? 'Preparing' : 'Ready for pickup'}</span></td>
      <td>
        <div class="table-actions">
          <button class="link-btn" data-msg-doc="${r.id}">Message</button>
          <button class="link-btn" data-pickup-doc="${r.id}">Set pickup date</button>
          ${r.status !== 'resolved' ? `<button class="link-btn" data-ready-doc="${r.id}">Mark ready</button>` : ''}
        </div>
      </td>
    </tr>
  `).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Document &amp; ID requests</h3>
        <p>Coordinate certifications and pick-up appointments.</p>
      </div>
    </div>
    <div class="table-responsive">
      <table class="table">
        <thead><tr><th>Request</th><th>Status</th><th></th></tr></thead>
        <tbody>${trs}</tbody>
      </table>
    </div>
  `;
  stage.innerHTML = shell(main, 'documents');
  syncUserLabel();

  document.querySelectorAll('[data-msg-doc]').forEach(b => {
    b.onclick = () => {
      const doc = documents.find(d => d.id === Number(b.dataset.msgDoc));
      openCustomModal({
        title: `Message ${doc.name}`,
        contentHtml: `
          <div class="modal-form-group">
            <label>SMS / In-App Notification</label>
            <textarea class="field" id="doc-msg-text" placeholder="Type notification update regarding ${doc.type}..." required></textarea>
          </div>
        `,
        confirmText: 'Send Notification',
        onConfirm: () => {
          closeModal();
          openCustomModal({
            title: 'Message Dispatched',
            contentHtml: `<p style="font-size:12.5px;color:var(--muted);margin:0;">Notice dispatched to <b>${doc.name}</b> successfully.</p>`,
            confirmText: 'Done',
            onConfirm: closeModal
          });
        }
      });
    };
  });

  document.querySelectorAll('[data-pickup-doc]').forEach(b => {
    b.onclick = () => {
      const doc = documents.find(d => d.id === Number(b.dataset.pickupDoc));
      openCustomModal({
        title: 'Schedule document pickup',
        contentHtml: `
          <div class="modal-form-group">
            <label>Select pickup date for ${doc.name}</label>
            <input class="field" type="date" id="doc-pickup-input" required value="2026-09-18">
          </div>
        `,
        confirmText: 'Confirm date',
        onConfirm: async () => {
          const dateVal = document.getElementById('doc-pickup-input').value;
          await DataService.updateDocStatus(doc.id, 'progress', dateVal, 'Pickup date set.');
          closeModal();
          renderDocuments();
        }
      });
    };
  });

  document.querySelectorAll('[data-ready-doc]').forEach(b => {
    b.onclick = async () => {
      const docId = Number(b.dataset.readyDoc);
      await DataService.updateDocStatus(docId, 'resolved');
      renderDocuments();
    };
  });
}

// Screen 7: Announcements & Calendar
async function renderAnnouncements() {
  const annData = await DataService.getAnnouncements();
  const activeAnnouncements = annData.active || [];
  const pastAnnouncements = annData.past || [];
  const eventDays = activeAnnouncements.map(a => a.day).filter(Boolean);

  const dateInfo = getCurrentDate();

  const cal = [...Array(35)].map((_, i) => {
    const day = i - 1;
    if (day < 1 || day > 30) return `<div class="d muted">${((day + 30 - 1) % 30) + 1}</div>`;
    const isToday = day === dateInfo.day;
    const isEvent = eventDays.includes(day);
    return `<div class="d ${isToday ? 'today' : ''} ${isEvent ? 'event' : ''}">${day}</div>`;
  }).join('');

  const activeItems = activeAnnouncements.map(a => {
    const tagStyle = a.tag === 'Advisory'
      ? 'color:var(--brick);background:var(--brick-100);'
      : a.tag === 'Event'
      ? 'color:var(--gold-600);background:var(--gold-100);'
      : 'color:var(--teal-800);background:var(--teal-100);';

    return `
      <div class="ann-item">
        <div class="top"><span class="tag" style="${tagStyle}">${a.tag}</span></div>
        <p class="title">${a.title}</p>
        <p class="meta">Posted ${a.posted} · <a href="#" style="color:var(--brick);text-decoration:none;font-weight:600;" data-delete-ann="${a.id}">Delete</a></p>
      </div>
    `;
  }).join('');

  const pastRows = pastAnnouncements.map(p => `
    <tr>
      <td><strong>${p.title}</strong></td>
      <td>${p.tag}</td>
      <td>${p.posted}</td>
      <td style="text-align:right;"><button class="link-btn" data-repost-ann="${p.id}">Repost</button></td>
    </tr>
  `).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Announcements &amp; calendar</h3>
        <p>What citizens see on their dashboard.</p>
      </div>
      <button class="btn-small" id="btn-new-ann">+ New announcement</button>
    </div>

    <div class="ann-layout">
      <div>${activeItems || '<p style="color:var(--muted);font-size:12px;">No active announcements.</p>'}</div>
      <div class="panel">
        <h4>${dateInfo.month} ${dateInfo.year}</h4>
        <div class="cal">
          <div class="cal-head">S</div><div class="cal-head">M</div><div class="cal-head">T</div><div class="cal-head">W</div><div class="cal-head">T</div><div class="cal-head">F</div><div class="cal-head">S</div>
          ${cal}
        </div>
      </div>
    </div>

    <div class="panel" style="margin-top:20px;">
      <h4>Past announcements</h4>
      <div class="table-responsive" style="margin-bottom:0;">
        <table class="table">
          <thead>
            <tr><th>Title</th><th>Category</th><th>Posted</th><th></th></tr>
          </thead>
          <tbody>
            ${pastRows || '<tr><td colspan="4" style="color:var(--muted);padding:10px 0;">No past records.</td></tr>'}
          </tbody>
        </table>
      </div>
    </div>
  `;
  stage.innerHTML = shell(main, 'announcements');
  syncUserLabel();

  document.getElementById('btn-new-ann').onclick = openAnnouncementModal;

  document.querySelectorAll('[data-delete-ann]').forEach(a => {
    a.onclick = (e) => {
      e.preventDefault();
      const id = Number(a.dataset.deleteAnn);
      const item = DB.announcements.find(x => x.id === id);
      openCustomModal({
        title: 'Archive announcement',
        contentHtml: `<p style="font-size:13px; color:var(--muted); margin:0;">Move "<b>${item.title}</b>" to past announcements archive?</p>`,
        confirmText: 'Archive',
        confirmClass: 'btn-small ghost',
        onConfirm: () => {
          DB.pastAnnouncements.unshift({ id: item.id, title: item.title, tag: item.tag, posted: item.posted });
          DB.announcements = DB.announcements.filter(x => x.id !== id);
          saveDB();
          closeModal();
          renderAnnouncements();
        }
      });
    };
  });

  document.querySelectorAll('[data-repost-ann]').forEach(b => {
    b.onclick = () => {
      const item = DB.pastAnnouncements.find(p => p.id === Number(b.dataset.repostAnn));
      DB.announcements.unshift({
        id: Date.now(),
        title: item.title,
        tag: item.tag,
        posted: 'Today',
        day: null
      });
      saveDB();
      renderAnnouncements();
    };
  });
}

function openAnnouncementModal() {
  const dateInfo = getCurrentDate();
  const todayVal = `${dateInfo.year}-${String(new Date().getMonth() + 1).padStart(2, '0')}-${String(dateInfo.day).padStart(2, '0')}`;

  openCustomModal({
    title: 'New announcement',
    contentHtml: `
      <div class="modal-form-group">
        <label>Title / Bulletin Headline</label>
        <input class="field" id="ann-title" placeholder="e.g. Free anti-rabies vaccination" required autofocus>
      </div>

      <div class="modal-form-group">
        <label>Category</label>
        <select class="field" id="ann-tag">
          <option value="Health">Health</option>
          <option value="Advisory" selected>Advisory</option>
          <option value="Event">Event</option>
        </select>
      </div>

      <div class="modal-form-group">
        <label>Event Date (Select on Calendar)</label>
        <input class="field" type="date" id="ann-date-picker" value="${todayVal}">
      </div>
    `,
    confirmText: 'Publish bulletin',
    onConfirm: () => {
      const title = document.getElementById('ann-title').value.trim();
      const tag = document.getElementById('ann-tag').value;
      const dateVal = document.getElementById('ann-date-picker').value;
      let day = null;

      if (dateVal) {
        const parts = dateVal.split('-');
        day = parseInt(parts[2], 10);
      }

      DB.announcements.unshift({
        id: Date.now(),
        title,
        tag,
        posted: 'Today',
        day
      });

      saveDB();
      closeModal();
      renderAnnouncements();
    }
  });
}

// Screen 8: Emergency Contacts
async function renderEmergency() {
  const trs = DB.emergency.map(r => `
    <tr>
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--sage-100);color:#3E6552;">${ic('phone')}</div>
          <div class="t">${r.name}</div>
        </div>
      </td>
      <td>${r.cat}</td>
      <td>${r.num}</td>
      <td>
        <div class="table-actions">
          <button class="link-btn" data-edit-em="${r.id}">Edit</button>
          <button class="link-btn danger" data-del-em="${r.id}">Delete</button>
        </div>
      </td>
    </tr>
  `).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Emergency contacts</h3>
        <p>Public hotlines displayed directly on the mobile app.</p>
      </div>
      <button class="btn-small" id="btn-add-em">+ Add contact</button>
    </div>
    <div class="table-responsive">
      <table class="table">
        <thead><tr><th>Name</th><th>Category</th><th>Number</th><th></th></tr></thead>
        <tbody>${trs}</tbody>
      </table>
    </div>
  `;
  stage.innerHTML = shell(main, 'emergency');
  syncUserLabel();

  document.getElementById('btn-add-em').onclick = () => {
    openCustomModal({
      title: 'Add emergency contact',
      contentHtml: `
        <div class="modal-form-group">
          <label>Contact Name / Agency</label>
          <input class="field" id="em-name" placeholder="e.g. MDRRMO Action Center" required>
        </div>
        <div class="modal-form-group">
          <label>Category</label>
          <select class="field" id="em-cat">
            <option value="Barangay">Barangay</option>
            <option value="National">National</option>
          </select>
        </div>
        <div class="modal-form-group">
          <label>Hotline Number</label>
          <input class="field" id="em-num" placeholder="e.g. (02) 8123 4567" required>
        </div>
      `,
      confirmText: 'Save contact',
      onConfirm: () => {
        const name = document.getElementById('em-name').value;
        const cat = document.getElementById('em-cat').value;
        const num = document.getElementById('em-num').value;
        DB.emergency.push({ id: Date.now(), name, cat, num });
        saveDB();
        closeModal();
        renderEmergency();
      }
    });
  };

  document.querySelectorAll('[data-edit-em]').forEach(b => {
    b.onclick = () => {
      const item = DB.emergency.find(e => e.id === Number(b.dataset.editEm));
      openCustomModal({
        title: `Edit ${item.name}`,
        contentHtml: `
          <div class="modal-form-group">
            <label>Hotline Phone Number</label>
            <input class="field" id="em-edit-num" value="${item.num}" required>
          </div>
        `,
        confirmText: 'Update number',
        onConfirm: () => {
          item.num = document.getElementById('em-edit-num').value;
          saveDB();
          closeModal();
          renderEmergency();
        }
      });
    };
  });

  document.querySelectorAll('[data-del-em]').forEach(b => {
    b.onclick = () => {
      const id = Number(b.dataset.delEm);
      const item = DB.emergency.find(e => e.id === id);
      openCustomModal({
        title: 'Delete contact',
        contentHtml: `<p style="font-size:13px; color:var(--muted); margin:0;">Remove <b>${item.name}</b> from the citizen emergency directory?</p>`,
        confirmText: 'Delete',
        confirmClass: 'btn-small ghost',
        onConfirm: () => {
          DB.emergency = DB.emergency.filter(e => e.id !== id);
          saveDB();
          closeModal();
          renderEmergency();
        }
      });
    };
  });
}

function syncUserLabel() {
  const el = document.getElementById('active-user-name');
  if (el && auth.currentUser) {
    el.innerText = auth.currentUser.email.split('@')[0];
  }
}

/* =========================================================================
   Initialization & Auth Gate (Login Always Appears First)
   ========================================================================= */
let userExplicitlyLoggedIn = false;

// Render login page directly upon loading
renderLogin();

onAuthStateChanged(auth, (user) => {
  if (user && userExplicitlyLoggedIn) {
    showScreen('dashboard');
  } else {
    showScreen('login');
  }
});