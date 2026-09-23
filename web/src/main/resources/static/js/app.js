import { DataService } from './db.js';

let calendarViewDate = new Date();

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

const stage = document.getElementById('app-stage');
const modalRoot = document.getElementById('modal-root');

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

function closeModal() {
  if (modalRoot) modalRoot.innerHTML = '';
}
window.closeModal = closeModal;

function openCustomModal({ title, contentHtml, onConfirm, confirmText = 'Save', confirmClass = 'btn-small' }) {
  modalRoot.innerHTML = `
    <div class="modal-overlay">
      <div class="modal-box" style="width: 460px;">
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
  document.getElementById('custom-modal-form').onsubmit = async (e) => {
    e.preventDefault();
    if (onConfirm) await onConfirm();
  };
}

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
      ${ic(icon)} ${label}
    </div>
  `).join('');

  return `
    <div class="shell">
      <div class="sidebar">
        <p class="brgy-name">${user.barangayName}</p>
        <p class="brgy-sub">Official Portal</p>
        <div class="sb-nav">${nav}</div>
        <div class="sb-foot">
          <div class="sb-avatar">${ic('user')}</div>
          <div style="flex:1; min-width:0;">
            <p class="who" style="white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" title="${user.fullName || user.email}">
              ${user.fullName || user.email}
            </p>
            <p class="role">${user.position || user.role || 'Official'}</p>
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
        try {
          await DataService.logout();
        } catch (err) {
          console.warn('Logout error:', err);
        }
        setActiveUser(null);
        showScreen('login');
      }
    });
    return;
  }
});

function renderLogin() {
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


      const profile = await DataService.getUserProfile(authResult.user.id);

      setActiveUser({
        id: authResult.user.id,
        email: authResult.user.email,
        fullName: profile?.full_name || authResult.user.user_metadata?.full_name || email,
        role: profile?.role || 'Barangay Official',
        position: profile?.position || 'Official',
        barangayId: profile?.barangay_id || 1,
        barangayName: profile?.barangays?.name || 'Community Portal'
      });

      showScreen('dashboard');
    } catch (err) {
      errorBox.textContent = err.message || 'Unable to authenticate credentials.';
      errorBox.style.display = 'block';
      submitBtn.disabled = false;
      submitBtn.textContent = 'Sign in';
    }
  };
}

async function renderDashboard() {
  const user = getActiveUser();
  const dateInfo = getCurrentDate();

  let reports = [];
  let docs = [];
  let announcements = [];

  try {
    reports = await DataService.getReports(user.barangayId) || [];
    docs = await DataService.getDocuments(user.barangayId) || [];
    announcements = await DataService.getAnnouncements(user.barangayId) || [];
  } catch (err) {
    console.error('Error fetching dashboard statistics:', err);
  }

  const newReports = reports.filter(q => q.status === 'pending').length;
  const pendingDocs = docs.filter(d => d.status === 'pending').length;
  const resolved = reports.filter(q => q.status === 'resolved').length + docs.filter(d => d.status === 'resolved').length;
  const activeAnn = announcements.filter(a => !a.isArchived).length;

  const trendPoints = [
    { day: 'Mon', val: 2 },
    { day: 'Tue', val: 4 },
    { day: 'Wed', val: 3 },
    { day: 'Thu', val: 6 },
    { day: 'Fri', val: Math.max(2, reports.length) },
    { day: 'Sat', val: 3 },
    { day: 'Sun', val: 5 }
  ];

  const maxVal = 8;
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

  const main = `
    <div class="main-head">
      <div>
        <h3>Dashboard</h3>
        <p>${dateInfo.weekday}, ${dateInfo.month} ${dateInfo.day}, ${dateInfo.year} · ${user.barangayName || 'Barangay Santo Niño'}</p>
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
        <h4>Incidents &amp; Complaints Trend</h4>
        <div style="flex:1; width:100%; margin-top:8px;">
          <svg viewBox="0 0 420 155" style="width:100%; height:auto; overflow:visible;">
            <defs>
              <linearGradient id="lineGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#0f766e" stop-opacity="0.28" />
                <stop offset="100%" stop-color="#0f766e" stop-opacity="0.0" />
              </linearGradient>
            </defs>

            <!-- Background subtle gridlines -->
            <line x1="25" y1="30" x2="410" y2="30" stroke="#f1f5f9" stroke-width="1.5" />
            <line x1="25" y1="75" x2="410" y2="75" stroke="#f1f5f9" stroke-width="1.5" />
            <line x1="25" y1="120" x2="410" y2="120" stroke="#f1f5f9" stroke-width="1.5" />

            <!-- Y Axis values -->
            <text x="12" y="34" font-size="10" fill="#94a3b8">6</text>
            <text x="12" y="79" font-size="10" fill="#94a3b8">3</text>
            <text x="12" y="124" font-size="10" fill="#94a3b8">0</text>

            <!-- Fill Area under the line -->
            <path d="${areaPath}" fill="url(#lineGrad)" />

            <!-- The Line -->
            <path d="${linePath}" fill="none" stroke="#0f766e" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />

            <!-- Points and Day Labels -->
            ${coords.map(c => `
              <circle cx="${c.x}" cy="${c.y}" r="4.5" fill="#0f766e" stroke="#ffffff" stroke-width="2" />
              <text x="${c.x}" y="142" font-size="10" font-weight="500" fill="#94a3b8" text-anchor="middle">${c.day}</text>
            `).join('')}
          </svg>
        </div>
      </div>

      <div class="panel">
        <h4>Current Operational Breakdown</h4>
        <div class="donut-row">
          <svg width="88" height="88" viewBox="0 0 36 36">
            <circle cx="18" cy="18" r="15.5" fill="none" stroke="#E7E9E1" stroke-width="4"/>
            <circle cx="18" cy="18" r="15.5" fill="none" stroke="#C1483A" stroke-width="4" stroke-dasharray="${Math.max(5, newReports * 12)} 97" stroke-dashoffset="0" transform="rotate(-90 18 18)"/>
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

function renderDashboardLineChart(reports = []) {
  const canvas = document.getElementById('dashboardLineChart');
  if (!canvas || typeof Chart === 'line') return;

  const ctx = canvas.getContext('2d');
  const gradient = ctx.createLinearGradient(0, 0, 0, 160);
  gradient.addColorStop(0, 'rgba(15, 118, 110, 0.25)');
  gradient.addColorStop(1, 'rgba(15, 118, 110, 0.0)');

  if (window.activeDashChart) {
    window.activeDashChart.destroy();
  }

  window.activeDashChart = new Chart(ctx, {
    type: 'line',
    data: {
      labels: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
      datasets: [{
        label: 'Reports Filed',
        data: [2, 4, 3, 6, Math.max(1, reports.length), 3, 5],
        borderColor: '#0f766e',
        borderWidth: 2.5,
        backgroundColor: gradient,
        fill: true,
        tension: 0.35,
        pointRadius: 4,
        pointHoverRadius: 6,
        pointBackgroundColor: '#0f766e'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false }
      },
      scales: {
        x: {
          grid: { display: false },
          ticks: { color: '#94a3b8', font: { size: 11 } }
        },
        y: {
          beginAtZero: true,
          grid: { color: '#f1f5f9' },
          ticks: { stepSize: 2, color: '#94a3b8', font: { size: 11 } }
        }
      }
    }
  });
}

async function renderApprovals() {
  const user = getActiveUser();
  let approvals = [];

  try {
    approvals = await DataService.getApprovals(user.barangayId) || [];
  } catch (err) {
    console.error('Error fetching approvals:', err);
  }

  const trs = approvals.map(r => `
    <tr class="row-link" data-approval-id="${r.id}">
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--teal-100);color:var(--teal-800);">${ic('user')}</div>
          <div>
            <div class="t">${r.name || r.full_name || 'Resident'}</div>
            <div class="s">${r.address || 'Address pending'}</div>
          </div>
        </div>
      </td>
      <td>
        <div style="font-weight:600; color:var(--charcoal);">${r.idType || r.id_type || 'Valid ID'}</div>
        <div class="s">${(r.idNumber || r.id_number) ? `ID No: ${r.idNumber || r.id_number}` : 'Number pending verification'}</div>
      </td>
      <td>${r.date || (r.created_at ? new Date(r.created_at).toLocaleDateString() : 'Recent')}</td>
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
      const selected = approvals.find(item => item.id == row.dataset.approvalId);
      if (selected) openApprovalModal(selected);
    };
  });
}

function openApprovalModal(record) {
  modalRoot.innerHTML = `
    <div class="modal-overlay">
      <div class="modal-box" style="width: 480px;">
        <div class="modal-head">
          <div>
            <h4>${record.name || record.full_name}</h4>
            <span style="font-size:11px;color:var(--muted);">Resident ID Verification</span>
          </div>
          <button class="modal-close" id="approval-modal-close">✕</button>
        </div>
        
        <div class="modal-field"><span class="modal-label">Address</span><span class="modal-value">${record.address || 'Not specified'}</span></div>
        <div class="modal-field"><span class="modal-label">ID type</span><span class="modal-value">${record.idType || record.id_type || 'Valid Government ID'}</span></div>
        <div class="modal-field"><span class="modal-label">ID number</span><span class="modal-value">${record.idNumber || record.id_number || 'N/A'}</span></div>
        
        <div style="margin-top:14px;">
          <label class="modal-label" style="display:block; margin-bottom:6px;">Uploaded ID Photo</label>
          ${(record.idPhotoUrl || record.id_photo_url)
            ? `<div style="border:1px solid var(--sand); border-radius:6px; overflow:hidden; background:#000; text-align:center;">
                 <img src="${record.idPhotoUrl || record.id_photo_url}" alt="Resident ID" style="max-width:100%; max-height:240px; display:inline-block; object-fit:contain;">
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

  document.getElementById('btn-approve').onclick = async () => {
    await DataService.updateApprovalStatus(record.id, 'APPROVED');
    closeModal();
    renderApprovals();
  };

  document.getElementById('btn-reject').onclick = async () => {
    await DataService.updateApprovalStatus(record.id, 'REJECTED');
    closeModal();
    renderApprovals();
  };
}

async function renderDocuments() {
  const user = getActiveUser();
  let docs = [];

  try {
    docs = await DataService.getDocuments(user.barangayId) || [];
  } catch (err) {
    console.error('Error fetching documents:', err);
  }

  const trs = docs.map(d => {
    const rawStatus = (d.status || 'pending').toLowerCase();
    const pickupVal = d.pickup || d.pickup_date || '';

    return `
      <tr data-doc-row="${d.id}">
        <td>
          <div class="name-cell">
            <div class="ic" style="background:var(--gold-100);color:var(--gold-600);">${ic('doc')}</div>
            <div>
              <div class="t">${d.name || d.resident_name}</div>
              <div class="s">Requested: ${d.type || d.document_type}</div>
            </div>
          </div>
        </td>
        <td>
          <select class="field doc-status-select" data-doc-id="${d.id}" style="width: auto; padding: 4px 8px; font-size: 12px; height: 32px;">
            <option value="pending" ${rawStatus === 'pending' ? 'selected' : ''}>Pending</option>
            <option value="in_progress" ${rawStatus === 'in_progress' ? 'selected' : ''}>In Progress</option>
            <option value="resolved" ${rawStatus === 'resolved' ? 'selected' : ''}>Resolved</option>
          </select>
        </td>
        <td>
          <input type="date" class="field doc-date-picker" data-doc-id="${d.id}" value="${pickupVal}" style="width: 145px; padding: 4px 8px; font-size: 12px; height: 32px;">
        </td>
        <td>
          <span class="save-indicator text-xs" id="indicator-${d.id}" style="color: var(--teal-800); font-size: 11px;">Saved</span>
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

  document.querySelectorAll('.doc-status-select').forEach(select => {
    select.onchange = async () => {
      const id = select.dataset.docId;
      const dateInput = document.querySelector(`.doc-date-picker[data-doc-id="${id}"]`);
      const indicator = document.getElementById(`indicator-${id}`);
      
      indicator.textContent = 'Saving...';
      try {
        await DataService.updateDocument(id, {
          status: select.value,
          pickup_date: dateInput.value || null
        });
        indicator.textContent = 'Saved';
      } catch (err) {
        indicator.textContent = 'Error saving';
        console.error(err);
      }
    };
  });

  document.querySelectorAll('.doc-date-picker').forEach(input => {
    input.onchange = async () => {
      const id = input.dataset.docId;
      const select = document.querySelector(`.doc-status-select[data-doc-id="${id}"]`);
      const indicator = document.getElementById(`indicator-${id}`);

      indicator.textContent = 'Saving...';
      try {
        await DataService.updateDocument(id, {
          status: select.value,
          pickup_date: input.value || null
        });
        indicator.textContent = 'Saved';
      } catch (err) {
        indicator.textContent = 'Error saving';
        console.error(err);
      }
    };
  });
}

async function renderQueue() {
  const user = getActiveUser();
  let reports = [];

  try {
    reports = await DataService.getReports(user.barangayId) || [];
  } catch (err) {
    console.error('Error fetching reports from Supabase:', err);
  }

  const cat = document.getElementById('queue-cat-filter')?.value || '';
  const stat = document.getElementById('queue-stat-filter')?.value || '';
  const search = (document.getElementById('queue-search')?.value || '').toLowerCase();

  const list = reports.filter(q => {
    const matchesCat = !cat || q.category === cat;
    const matchesStat = !stat || (q.status || '').toLowerCase() === stat;
    const matchesSearch = !search ||
      (q.title && q.title.toLowerCase().includes(search)) ||
      (q.description && q.description.toLowerCase().includes(search));
    return matchesCat && matchesStat && matchesSearch;
  });

  const trs = list.map(r => `
    <tr>
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--${r.priority === 'high' ? 'brick-100' : 'sage-100'});color:var(--${r.priority === 'high' ? 'brick' : 'teal-800'});">${ic('queue')}</div>
          <div>
            <div class="t">${r.title}</div>
            <div class="s">${r.category} · ${r.createdAt ? new Date(r.createdAt).toLocaleDateString() : 'Recent'} ${r.aiSeverityScore ? `· AI Severity: ${r.aiSeverityScore}/100` : ''}</div>
          </div>
        </div>
      </td>
      <td><span class="pill ${(r.priority || 'medium').toLowerCase()}">${(r.priority || 'MEDIUM').toUpperCase()} priority</span></td>
      <td><span class="pill ${(r.status || 'pending').toLowerCase()}">${r.status || 'Pending'}</span></td>
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
        <p>Live municipal concerns submitted by residents.</p>
      </div>
    </div>
    <div class="filters">
      <select id="queue-cat-filter">
        <option value="">All categories</option>
        <option value="Disturbance" ${cat === 'Disturbance' ? 'selected' : ''}>Disturbance</option>
        <option value="Drainage" ${cat === 'Drainage' ? 'selected' : ''}>Drainage</option>
        <option value="Sanitation" ${cat === 'Sanitation' ? 'selected' : ''}>Sanitation</option>
        <option value="Disaster" ${cat === 'Disaster' ? 'selected' : ''}>Disaster</option>
      </select>
      <select id="queue-stat-filter">
        <option value="">All statuses</option>
        <option value="pending" ${stat === 'pending' ? 'selected' : ''}>Pending</option>
        <option value="in_progress" ${stat === 'in_progress' ? 'selected' : ''}>In progress</option>
        <option value="resolved" ${stat === 'resolved' ? 'selected' : ''}>Resolved</option>
      </select>
      <input id="queue-search" placeholder="Search reports..." value="${search}">
    </div>
    <table class="table">
      <thead><tr><th>Report</th><th>Priority</th><th>Status</th><th></th></tr></thead>
      <tbody>${trs || '<tr><td colspan="4" style="color:var(--muted);padding:16px 10px;">No records match the current filters.</td></tr>'}</tbody>
    </table>
  `;
  stage.innerHTML = shell(main, 'queue');

  document.getElementById('queue-cat-filter').onchange = renderQueue;
  document.getElementById('queue-stat-filter').onchange = renderQueue;
  document.getElementById('queue-search').oninput = renderQueue;
  document.querySelectorAll('[data-view-report]').forEach(btn => {
    btn.onclick = () => showScreen('detail', btn.dataset.viewReport);
  });
}

async function renderDetail(id) {
  let report = null;
  try {
    report = await DataService.getReportById(id);
  } catch (err) {
    console.error('Error fetching report details:', err);
  }

  if (!report) return showScreen('queue');

  const aiStatusBadge = report.ai_valid === false || report.aiValid === false
    ? `<span class="pill" style="background:var(--brick-100);color:var(--brick);font-weight:700;">AI FLAGGED: TROLL / SPAM</span>`
    : `<span class="pill" style="background:var(--teal-100);color:var(--teal-900);font-weight:700;">AI VERIFIED GENUINE</span>`;

  const aiCard = `
    <div class="panel" style="margin-top: 14px; border-left: 4px solid ${report.aiValid === false ? 'var(--brick)' : 'var(--teal-700)'};">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
        <h4 style="margin:0;">Automated AI Triage</h4>
        ${aiStatusBadge}
      </div>
      <p style="font-size: 12px; margin: 4px 0;"><b>Calculated Severity:</b> ${report.ai_severity_score ?? report.aiSeverityScore ?? 0}/100</p>
      <p style="font-size: 11.5px; color: var(--muted); margin-top: 6px; line-height: 1.4;">
        <b>Assessment:</b> ${report.ai_triage_reason || report.aiTriageReason || 'AI analysis completed without flags.'}
      </p>
    </div>
  `;

  const main = `
    <div class="main-head">
      <div>
        <div style="display:flex;align-items:center;gap:10px;">
          <h3>${report.title}</h3>
          <span class="pill ${(report.priority || 'medium').toLowerCase()}">${(report.priority || 'MEDIUM').toUpperCase()}</span>
        </div>
        <p>${report.category} · Status: ${report.status}</p>
      </div>
      <button class="btn-small ghost" id="btn-back-queue">Back to queue</button>
    </div>
    <div style="display:grid;grid-template-columns:1.4fr 1fr;gap:16px;">
      <div>
        <div class="panel">
          <h4>Incident Details</h4>
          <p style="font-size:12.5px;line-height:1.6;margin:0;">${report.description || 'No description provided.'}</p>
          ${report.photo_url || report.photoUrl ? `<div style="margin-top:14px;"><img src="${report.photo_url || report.photoUrl}" alt="Evidence" style="max-width:100%; border-radius:6px;"></div>` : '<div class="evidence-thumb" style="margin-top:14px;">No photo attached</div>'}
        </div>
        ${aiCard}
      </div>
      <div>
        <div class="panel">
          <h4>Triage &amp; Management</h4>
          <label class="field-label">Internal notes</label>
          <textarea class="field" id="report-notes" style="height:76px;resize:none;">${report.internal_notes || report.internalNotes || ''}</textarea>
          <div style="display:flex;gap:8px;margin-top:14px;flex-wrap:wrap;">
            <button class="btn-small" id="btn-mark-progress">Mark in progress</button>
            <button class="btn-small ghost" style="color:var(--sage);" id="btn-mark-resolved">Resolve</button>
          </div>
        </div>
      </div>
    </div>
  `;
  stage.innerHTML = shell(main, 'queue');

  document.getElementById('btn-back-queue').onclick = () => showScreen('queue');
  document.getElementById('btn-mark-progress').onclick = async () => {
    const notes = document.getElementById('report-notes').value;
    await DataService.updateReport(id, { status: 'in_progress', internal_notes: notes });
    showScreen('queue');
  };
  document.getElementById('btn-mark-resolved').onclick = async () => {
    const notes = document.getElementById('report-notes').value;
    await DataService.updateReport(id, { status: 'resolved', internal_notes: notes });
    showScreen('queue');
  };
}

async function renderAnnouncements() {
  const user = getActiveUser();
  let announcements = [];

  try {
    announcements = await DataService.getAnnouncements(user.barangayId) || [];
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
      const [year, month, day] = a.event_date.split('-').map(Number);
      if (year === viewYear && month === (viewMonth + 1)) {
        eventDays.add(day);
      }
    }
  });

  const activeItems = activeList.map(a => `
    <div class="ann-item" style="margin-bottom:12px;">
      <div class="top"><span class="tag" style="color:var(--teal-800);background:var(--teal-100);">${a.category || a.tag || 'General'}</span></div>
      <p class="title" style="margin:6px 0 2px 0;">${a.title}</p>
      ${a.description ? `<p style="font-size:12px; color:var(--muted); margin:0 0 6px 0;">${a.description}</p>` : ''}
      <p class="meta">
        ${a.event_date ? `Event: ${a.event_date} · ` : ''}Posted ${a.posted || 'Recent'} · 
        <a href="#" style="color:var(--teal-800);text-decoration:none;font-weight:600;margin-right:8px;" data-edit-ann="${a.id}">Edit</a>
        <a href="#" style="color:var(--brick);text-decoration:none;font-weight:600;" data-archive-ann="${a.id}">Archive</a>
      </p>
    </div>
  `).join('');

  const pastItems = pastList.map(a => `
    <div class="ann-item" style="margin-bottom:10px; opacity:0.85; background:#fbfbfa;">
      <div class="top"><span class="tag" style="color:#64748b;background:#f1f5f9;">${a.category || a.tag || 'Archived'}</span></div>
      <p class="title" style="margin:4px 0 2px 0; font-size:13.5px;">${a.title}</p>
      ${a.description ? `<p style="font-size:11.5px; color:var(--muted); margin:0 0 4px 0;">${a.description}</p>` : ''}
      <p class="meta">
        ${a.event_date ? `Event: ${a.event_date} · ` : ''}${a.posted || 'Past'} · 
        <a href="#" style="color:var(--teal-800);text-decoration:none;font-weight:600;margin-right:8px;" data-repost-ann="${a.id}">Repost</a>
        <a href="#" style="color:var(--charcoal);text-decoration:none;font-weight:600;" data-edit-ann="${a.id}">Edit</a>
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
        ${hasEvent ? `
          <span style="
            width: 5px;
            height: 5px;
            background-color: ${isToday ? '#ffffff' : 'var(--teal-800, #0f766e)'};
            border-radius: 50%;
            position: absolute;
            bottom: 2px;
          "></span>
        ` : ''}
      </div>
    `;
  }).join('');

  const main = `
    <div class="main-head">
      <div>
        <h3>Announcements &amp; calendar</h3>
        <p>Bulletins published to mobile users.</p>
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
          <h4 style="margin: 0;">${viewMonthName} ${viewYear}</h4>
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
      contentHtml: `
        <div class="modal-form-group">
          <label>Title</label>
          <input class="field" id="ann-title" placeholder="e.g. Free Rabies Vaccination" required>
        </div>
        <div class="modal-form-group">
          <label>Description</label>
          <textarea class="field" id="ann-desc" placeholder="Details and instructions..." style="height:60px;resize:none;" required></textarea>
        </div>
        <div class="modal-form-group">
          <label>Event Date</label>
          <input class="field" type="date" id="ann-date" required>
        </div>
        <div class="modal-form-group">
          <label>Category</label>
          <select class="field" id="ann-cat">
            <option value="Advisory">Advisory</option>
            <option value="Health">Health</option>
            <option value="Event">Event</option>
          </select>
        </div>
      `,
      confirmText: 'Publish',
      onConfirm: async () => {
        const title = document.getElementById('ann-title').value.trim();
        const description = document.getElementById('ann-desc').value.trim();
        const eventDate = document.getElementById('ann-date').value;
        const category = document.getElementById('ann-cat').value;

        await DataService.createAnnouncement({
          barangayId: user.barangayId,
          title,
          description,
          eventDate,
          category
        });
        closeModal();
        renderAnnouncements();
      }
    });
  };


  document.querySelectorAll('[data-archive-ann]').forEach(btn => {
    btn.onclick = async (e) => {
      e.preventDefault();
      await DataService.archiveAnnouncement(btn.dataset.archiveAnn);
      renderAnnouncements();
    };
  });


  document.querySelectorAll('[data-repost-ann]').forEach(btn => {
    btn.onclick = async (e) => {
      e.preventDefault();
      await DataService.unarchiveAnnouncement(btn.dataset.repostAnn);
      renderAnnouncements();
    };
  });


  document.querySelectorAll('[data-edit-ann]').forEach(btn => {
    btn.onclick = (e) => {
      e.preventDefault();
      const annId = btn.dataset.editAnn;
      const ann = announcements.find(a => a.id == annId);
      if (!ann) return;

      openCustomModal({
        title: 'Edit announcement',
        contentHtml: `
          <div class="modal-form-group">
            <label>Title</label>
            <input class="field" id="edit-ann-title" value="${ann.title || ''}" required>
          </div>
          <div class="modal-form-group">
            <label>Description</label>
            <textarea class="field" id="edit-ann-desc" style="height:60px;resize:none;" required>${ann.description || ''}</textarea>
          </div>
          <div class="modal-form-group">
            <label>Event Date</label>
            <input class="field" type="date" id="edit-ann-date" value="${ann.event_date || ''}" required>
          </div>
          <div class="modal-form-group">
            <label>Category</label>
            <select class="field" id="edit-ann-cat">
              <option value="Advisory" ${(ann.category || ann.tag) === 'Advisory' ? 'selected' : ''}>Advisory</option>
              <option value="Health" ${(ann.category || ann.tag) === 'Health' ? 'selected' : ''}>Health</option>
              <option value="Event" ${(ann.category || ann.tag) === 'Event' ? 'selected' : ''}>Event</option>
            </select>
          </div>
        `,
        confirmText: 'Save Changes',
        onConfirm: async () => {
          const title = document.getElementById('edit-ann-title').value.trim();
          const description = document.getElementById('edit-ann-desc').value.trim();
          const eventDate = document.getElementById('edit-ann-date').value;
          const category = document.getElementById('edit-ann-cat').value;

          await DataService.updateAnnouncement(annId, {
            title,
            description,
            eventDate,
            category
          });
          closeModal();
          renderAnnouncements();
        }
      });
    };
  });
}

async function renderEmergency() {
  const user = getActiveUser();
  let contacts = [];

  try {
    contacts = await DataService.getEmergencyContacts(user.barangayId) || [];
  } catch (err) {
    console.error('Error fetching emergency contacts:', err);
  }

  const trs = contacts.map(r => `
    <tr>
      <td>
        <div class="name-cell">
          <div class="ic" style="background:var(--sage-100);color:#3E6552;">${ic('phone')}</div>
          <div class="t">${r.name}</div>
        </div>
      </td>
      <td>${r.category || 'General'}</td>
      <td><b>${r.contact_number || r.contactNumber || r.num || r.number || 'No number'}</b></td>
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
      contentHtml: `
        <div class="modal-form-group">
          <label>Agency / Contact Name</label>
          <input class="field" id="em-name" required placeholder="e.g. Police Action Center">
        </div>
        <div class="modal-form-group">
          <label>Category</label>
          <select class="field" id="em-cat">
            <option value="Barangay">Barangay</option>
            <option value="National">National</option>
          </select>
        </div>
        <div class="modal-form-group">
          <label>Contact Number</label>
          <input class="field" id="em-num" required placeholder="e.g. 911 or 0917-XXX-XXXX">
        </div>
      `,
      confirmText: 'Save Contact',
      onConfirm: async () => {
        const name = document.getElementById('em-name').value;
        const category = document.getElementById('em-cat').value;
        const contactNumber = document.getElementById('em-num').value;
        await DataService.createEmergencyContact({
          barangayId: user.barangayId,
          name,
          category,
          num: contactNumber
        });
        closeModal();
        renderEmergency();
      }
    });
  };

  document.querySelectorAll('[data-edit-em]').forEach(b => {
    b.onclick = () => {
      const contact = contacts.find(c => c.id == b.dataset.editEm);
      if (!contact) return;

      openCustomModal({
        title: 'Edit emergency contact',
        contentHtml: `
          <div class="modal-form-group">
            <label>Agency / Contact Name</label>
            <input class="field" id="em-edit-name" value="${contact.name || ''}" required>
          </div>
          <div class="modal-form-group">
            <label>Category</label>
            <select class="field" id="em-edit-cat">
              <option value="Barangay" ${contact.category === 'Barangay' ? 'selected' : ''}>Barangay</option>
              <option value="National" ${contact.category === 'National' ? 'selected' : ''}>National</option>
            </select>
          </div>
          <div class="modal-form-group">
            <label>Contact Number</label>
            <input class="field" id="em-edit-num" value="${contact.contact_number || contact.num || ''}" required>
          </div>
        `,
        confirmText: 'Update Contact',
        onConfirm: async () => {
          const name = document.getElementById('em-edit-name').value;
          const category = document.getElementById('em-edit-cat').value;
          const num = document.getElementById('em-edit-num').value;

          await DataService.updateEmergencyContact(contact.id, { name, category, num });
          closeModal();
          renderEmergency();
        }
      });
    };
  });

  document.querySelectorAll('[data-del-em]').forEach(b => {
    b.onclick = async () => {
      if (confirm("Delete this emergency contact?")) {
        await DataService.deleteEmergencyContact(b.dataset.delEm);
        renderEmergency();
      }
    };
  });
}

const initialUser = getActiveUser();
if (initialUser) {
  showScreen('dashboard');
} else {
  showScreen('login');
}