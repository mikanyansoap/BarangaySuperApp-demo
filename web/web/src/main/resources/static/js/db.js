import { initializeApp } from "https://www.gstatic.com/firebasejs/10.9.0/firebase-app.js";
import { 
    getAuth, 
    signInWithEmailAndPassword, 
    signOut, 
    onAuthStateChanged 
} from "https://www.gstatic.com/firebasejs/10.9.0/firebase-auth.js";

const firebaseConfig = {
  apiKey: "AIzaSyB4XP_dS7suaSnoFZW-_JWCE-oz-uhrC-w",
  authDomain: "brgysuperapp-admin.firebaseapp.com",
  projectId: "brgysuperapp-admin",
  storageBucket: "brgysuperapp-admin.firebasestorage.app",
  messagingSenderId: "650823276246",
  appId: "1:650823276246:web:7e6859f2970da07fe05b77",
  measurementId: "G-2Z05PYPVG6"
};

const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export { signInWithEmailAndPassword, signOut, onAuthStateChanged };

const DEFAULT_DB = {
  approvals: [
    { id: 1, name: "Maria Santos", address: "Purok 2, San Isidro", idType: "Barangay ID", idNumber: "BSI-2019-0212", date: "Sept 9" },
    { id: 2, name: "Carlo Reyes", address: "Purok 5, San Isidro", idType: "Driver's license", idNumber: "N01-23-456789", date: "Sept 8" },
    { id: 3, name: "Ella Tan", address: "Purok 1, San Isidro", idType: "Passport", idNumber: "P1234567A", date: "Sept 7" }
  ],
  reports: [
    { id: 101, title: "Blocked drainage, Purok 3", meta: "Juan Dela Cruz · Sept 7", priority: "high", status: "pending", category: "Drainage", desc: "Heavy clogging along Purok 3 main drainage after weekend storm.", bg: "brick-100", fg: "brick", notes: "" },
    { id: 102, title: "Noise disturbance, Rizal St.", meta: "Ana Reyes · Sept 6", priority: "medium", status: "progress", category: "Disturbance", desc: "Loud videoke and street party past 11 PM curfews.", bg: "gold-100", fg: "gold-600", notes: "Barangay Tanod deployed." },
    { id: 103, title: "Structural damage after storm", meta: "Pedro Cruz · Sept 5", priority: "high", status: "pending", category: "Other", desc: "Cracked perimeter wall near community chapel posing collapse risk.", bg: "brick-100", fg: "brick", notes: "" },
    { id: 104, title: "Uncollected garbage, Purok 1", meta: "Liza Gomez · Sept 4", priority: "low", status: "resolved", category: "Sanitation", desc: "Dumpster overflow along Purok 1 corner lot.", bg: "sage-100", fg: "#3E6552", notes: "Hauler picked up Sept 5." }
  ],
  documents: [
    { id: 201, type: "Barangay clearance", name: "Ana Reyes", date: "Sept 8", status: "pending", pickup: null },
    { id: 202, type: "Barangay ID — renewal", name: "Juan Dela Cruz", date: "Sept 8", status: "progress", pickup: "Sept 12" },
    { id: 203, type: "Certificate of indigency", name: "Mark Villanueva", date: "Sept 7", status: "progress", pickup: null },
    { id: 204, type: "Business permit endorsement", name: "Liza Gomez", date: "Sept 5", status: "resolved", pickup: "Sept 8" }
  ],
  announcements: [
    { id: 301, tag: "Health", title: "Free anti-rabies vaccination, Sept 14", posted: "Sept 7", day: 14 },
    { id: 302, tag: "Advisory", title: "Water interruption on Rizal St., Sept 11", posted: "Today", day: 11 },
    { id: 303, tag: "Event", title: "Barangay assembly, Sept 22, 6pm", posted: "Sept 5", day: 22 }
  ],
  pastAnnouncements: [
    { id: 304, title: "Feeding program results posted", tag: "Health", posted: "Aug 28" },
    { id: 305, title: "Road closure, Purok 2 bridge repair", tag: "Advisory", posted: "Aug 14" },
    { id: 306, title: "Founding anniversary celebration", tag: "Event", posted: "Jul 20" }
  ],
  emergency: [
    { id: 401, name: "Barangay hall", cat: "Barangay", num: "(02) 8XXX XXXX" },
    { id: 402, name: "Barangay captain", cat: "Barangay", num: "09XX XXX XXXX" },
    { id: 403, name: "Police / national emergency", cat: "National", num: "911" },
    { id: 404, name: "Bureau of Fire Protection", cat: "National", num: "(02) 8426 0219" }
  ]
};

const saved = localStorage.getItem('brgy_admin_db');
export const DB = saved ? JSON.parse(saved) : DEFAULT_DB;

export function saveDB() {
  localStorage.setItem('brgy_admin_db', JSON.stringify(DB));
}