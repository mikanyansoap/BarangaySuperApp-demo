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
      .select('*, barangays(name)')
      .eq('id', userId)
      .maybeSingle();

    if (error) {
      console.warn("Could not fetch profile from database:", error);
      return null;
    }
    return data;
  },

  async getReports(barangayId = 1) {
    const { data, error } = await supabase
      .from('reports')
      .select('*')
      .eq('barangay_id', barangayId)
      .order('id', { ascending: false });
    if (error) throw error;
    return data;
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

  async getApprovals(barangayId = 1) {
    const { data, error } = await supabase
      .from('resident_approvals')
      .select('*')
      .eq('barangay_id', barangayId)
      .order('id', { ascending: false });
    if (error) throw error;
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
    if (error) throw error;
    return data;
  },

  async getDocuments(barangayId = 1) {
    const { data, error } = await supabase
      .from('document_requests')
      .select('*')
      .eq('barangay_id', barangayId)
      .order('id', { ascending: false });
    if (error) throw error;
    return (data || []).map(d => ({
      ...d,
      name: d.resident_name || d.name,
      type: d.document_type || d.type,
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

  async getAnnouncements(barangayId = 1) {
    const { data, error } = await supabase
      .from('announcements')
      .select('*')
      .eq('barangay_id', barangayId)
      .order('id', { ascending: false });

    if (error) throw error;

    const todayStr = new Date().toISOString().split('T')[0];

    return (data || []).map(a => {
      const isPastDate = a.event_date ? a.event_date < todayStr : false;
      const isArchived = Boolean(a.is_archived || isPastDate);

      return {
        ...a,
        tag: a.category || a.tag,
        posted: a.created_at ? new Date(a.created_at).toLocaleDateString() : 'Recent',
        isArchived: isArchived,
        is_archived: isArchived
      };
    });
  },

  async createAnnouncement({ title, description, eventDate, category, barangayId = 1 }) {
    const { data, error } = await supabase
      .from('announcements')
      .insert([{
        barangay_id: barangayId,
        title,
        description,
        event_date: eventDate || null,
        category,
        is_archived: false
      }]);
    if (error) throw error;
    return data;
  },

  async updateAnnouncement(id, { title, description, eventDate, category }) {
    const { data, error } = await supabase
      .from('announcements')
      .update({
        title,
        description,
        event_date: eventDate || null,
        category
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

  async getEmergencyContacts(barangayId = 1) {
    const { data, error } = await supabase
      .from('emergency_contacts')
      .select('*')
      .eq('barangay_id', barangayId)
      .order('id', { ascending: true });
    if (error) throw error;
    return (data || []).map(c => ({
      ...c,
      num: c.contact_number || c.num || c.number,
      number: c.contact_number || c.num || c.number
    }));
  },

  async createEmergencyContact({ name, category, num, barangayId = 1 }) {
    const { data, error } = await supabase
      .from('emergency_contacts')
      .insert([{
        barangay_id: barangayId,
        name,
        category,
        contact_number: num
      }]);
    if (error) throw error;
    return data;
  },

  async updateEmergencyContact(id, { name, category, num }) {
    const { data, error } = await supabase
      .from('emergency_contacts')
      .update({
        name,
        category,
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