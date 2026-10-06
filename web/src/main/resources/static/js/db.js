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

const SUPER_ADMIN_ROLES = ['db_admin', 'super_admin', 'superadmin'];

/** db_admin = super admin over every barangay. */
export function isSuperAdminProfile(profile) {
  if (!profile) return false;
  const status = String(profile.account_status || 'approved').toLowerCase();
  return SUPER_ADMIN_ROLES.includes(String(profile.role || '').toLowerCase()) && !BLOCKED_STATUSES.includes(status);
}

/**
 * Barangay scope of a query.
 *   psgcCode = '1380300024' -> that barangay only
 *   psgcCode = null         -> everything the signed-in user may see (super admin: all barangays)
 */
function scoped(query, psgcCode) {
  return psgcCode ? query.eq('psgc_code', psgcCode) : query;
}

function ownOrNationwide(query, psgcCode) {
  if (psgcCode === 'ALL') return query;
  return psgcCode ? query.eq('psgc_code', psgcCode) : query.is('psgc_code', null);
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

  /**
   * Barangay name for the sidebar header - never hardcoded:
   * 1. profiles.barangay / barangay_name, 2. the barangays table, 3. the public PSGC directory.
   */
  async getBarangayName(profile) {
    const withPrefix = n => (/^(brgy|barangay)/i.test(n) ? n : `Barangay ${n}`);
    const fromProfile = profile?.barangay || profile?.barangay_name;
    if (fromProfile) return withPrefix(fromProfile);
    const code = profile?.psgc_code;
    if (!code) return 'Barangay';
    try {
      const { data } = await supabase.from('barangays').select('*').eq('psgc_code', code).maybeSingle();
      if (data?.name) {
        // many barangays share a name (e.g. 3 "San Isidro" in Metro Manila), so add the city
        const city = String(data.city_municipality || data.city || '').replace(/^City of\s+/i, '');
        return withPrefix(data.name) + (city ? `, ${city}` : '');
      }
    } catch (err) {
      console.warn('barangays lookup failed:', err);
    }
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
    const { data, error } = await scoped(supabase
      .from('reports')
      .select('*')
      .order('created_at', { ascending: false }), psgcCode);
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
    const { data, error } = await scoped(supabase
      .from('profiles')
      .select('*')
      .eq('account_status', 'pending')
      .eq('role', 'resident')
      .order('created_at', { ascending: false }), psgcCode);
    if (error) throw error;
    return (data || []).map(u => ({
      psgc_code: u.psgc_code,
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
    let q = supabase.from('requests').select('*, profiles(email, mobile_number)').order('created_at', { ascending: false });
    let { data, error } = await scoped(q, psgcCode);
    if (error) {
      // Fallback if profiles relation doesn't exist
      const fallback = await scoped(supabase.from('requests').select('*').order('created_at', { ascending: false }), psgcCode);
      data = fallback.data;
    }
    return (data || []).map(d => {
      const applicant = /Applicant:\s*(.+)/i.exec(d.description || '');
      const docType = /Document Type:\s*(.+)/i.exec(d.description || '');
      const purpose = /Purpose:\s*(.+)/i.exec(d.description || '');
      const phone = d.profiles?.mobile_number || '';
      const email = d.profiles?.email || '';
      return {
        ...d,
        name: d.requester_name || (applicant ? applicant[1].trim() : '') || 'Resident',
        type: d.document_type || (docType ? docType[1].trim() : '') ||
              (d.title || '').replace(/^Document Request:\s*/i, '') || 'Barangay Document',
        purpose: purpose ? purpose[1].trim() : (d.description || ''),
        contact: [phone, email].filter(Boolean).join(' · '),
        pickup: d.pickup_date || '',
        remarks: d.admin_remarks || ''
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
    const { data, error } = await ownOrNationwide(supabase
      .from('announcements')
      .select('*, profiles!author_id(role)')
      .order('created_at', { ascending: false }), psgcCode);
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
        psgc_code: psgcCode || null,   // null = nationwide (super admin only)
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

  /**
   * Uploads an image for an announcement to the public "announcement-images" bucket
   * (folder = the official's barangay code) and returns its public URL.
   */
  async uploadAnnouncementImage(file, psgcCode) {
    if (!file || !String(file.type || '').startsWith('image/')) throw new Error('Only image files can be added.');
    if (file.size > 5 * 1024 * 1024) throw new Error('Image is too big (max 5 MB).');
    const ext = (String(file.name || '').split('.').pop() || file.type.split('/')[1] || 'jpg').toLowerCase().replace(/[^a-z0-9]/g, '') || 'jpg';
    const path = `${psgcCode || 'nationwide'}/${Date.now()}-${Math.random().toString(36).slice(2, 8)}.${ext}`;
    const bucket = supabase.storage.from('announcement-images');
    const { error } = await bucket.upload(path, file, { contentType: file.type, upsert: false });
    if (error) {
      if (/bucket not found/i.test(error.message || '')) {
        throw new Error('Image storage is not set up yet (run 2026-10-06_announcement_images.sql in Supabase).');
      }
      throw error;
    }
    return bucket.getPublicUrl(path).data.publicUrl;
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
    const { data, error } = await ownOrNationwide(supabase
      .from('emergency_contacts')
      .select('*')
      .order('id', { ascending: true }), psgcCode);
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
      .insert([{ name, scope: category, phone_number: num, psgc_code: psgcCode || null }])
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
  },

  // ---------------- SUPER ADMIN: barangays ----------------

  /** Search the barangays table by name, city or code. */
  async searchBarangays(text, limit = 50) {
    let q = supabase.from('barangays').select('*').order('name').limit(limit);
    const t = String(text || '').trim().replace(/[,()%]/g, ' ');
    if (t) {
      q = /^\d+$/.test(t)
        ? q.like('psgc_code', `${t}%`)
        : q.or(`name.ilike.%${t}%,city_municipality.ilike.%${t}%,province.ilike.%${t}%`);
    }
    const { data, error } = await q;
    if (error) throw error;
    return data || [];
  },

  /** { code: 'San Isidro, Makati' } for a list of barangay codes. */
  async getBarangayLabels(codes) {
    const unique = [...new Set((codes || []).filter(Boolean))];
    if (!unique.length) return {};
    const { data, error } = await supabase.from('barangays').select('*').in('psgc_code', unique);
    if (error) return {};
    const out = {};
    (data || []).forEach(b => {
      const city = String(b.city_municipality || b.city || '').replace(/^City of\s+/i, '');
      out[b.psgc_code] = b.name + (city ? `, ${city}` : '');
    });
    return out;
  },

  async saveBarangay(row, isNew) {
    const payload = {
      psgc_code: String(row.psgc_code || '').trim(),
      name: String(row.name || '').trim(),
      city_municipality: String(row.city_municipality || '').trim(),
      province: String(row.province || '').trim() || null,
      region: String(row.region || '').trim() || null
    };
    if (!/^\d{10}$/.test(payload.psgc_code)) throw new Error('The PSGC code must be 10 digits.');
    if (!payload.name || !payload.city_municipality) throw new Error('Name and city / municipality are required.');
    const q = isNew
      ? supabase.from('barangays').insert([payload]).select('psgc_code')
      : supabase.from('barangays').update(payload).eq('psgc_code', payload.psgc_code).select('psgc_code');
    const { data, error } = await q;
    if (error) throw error;
    return ensureChanged(data, 'save this barangay');
  },

  // ---------------- SUPER ADMIN: users & officials ----------------

  async listProfiles({ search = '', role = '', status = '', psgcCode = null } = {}) {
    let q = scoped(supabase.from('profiles').select('*').order('created_at', { ascending: false }).limit(300), psgcCode);
    if (role) q = role === 'official' ? q.not('role', 'in', '(resident,db_admin,super_admin,superadmin)') : q.eq('role', role);
    if (status) q = q.eq('account_status', status);
    const t = String(search || '').trim().replace(/[,()%]/g, ' ');
    if (t) q = q.or(`email.ilike.%${t}%,first_name.ilike.%${t}%,last_name.ilike.%${t}%,mobile_number.ilike.%${t}%,current_address.ilike.%${t}%`);
    const { data, error } = await q;
    if (error) throw error;
    return data || [];
  },

  async updateProfileAdmin(id, { role, account_status, psgc_code, rejection_reason }) {
    const updates = { role, account_status, psgc_code: psgc_code || null };
    if (rejection_reason !== undefined) updates.rejection_reason = rejection_reason;
    const { data, error } = await supabase.from('profiles').update(updates).eq('id', id).select('id');
    if (error) throw error;
    return ensureChanged(data, 'update this user');
  }
};
