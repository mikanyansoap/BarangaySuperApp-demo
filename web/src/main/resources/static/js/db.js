import { createClient } from 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js/+esm';

const SUPABASE_URL = 'https://wjrabyrmhymwtvcjywea.supabase.co';
const SUPABASE_ANON_KEY = 'sb_publishable_eKmPItxbga4MB9Rn2JuMJw_04jCCvGE';

export const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

// Helper for updating document requests
async function updateRequest(id, updates) {
  const { data, error } = await supabase
    .from('requests')
    .update(updates)
    .eq('id', id)
    .select();
  if (error) throw error;
  if (!data || data.length === 0) {
    throw new Error('Update changed 0 rows. Check the RLS UPDATE policy on public.requests.');
  }
  return data;
}

// Helper for updating incident/complaint reports
async function updateReportRecord(id, updates) {
  const { data, error } = await supabase
    .from('reports')
    .update(updates)
    .eq('id', id)
    .select();
  if (error) throw error;
  if (!data || data.length === 0) {
    throw new Error('Update changed 0 rows. Check the RLS UPDATE policy on public.reports.');
  }
  return data;
}

export const DataService = {

  async login(email, password) {
    if (!email || !password) throw new Error("Email and password required.");
    const { data, error } = await supabase.auth.signInWithPassword({
      email: email.trim(),
      password: password.trim()
    });
    if (error) throw error;
    return data;
  },

  async logout() {
    return await supabase.auth.signOut();
  },

  async getUserProfile(userId) {
    const { data, error } = await supabase
      .from('profiles')
      .select('id, email, first_name, last_name, role, psgc_code, barangays(name)')
      .eq('id', userId)
      .maybeSingle();

    if (error) {
      console.warn("Could not fetch user profile from public.profiles:", error);
      return null;
    }
    return data;
  },

  // REPORTS (Incidents & Complaints) - Now fetching from 'reports' table
  async getReports(psgcCode) {
    if (!psgcCode) throw new Error("psgcCode is required to fetch reports");
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
      .single();
    if (error) throw error;
    return { ...data, internal_notes: data.admin_remarks };
  },

  async updateReport(id, updates) {
    const { internal_notes, ...rest } = updates;
    const payload = internal_notes !== undefined ? { ...rest, admin_remarks: internal_notes } : rest;
    return updateReportRecord(id, payload);
  },

  // DOCUMENTS - Now fetching exclusively from 'requests' table
  async getDocuments(psgcCode) {
    if (!psgcCode) throw new Error("psgcCode is required to fetch documents");

    const { data: rows, error: reqError } = await supabase
      .from('requests')
      .select('*')
      .eq('psgc_code', psgcCode)
      .order('created_at', { ascending: false });
      
    if (reqError) throw reqError;

    const ids = [...new Set((rows || []).map(r => r.user_id).filter(Boolean))];
    const names = {};
    if (ids.length) {
      const { data: profs, error } = await supabase
        .from('profiles')
        .select('id, first_name, last_name, email')
        .in('id', ids);
      if (error) console.warn('Could not load requester names:', error);
      (profs || []).forEach(p => {
        names[p.id] = `${p.first_name || ''} ${p.last_name || ''}`.trim() || p.email;
      });
    }

    return (rows || []).map(d => ({
      ...d,
      name: names[d.user_id] || 'Resident',
      type: d.item_type || d.title || d.category || 'Barangay Document',
      pickup: d.pickup_date || ''
    }));
  },

  async updateDocument(id, updates) {
    return updateRequest(id, updates);
  },

  // ACCOUNT APPROVALS
  async getApprovals(psgcCode) {
    if (!psgcCode) throw new Error("psgcCode is required to fetch approvals");
    const { data, error } = await supabase
      .from('profiles')
      .select('*')
      .eq('psgc_code', psgcCode)
      .eq('account_status', 'pending')
      .order('created_at', { ascending: false });

    if (error) throw error;
    return (data || []).map(u => ({
      id: u.id,
      name: `${u.first_name || ''} ${u.last_name || ''}`.trim() || u.email,
      address: u.current_address || 'Address pending',
      idType: u.id_type || 'Valid ID',
      idNumber: u.id_number || 'N/A',
      idPhotoUrl: u.id_photo_url,
      date: u.created_at ? new Date(u.created_at).toLocaleDateString() : 'Recent'
    }));
  },

  async updateApprovalStatus(id, status) {
    const { data, error } = await supabase
      .from('profiles')
      .update({ account_status: status.toLowerCase() })
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  // ANNOUNCEMENTS
  async getAnnouncements(psgcCode) {
    if (!psgcCode) throw new Error("psgcCode is required to fetch announcements");
    const { data, error } = await supabase
      .from('announcements')
      .select('*')
      .eq('psgc_code', psgcCode)
      .order('created_at', { ascending: false });
    if (error) throw error;

    const todayStr = new Date().toISOString().split('T')[0];

    return (data || []).map(a => {
      const isPastDate = a.event_date ? a.event_date < todayStr : false;
      const isArchived = Boolean(a.is_archived || isPastDate);

      return {
        ...a,
        tag: a.category || a.type || 'General',
        description: a.body || '',
        posted: a.created_at ? new Date(a.created_at).toLocaleDateString() : 'Recent',
        isArchived: isArchived
      };
    });
  },

  async createAnnouncement({ title, description, eventDate, category, psgcCode, authorId }) {
    const payload = {
      title,
      body: description,
      event_date: eventDate || null,
      category,
      type: category,
      psgc_code: String(psgcCode),
      author_id: authorId
    };
    
    const { data, error } = await supabase
      .from('announcements')
      .insert([payload]);
    if (error) throw error;
    return data;
  },

  async updateAnnouncement(id, { title, description, eventDate, category }) {
    const { data, error } = await supabase
      .from('announcements')
      .update({
        title,
        body: description,
        event_date: eventDate || null,
        category,
        type: category
      })
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  async archiveAnnouncement(id) {
    const { data, error } = await supabase
      .from('announcements')
      .update({ is_archived: true }) 
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  async unarchiveAnnouncement(id) {
    const { data, error } = await supabase
      .from('announcements')
      .update({ is_archived: false })
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  // EMERGENCY CONTACTS
  async getEmergencyContacts(psgcCode) {
    if (!psgcCode) throw new Error("psgcCode is required to fetch emergency contacts");
    const { data, error } = await supabase
      .from('emergency_contacts')
      .select('*')
      .eq('psgc_code', psgcCode)
      .order('id', { ascending: true });
    if (error) throw error;
    return (data || []).map(c => ({
      ...c,
      num: c.phone_number || c.contact_number,
      number: c.phone_number || c.contact_number
    }));
  },

  async createEmergencyContact({ name, category, num, psgcCode }) {
    const payload = {
      name: name,
      scope: category,       
      phone_number: num,     
      psgc_code: String(psgcCode)
    };

    const { data, error } = await supabase
      .from('emergency_contacts')
      .insert([payload]);
    if (error) throw error;
    return data;
  },

  async updateEmergencyContact(id, { name, category, num }) {
    const { data, error } = await supabase
      .from('emergency_contacts')
      .update({
        name: name,
        scope: category,     
        phone_number: num   
      })
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  async deleteEmergencyContact(id) {
    const { data, error } = await supabase
      .from('emergency_contacts')
      .delete()
      .eq('id', id);
    if (error) throw error;
    return data;
  }
};