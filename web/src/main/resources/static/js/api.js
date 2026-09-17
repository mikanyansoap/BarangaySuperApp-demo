const API_BASE = window.location.origin + '/api';

export async function fetchReports() {
    const res = await fetch(`${API_BASE}/reports`);
    return await res.json();
}

export async function updateReportStatus(reportId, status, adminNotes = '') {
    const res = await fetch(`${API_BASE}/reports/${reportId}/status`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status, adminNotes })
    });
    return await res.json();
}

export async function fetchDocumentRequests() {
    const res = await fetch(`${API_BASE}/documents`);
    return await res.json();
}

export async function updateDocumentStatus(docId, status, pickupDate = null, adminRemarks = '') {
    const res = await fetch(`${API_BASE}/documents/${docId}/status`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status, pickupDate, adminRemarks })
    });
    return await res.json();
}