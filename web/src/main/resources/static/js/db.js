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

  async getReports(psgcCode = 'BRGY-001') {
    const { data, error } = await supabase
      .from('requests')
      .select('*')
      .eq('psgc_code', psgcCode)
      .order('created_at', { ascending: false });

    if (error) throw error;
    return data || [];
  },

  async getReportById(id) {
    const { data, error } = await supabase
      .from('requests')
      .select('*')
      .eq('id', id)
      .single();
    if (error) throw error;
    return data;
  },

  async updateReport(id, updates) {
    const { data, error } = await supabase
      .from('requests')
      .update(updates)
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  async getApprovals(psgcCode = 'BRGY-001') {
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

  async getDocuments(psgcCode = 'BRGY-001') {
    const { data, error } = await supabase
      .from('requests')
      .select('*')
      .eq('psgc_code', psgcCode)
      .order('created_at', { ascending: false });
    if (error) throw error;
    return (data || []).map(d => ({
      ...d,
      name: d.requester_name || d.title || 'Resident',
      type: d.document_type || d.category || 'Barangay Document',
      pickup: d.pickup_date || ''
    }));
  },

  async updateDocument(id, updates) {
    const { data, error } = await supabase
      .from('requests')
      .update(updates)
      .eq('id', id);
    if (error) throw error;
    return data;
  },

  async getAnnouncements(psgcCode = 'BRGY-001') {
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

  async getEmergencyContacts(psgcCode = 'BRGY-001') {
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
      scope: category,       // Do NOT put 'category: category' here
      phone_number: num,     // Do NOT put 'contact_number: num' here
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