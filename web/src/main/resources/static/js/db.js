import { createClient } from 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/+esm';

const SUPABASE_URL = 'https://wjrabyrmhymwtvcjywea.supabase.co';
const SUPABASE_ANON_KEY = 'sb_publishable_eKmPItxbga4MB9Rn2JuMJw_04jCCvGE';

export const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

// Tables
//   profiles       - every user (residents + officials). role, account_status, psgc_code
//   reports        - complaints / incidents from the mobile app (has latitude / longitude)
//   requests       - document / barangay ID requests from the mobile app
//   announcements  - posted by officials, read by residents with the same psgc_code
//   emergency_contacts
//
// psgc_code is always the 10-digit PSGC code. The database converts old 9-digit codes and
// stamps psgc_code / author_id / user_id itself (see supabase/2026-10-05_fix_psgc_rls_reports.sql),
// so the portal can never post into the wrong barangay.

const BLOCKED_STATUSES = ['pending', 'rejected', 'unapproved'];

export function isOfficialProfile(profile) {
  if (!profile) return false;
  const role = String(profile.role || 'resident').toLowerCase();
  const status = String(profile.account_status || 'approved').toLowerCase();
  return role !== 'resident' && !BLOCKED_STATUSES.includes(status);
}

/** Throws when an update/delete touched no rows (usually RLS: wrong role or barangay). */
function ensureChanged(data, what) {
  if (!data || data.length === 0) {
    throw new Error(`Could not ${what}. Your account may not have permission for this barangay.`);
  }
  return data;
}

const MAP_PIN_RE = /Map pin:\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)/i;

/** Latitude/longitude of a report: real columns first, then the "Map pin: lat, lng" line older app versions wrote. */
export function reportCoords(r) {
  const lat = Number(r?.latitude);
  const lng = Number(r?.longitude);
  if (r?.latitude != null && r?.longitude != null && Number.isFinite(lat) && Number.isFinite(lng)) {
    return { lat, lng };
  }
  const m = MAP_PIN_RE.exec(r?.description || '');
  return m ? { lat: Number(m[1]), lng: Number(m[2]) } : null;
}

/** "Location: ..." line the mobile app writes into the description. */
export function reportLocationText(r) {
  if (r?.location_label) return r.location_label;
  const m = /Location:\s*(.+)/i.exec(r?.description || '');
  return m ? m[1].trim() : '';
}

/** YYYY-MM-DD in the browser's local time zone (toISOString() is UTC and is a day behind in Manila before 8 AM). */
export function localDateString(d) {
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export const DataService = {

  async login(email, password) {
    if (!email || !password) throw new Error('Email and password required.');
    const { data, error } = await supabase.auth.signInWithPassword({
      email: email.trim(),
      password
    });
    if (error) throw error;
    return data;
  },

  async logout() {
    return await supabase.auth.signOut();
  },

  async hasSession() {
    const { data } = await supabase.auth.getSession();
    return Boolean(data?.session);
  },

  async getUserProfile(userId) {
    const { data, error } = await supabase
      .from('profiles')
      .select('*')
      .eq('id', userId)
      .maybeSingle();
    if (error) throw error;
    return data;
  },

  /** Barangay name for the sidebar: profile column if present, else the public PSGC directory. */
  async getBarangayName(profile) {
    const fromProfile = profile?.barangay || profile?.barangay_name;
    if (fromProfile) return /^(brgy|barangay)/i.test(fromProfile) ? fromProfile : `Barangay ${fromProfile}`;
    const code = profile?.psgc_code;
    if (!code) return 'Barangay';
    try {
      const res = await fetch(`https://psgc.cloud/api/barangays/${encodeURIComponent(code)}`);
      if (res.ok) {
        const json = await res.json();
        const row = Array.isArray(json) ? json[0] : json;
        if (row?.name) return `Barangay ${row.name}`;
      }
    } catch (err) {
      console.warn('PSGC lookup failed:', err);
    }
    return `Barangay ${code}`;
  },

  // ---------------- REPORTS (complaints / incidents) ----------------

  async getReports(psgcCode) {
    const { data, error } = await supabase
      .from('reports')
      .select('*')
      .eq('psgc_code', psgcCode)
      .order('created_at', { ascending: false });
    if (error) throw error;
    return data || [];
  },

  async getReportById(id) {
    const { data, error } = await supabase
      .from('reports')
      .select('*')
      .eq('id', id)
      .maybeSingle();
    if (error) throw error;
    return data;
  },

  async updateReport(id, updates) {
    const { data, error } = await supabase
      .from('reports')
      .update(updates)
      .eq('id', id)
      .select('id');
    if (error) throw error;
    return ensureChanged(data, 'update this report');
  },

  // ---------------- ACCOUNT APPROVALS ----------------

  async getApprovals(psgcCode) {
    const { data, error } = await supabase
      .from('profiles')
      .select('*')
      .eq('psgc_code', psgcCode)
      .eq('account_status', 'pending')
      .order('created_at', { ascending: false });
    if (error) throw error;
    return (data || []).map(u => ({
      id: u.id,
      name: [u.first_name, u.middle_name, u.last_name, u.suffix].filter(Boolean).join(' ') || u.email || 'Resident',
      email: u.email || '',
      phone: u.mobile_number || '',
      address: u.current_address || 'Address pending',
      idType: u.id_type || 'Valid ID',
      idNumber: u.id_number || '',
      idPhotoUrl: u.id_photo_url,
      date: u.created_at ? new Date(u.created_at).toLocaleDateString() : 'Recent'
    }));
  },

  async updateApprovalStatus(id, status) {
    const { data, error } = await supabase
      .from('profiles')
      .update({ account_status: status.toLowerCase() })
      .eq('id', id)
      .select('id');
    if (error) throw error;
    return ensureChanged(data, 'update this account');
  },

  // ---------------- DOCUMENT / ID REQUESTS ----------------

  async getDocuments(psgcCode) {
    const { data, error } = await supabase
      .from('requests')
      .select('*')
      .eq('psgc_code', psgcCode)
      .order('created_at', { ascending: false });
    if (error) throw error;
    return (data || []).map(d => {
      const applicant = /Applicant:\s*(.+)/i.exec(d.description || '');
      const docType = /Document Type:\s*(.+)/i.exec(d.description || '');
      return {
        ...d,
        name: d.requester_name || (applicant ? applicant[1].trim() : '') || 'Resident',
        type: d.document_type || (docType ? docType[1].trim() : '') ||
              (d.title || '').replace(/^Document Request:\s*/i, '') || 'Barangay Document',
        pickup: d.pickup_date || ''
      };
    });
  },

  async updateDocument(id, updates) {
    const { data, error } = await supabase
      .from('requests')
      .update(updates)
      .eq('id', id)
      .select('id');
    if (error) throw error;
    return ensureChanged(data, 'update this request');
  },

  // ---------------- ANNOUNCEMENTS ----------------

  async getAnnouncements(psgcCode) {
    const { data, error } = await supabase
      .from('announcements')
      .select('*')
      .eq('psgc_code', psgcCode)
      .order('created_at', { ascending: false });
    if (error) throw error;

    const todayStr = localDateString(new Date());

    return (data || []).map(a => {
      const isPastDate = a.event_date ? a.event_date < todayStr : false;
      return {
        ...a,
        tag: a.category || a.type || 'General',
        description: a.body || '',
        posted: a.created_at ? new Date(a.created_at).toLocaleDateString() : 'Recent',
        isPastEvent: isPastDate,
        isArchived: Boolean(a.is_archived || isPastDate)
      };
    });
  },

  async createAnnouncement({ title, description, eventDate, category, psgcCode, authorId }) {
    // psgc_code and author_id are re-stamped by the database from the signed-in official
    const { data, error } = await supabase
      .from('announcements')
      .insert([{
        title,
        body: description,
        event_date: eventDate || null,
        category,
        type: category === 'Event' ? 'event' : 'announcement',
        psgc_code: psgcCode,
        author_id: authorId,
        is_archived: false
      }])
      .select('id');
    if (error) throw error;
    return ensureChanged(data, 'publish this announcement');
  },

  async updateAnnouncement(id, { title, description, eventDate, category, isArchived }) {
    const updates = {
      title,
      body: description,
      event_date: eventDate || null,
      category,
      type: category === 'Event' ? 'event' : 'announcement'
    };
    if (typeof isArchived === 'boolean') updates.is_archived = isArchived;
    const { data, error } = await supabase
      .from('announcements')
      .update(updates)
      .eq('id', id)
      .select('id');
    if (error) throw error;
    return ensureChanged(data, 'update this announcement');
  },

  async archiveAnnouncement(id) {
    const { data, error } = await supabase
      .from('announcements')
      .update({ is_archived: true })
      .eq('id', id)
      .select('id');
    if (error) throw error;
    return ensureChanged(data, 'archive this announcement');
  },

  // ---------------- EMERGENCY CONTACTS ----------------

  async getEmergencyContacts(psgcCode) {
    const { data, error } = await supabase
      .from('emergency_contacts')
      .select('*')
      .eq('psgc_code', psgcCode)
      .order('id', { ascending: true });
    if (error) throw error;
    return (data || []).map(c => ({
      ...c,
      category: c.scope || c.category || 'General',
      num: c.phone_number || c.contact_number || ''
    }));
  },

  async createEmergencyContact({ name, category, num, psgcCode }) {
    const { data, error } = await supabase
      .from('emergency_contacts')
      .insert([{ name, scope: category, phone_number: num, psgc_code: psgcCode }])
      .select('id');
    if (error) throw error;
    return ensureChanged(data, 'save this contact');
  },

  async updateEmergencyContact(id, { name, category, num }) {
    const { data, error } = await supabase
      .from('emergency_contacts')
      .update({ name, scope: category, phone_number: num })
      .eq('id', id)
      .select('id');
    if (error) throw error;
    return ensureChanged(data, 'update this contact');
  },

  async deleteEmergencyContact(id) {
    const { data, error } = await supabase
      .from('emergency_contacts')
      .delete()
      .eq('id', id)
      .select('id');
    if (error) throw error;
    return ensureChanged(data, 'delete this contact');
  }
};
