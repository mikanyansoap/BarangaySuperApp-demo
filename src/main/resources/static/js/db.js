import { createClient } from 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js/+esm';

const SUPABASE_URL = 'https://wjrabyrmhymwtvcjywea.supabase.co';
const SUPABASE_ANON_KEY = 'sb_publishable_eKmPItxbga4MB9Rn2JuMJw_04jCCvGE';

export const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

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

  // Dynamic user and barangay lookup from public.users
  async getUserProfile(userId) {
    const { data, error } = await supabase
      .from('users')
      .select('id, email, first_name, last_name, role, barangay_id, brgy_code, barangays(barangay_name)')
      .eq('id', userId)
      .maybeSingle();

    if (error) {
      console.warn("Could not fetch user profile from public.users:", error);
      return null;
    }
    return data;
  },

  async getReports(brgyCode = 'BRGY-001') {
    let query = supabase.from('reports').select('*');
    if (typeof brgyCode === 'number') {
      query = query.eq('barangay_id', brgyCode);
    } else if (brgyCode) {
      query = query.or(`brgy_code.eq.${brgyCode},barangay_id.eq.${brgyCode}`);
    }

    const { data, error } = await query.order('created_at', { ascending: false });
    if (error) {
      // Fallback if brgy column filter is unpopulated
      const { data: fallback, error: fbErr } = await supabase.from('reports').select('*').order('created_at', { ascending: false });
      if (fbErr) throw fbErr;
      return fallback || [];
    }
    return data || [];
  },

  async getReportById(id) {
    const { data, error } = await supabase
      .from('reports')
      .select('*')
      .eq('id', id)
      .single();
    if (error) throw error;
    return data;
  },

  async updateReport(id, updates) {
    const { data, error } = await supabase
      .from('reports')
      .update(updates)
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  async getApprovals(brgyCode = 'BRGY-001') {
    // If resident_approvals table exists, fetch from it; otherwise query unverified users
    const { data, error } = await supabase
      .from('resident_approvals')
      .select('*')
      .order('id', { ascending: false });

    if (error) {
      const { data: unverifiedUsers, error: userErr } = await supabase
        .from('users')
        .select('*')
        .eq('verification_status', 'pending');
      if (userErr) return [];
      return (unverifiedUsers || []).map(u => ({
        id: u.id,
        name: `${u.first_name || ''} ${u.last_name || ''}`.trim() || u.email,
        address: u.address || 'Address pending',
        idType: u.id_type || 'Valid ID',
        idNumber: u.id_number || 'N/A',
        idPhotoUrl: u.id_photo_url,
        date: u.created_at ? new Date(u.created_at).toLocaleDateString() : 'Recent'
      }));
    }

    return (data || []).map(a => ({
      ...a,
      name: a.full_name || a.name,
      idType: a.id_type || a.idType,
      idNumber: a.id_number || a.idNumber,
      date: a.date || 'Recent'
    }));
  },

  async updateApprovalStatus(id, status) {
    const { data, error } = await supabase
      .from('resident_approvals')
      .update({ status })
      .eq('id', id);
    if (error) {
      // Fallback updating user directly
      await supabase.from('users').update({ verification_status: status.toLowerCase() }).eq('id', id);
    }
    return data;
  },

  async getDocuments(brgyCode = 'BRGY-001') {
    const { data, error } = await supabase
      .from('document_requests')
      .select('*')
      .order('created_at', { ascending: false });
    if (error) throw error;
    return (data || []).map(d => ({
      ...d,
      name: d.resident_name || d.name || 'Resident',
      type: d.document_type || d.type || 'Barangay Document',
      pickup: d.pickup_date || d.pickup
    }));
  },

  async updateDocument(id, updates) {
    const { data, error } = await supabase
      .from('document_requests')
      .update(updates)
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  // Multi-tenant announcements fetch by brgy_code
  async getAnnouncements(brgyCode) {
    let query = supabase.from('announcements').select('*');
    if (brgyCode) {
      query = query.or(`brgy_code.eq.${brgyCode},barangay_id.eq.${brgyCode}`);
    }

    const { data, error } = await query.order('created_at', { ascending: false });
    if (error) throw error;

    const todayStr = new Date().toISOString().split('T')[0];

    return (data || []).map(a => {
      const isPastDate = a.event_date ? a.event_date < todayStr : false;
      const isArchived = Boolean((a.is_active === false) || a.is_archived || isPastDate);

      return {
        ...a,
        tag: a.category || a.type || 'General',
        description: a.body || a.description || '',
        posted: a.created_at ? new Date(a.created_at).toLocaleDateString() : 'Recent',
        isArchived: isArchived,
        is_archived: isArchived
      };
    });
  },

  async createAnnouncement({ title, description, body, eventDate, category, brgyCode, authorId, barangayId }) {
    const payload = {
      title,
      body: body || description,
      description: body || description,
      event_date: eventDate || null,
      category,
      type: category,
      is_active: true,
      is_archived: false
    };

    if (brgyCode) payload.brgy_code = String(brgyCode);
    if (barangayId) payload.barangay_id = barangayId;
    if (authorId) payload.author_id = authorId;

    const { data, error } = await supabase
      .from('announcements')
      .insert([payload]);
    if (error) throw error;
    return data;
  },

  async updateAnnouncement(id, { title, description, body, eventDate, category }) {
    const { data, error } = await supabase
      .from('announcements')
      .update({
        title,
        body: body || description,
        description: body || description,
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
      .update({ is_active: false, is_archived: true })
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  async unarchiveAnnouncement(id) {
    const { data, error } = await supabase
      .from('announcements')
      .update({ is_active: true, is_archived: false })
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  async getEmergencyContacts(brgyCode) {
    let query = supabase.from('emergency_contacts').select('*');
    if (brgyCode) {
      query = query.or(`brgy_code.eq.${brgyCode},barangay_id.eq.${brgyCode}`);
    }

    const { data, error } = await query.order('id', { ascending: true });
    if (error) throw error;
    return (data || []).map(c => ({
      ...c,
      num: c.phone_number || c.contact_number || c.num || c.number,
      number: c.phone_number || c.contact_number || c.num || c.number
    }));
  },

  async createEmergencyContact({ name, category, num, brgyCode, barangayId = 1 }) {
    const payload = {
      name,
      category,
      scope: category,
      phone_number: num,
      contact_number: num
    };
    if (brgyCode) payload.brgy_code = String(brgyCode);
    if (barangayId) payload.barangay_id = barangayId;

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
        name,
        category,
        scope: category,
        phone_number: num,
        contact_number: num
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