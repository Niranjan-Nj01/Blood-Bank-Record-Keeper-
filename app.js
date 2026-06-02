// HemaFlow Portal Logic
const API_BASE = 'http://localhost:5000/api';

// State Variables
let activeTab = 'dashboard';
let bloodGroups = [];
let donors = [];
let recipients = [];
let requests = [];
let stock = [];
let stats = {};

// Chart Reference
let stockChartInstance = null;

// Initialize Web App
document.addEventListener('DOMContentLoaded', () => {
    setupTabNavigation();
    setupFormSubmissions();
    setupQuickActions();
    setupDonorSearch();
    
    // Fetch blood groups first, then load the dashboard
    fetchBloodGroups().then(() => {
        loadAllData();
    });
});

// 1. Tab Switching
function setupTabNavigation() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', (e) => {
            e.preventDefault();
            const tabId = item.getAttribute('data-tab');
            switchTab(tabId);
        });
    });
}

function switchTab(tabId) {
    activeTab = tabId;
    
    // Update active nav state
    document.querySelectorAll('.nav-item').forEach(item => {
        if (item.getAttribute('data-tab') === tabId) {
            item.classList.add('active');
        } else {
            item.classList.remove('active');
        }
    });

    // Update active pane
    document.querySelectorAll('.tab-pane').forEach(pane => {
        if (pane.id === `tab-${tabId}`) {
            pane.classList.add('active');
        } else {
            pane.classList.remove('active');
        }
    });

    // Update headers
    const titleEl = document.getElementById('current-tab-title');
    const descEl = document.getElementById('current-tab-desc');

    if (tabId === 'dashboard') {
        titleEl.textContent = 'Analytics Dashboard';
        descEl.textContent = 'Real-time status of blood stocks, active donors and urgent requests.';
    } else if (tabId === 'donors') {
        titleEl.textContent = 'Donor Registry';
        descEl.textContent = 'Manage active blood donors, eligibility and check histories.';
    } else if (tabId === 'recipients') {
        titleEl.textContent = 'Recipients & Tickets';
        descEl.textContent = 'Create recipient profile and trace active blood tickets.';
    } else if (tabId === 'stock') {
        titleEl.textContent = 'Stock Depots & Alerts';
        descEl.textContent = 'Monitor current blood volumes, check thresholds and register stock.';
    }

    // Refresh data
    loadAllData();
}

// 2. Fetch Helper Functions
async function fetchBloodGroups() {
    try {
        const response = await fetch(`${API_BASE}/bloodgroups`);
        if (!response.ok) throw new Error('Failed to fetch blood groups');
        bloodGroups = await response.json();
        
        // Populate form selectors
        populateSelectors();
    } catch (err) {
        showToast('Error loading blood groups: ' + err.message, 'error');
    }
}

function populateSelectors() {
    const selectors = ['donor-bg', 'recipient-bg', 'stock-bg', 'request-bg'];
    selectors.forEach(id => {
        const select = document.getElementById(id);
        if (select) {
            select.innerHTML = bloodGroups.map(bg => 
                `<option value="${bg.BG_ID}">${bg.BloodGroup}</option>`
            ).join('');
        }
    });
}

async function loadAllData() {
    try {
        await Promise.all([
            fetchStats(),
            fetchDonors(),
            fetchRecipients(),
            fetchRequests(),
            fetchStock()
        ]);
        
        // Render UI
        renderDashboard();
        renderDonors(donors);
        renderRecipientsAndRequests();
        renderStockAndAlerts();
    } catch (err) {
        showToast('Connection failed. Make sure Java WebServer is running!', 'error');
    }
}

async function fetchStats() {
    const res = await fetch(`${API_BASE}/dashboard/stats`);
    stats = await res.json();
}

async function fetchDonors() {
    const res = await fetch(`${API_BASE}/donors`);
    donors = await res.json();
}

async function fetchRecipients() {
    const res = await fetch(`${API_BASE}/recipients`);
    recipients = await res.json();
    
    // Populate recipient selector in request form
    const reqSelect = document.getElementById('request-recipient');
    if (reqSelect) {
        reqSelect.innerHTML = recipients.map(r => 
            `<option value="${r.RecipientID}">${r.RecipientID} - ${r.Name} (${r.BloodGroup})</option>`
        ).join('');
    }
}

async function fetchRequests() {
    const res = await fetch(`${API_BASE}/requests`);
    requests = await res.json();
}

async function fetchStock() {
    const res = await fetch(`${API_BASE}/stock`);
    stock = await res.json();
}

// 3. Render Dashboard Elements
function renderDashboard() {
    document.getElementById('stat-donors').textContent = stats.totalDonors || 0;
    document.getElementById('stat-recipients').textContent = stats.totalRecipients || 0;
    document.getElementById('stat-requests').textContent = stats.totalRequests || 0;
    
    const qty = stats.totalStock ? parseFloat(stats.totalStock).toFixed(1) : '0.0';
    document.getElementById('stat-low-stock').textContent = stats.lowStockAlerts || 0;

    // Warning styling for low stock card
    const alertCard = document.getElementById('card-alert-count');
    if (stats.lowStockAlerts > 0) {
        alertCard.classList.add('warning');
    } else {
        alertCard.classList.remove('warning');
    }

    // Render stock chart
    renderChart(stats.stockByGroup);

    // Render Urgent Requests table (Top 5 requests)
    const reqTable = document.getElementById('recent-requests-table');
    if (!requests || requests.length === 0) {
        reqTable.innerHTML = `<tr><td colspan="7" class="loading-cell">No requests found.</td></tr>`;
        return;
    }

    const urgentReqs = requests.slice(0, 5);
    reqTable.innerHTML = urgentReqs.map(req => {
        const badgeClass = getStatusBadgeClass(req.Status);
        const actionButtons = getRequestActionButtons(req);
        return `
            <tr>
                <td><strong>#${req.RequestID}</strong></td>
                <td>${req.RecipientName}</td>
                <td><span class="badge badge-info">${req.BloodGroup}</span></td>
                <td>${parseFloat(req.QuantityRequested).toFixed(1)} L</td>
                <td><span class="badge ${badgeClass}">${req.Status}</span></td>
                <td>${req.FulfillmentDate || '—'}</td>
                <td><div class="action-buttons-cell">${actionButtons}</div></td>
            </tr>
        `;
    }).join('');

    // Generate critical alerts in the alerts panel
    generateAlertsPanel();
}

function getStatusBadgeClass(status) {
    switch (status.toLowerCase()) {
        case 'pending': return 'badge-warning';
        case 'approved': return 'badge-success';
        case 'rejected': return 'badge-danger';
        case 'consumed': return 'badge-consumed';
        default: return 'badge-info';
    }
}

function getRequestActionButtons(req) {
    if (req.Status.toLowerCase() === 'pending') {
        return `
            <button class="btn btn-sm btn-success" onclick="fulfillRequest(${req.RequestID}, 'Approve')">Approve</button>
            <button class="btn btn-sm btn-danger" onclick="fulfillRequest(${req.RequestID}, 'Reject')">Reject</button>
        `;
    } else if (req.Status.toLowerCase() === 'approved') {
        return `
            <button class="btn btn-sm btn-info" onclick="fulfillRequest(${req.RequestID}, 'Consume')">Consume/Deduct</button>
        `;
    }
    return `<span class="text-muted">No actions</span>`;
}

async function fulfillRequest(reqID, action) {
    try {
        const response = await fetch(`${API_BASE}/requests/fulfill`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ RequestID: reqID, Action: action })
        });
        
        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.error || 'Fulfillment error');
        }
        
        showToast(`Request #${reqID} successfully ${action}d!`, 'success');
        loadAllData();
    } catch (err) {
        showToast(err.message, 'error');
    }
}

// Draw premium chart using Chart.js
function renderChart(stockMap) {
    const ctx = document.getElementById('bloodStockChart').getContext('2d');
    
    // Sort keys alphabetically to keep layout consistent
    const labels = Object.keys(stockMap || {}).sort();
    const data = labels.map(label => stockMap[label]);

    // Create vibrant harmony theme
    const backgroundGradients = labels.map((_, i) => {
        return 'rgba(244, 63, 94, 0.6)'; // Red accent
    });

    if (stockChartInstance) {
        stockChartInstance.destroy();
    }

    stockChartInstance = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [{
                label: 'Volume Available (L)',
                data: data,
                backgroundColor: backgroundGradients,
                borderColor: '#f43f5e',
                borderWidth: 1.5,
                borderRadius: 8,
                barThickness: 28
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: false
                }
            },
            scales: {
                y: {
                    grid: {
                        color: 'rgba(255, 255, 255, 0.05)'
                    },
                    ticks: {
                        color: '#9ca3af',
                        font: { family: 'Inter', size: 11 }
                    },
                    suggestedMax: 20
                },
                x: {
                    grid: {
                        display: false
                    },
                    ticks: {
                        color: '#f3f4f6',
                        font: { family: 'Outfit', size: 12, weight: '600' }
                    }
                }
            }
        }
    });
}

function generateAlertsPanel() {
    const panel = document.getElementById('alerts-container');
    let alertsHtml = '';

    // 1. Check for critical stock deficits (< 10 L)
    const stockMap = stats.stockByGroup || {};
    let lowStockCount = 0;
    for (const [group, qty] of Object.entries(stockMap)) {
        if (qty < 10.0) {
            lowStockCount++;
            alertsHtml += `
                <div class="alert-item alert-danger">
                    <i class="fa-solid fa-triangle-exclamation"></i>
                    <div class="alert-text">
                        <h4>Stock Alert: ${group} Deficit</h4>
                        <p>Inventory level is critically low: <strong>${parseFloat(qty).toFixed(1)} L</strong>. Safe threshold is 10.0 L.</p>
                    </div>
                </div>
            `;
        }
    }

    // 2. Check for expiring stock items
    const today = new Date();
    let expiringCount = 0;
    stock.forEach(item => {
        const expDate = new Date(item.ExpiryDate);
        const timeDiff = expDate - today;
        const daysDiff = Math.ceil(timeDiff / (1000 * 60 * 60 * 24));
        
        if (daysDiff < 0) {
            alertsHtml += `
                <div class="alert-item alert-danger">
                    <i class="fa-solid fa-skull-crossbones"></i>
                    <div class="alert-text">
                        <h4>Stock Expired: Stock ID #${item.StockID}</h4>
                        <p>Group ${item.BloodGroup} (${item.QuantityAvailable}L) expired on ${item.ExpiryDate}. Please discard safely.</p>
                    </div>
                </div>
            `;
        } else if (daysDiff <= 3) {
            expiringCount++;
            alertsHtml += `
                <div class="alert-item alert-warning">
                    <i class="fa-solid fa-hourglass-half"></i>
                    <div class="alert-text">
                        <h4>Near Expiration Alert</h4>
                        <p>Stock ID #${item.StockID} (${item.BloodGroup}, ${item.QuantityAvailable}L) expires in ${daysDiff} days (${item.ExpiryDate}).</p>
                    </div>
                </div>
            `;
        }
    });

    if (alertsHtml === '') {
        alertsHtml = `
            <div class="alert-item alert-success">
                <i class="fa-solid fa-circle-check"></i>
                <div class="alert-text">
                    <h4>Stock Levels Normal</h4>
                    <p>All blood group levels are normal. No active alerts.</p>
                </div>
            </div>
        `;
    }

    panel.innerHTML = alertsHtml;
}

// 4. Render Donors Table
function renderDonors(data) {
    const tableBody = document.getElementById('donors-table');
    if (!data || data.length === 0) {
        tableBody.innerHTML = `<tr><td colspan="9" class="loading-cell">No donors matching search query.</td></tr>`;
        return;
    }

    tableBody.innerHTML = data.map(d => {
        const eligibilityBadge = d.EligibilityStatus === 'POSITIVE' 
            ? '<span class="badge badge-success">ELIGIBLE</span>' 
            : '<span class="badge badge-danger">INELIGIBLE</span>';
        
        return `
            <tr>
                <td><strong>#${d.DonorID}</strong></td>
                <td>${d.Name}</td>
                <td><span class="badge badge-info">${d.BloodGroup}</span></td>
                <td>${d.Gender === 'M' ? 'Male' : 'Female'}</td>
                <td>${d.DateOfBirth}</td>
                <td>${d.Phone}</td>
                <td>${d.Address}</td>
                <td>${d.LastDonationDate}</td>
                <td>${eligibilityBadge}</td>
            </tr>
        `;
    }).join('');
}

// 5. Render Recipients & Requests
function renderRecipientsAndRequests() {
    // Render Recipients
    const recBody = document.getElementById('recipients-table');
    if (!recipients || recipients.length === 0) {
        recBody.innerHTML = `<tr><td colspan="5" class="loading-cell">No registered patients.</td></tr>`;
    } else {
        recBody.innerHTML = recipients.map(r => `
            <tr>
                <td><strong>#${r.RecipientID}</strong></td>
                <td>${r.Name}</td>
                <td><span class="badge badge-info">${r.BloodGroup}</span></td>
                <td>${r.Phone}</td>
                <td>${r.RequestDate}</td>
            </tr>
        `).join('');
    }

    // Render Requests
    const reqBody = document.getElementById('requests-table');
    if (!requests || requests.length === 0) {
        reqBody.innerHTML = `<tr><td colspan="6" class="loading-cell">No active request tickets.</td></tr>`;
    } else {
        reqBody.innerHTML = requests.map(req => {
            const badgeClass = getStatusBadgeClass(req.Status);
            const actionButtons = getRequestActionButtons(req);
            return `
                <tr>
                    <td><strong>#${req.RequestID}</strong></td>
                    <td>${req.RecipientName}</td>
                    <td><span class="badge badge-info">${req.BloodGroup}</span></td>
                    <td>${parseFloat(req.QuantityRequested).toFixed(1)} L</td>
                    <td><span class="badge ${badgeClass}">${req.Status}</span></td>
                    <td><div class="action-buttons-cell">${actionButtons}</div></td>
                </tr>
            `;
        }).join('');
    }
}

// 6. Render Stock and Expiry Warnings
function renderStockAndAlerts() {
    const tableBody = document.getElementById('stock-table');
    if (!stock || stock.length === 0) {
        tableBody.innerHTML = `<tr><td colspan="5" class="loading-cell">Depot is empty. Add new stock details.</td></tr>`;
    } else {
        tableBody.innerHTML = stock.map(s => {
            const today = new Date();
            const expDate = new Date(s.ExpiryDate);
            let statusBadge = '<span class="badge badge-success">Active</span>';
            
            if (expDate < today) {
                statusBadge = '<span class="badge badge-danger">Expired</span>';
            } else if ((expDate - today) / (1000 * 60 * 60 * 24) <= 3) {
                statusBadge = '<span class="badge badge-warning">Near Expiry</span>';
            }

            return `
                <tr>
                    <td><strong>#${s.StockID}</strong></td>
                    <td><span class="badge badge-info">${s.BloodGroup}</span></td>
                    <td>${parseFloat(s.QuantityAvailable).toFixed(1)} Liters</td>
                    <td>${s.ExpiryDate}</td>
                    <td>${statusBadge}</td>
                </tr>
            `;
        }).join('');
    }

    // Populate alert notices in Stock Tab
    const alertsPanel = document.getElementById('stock-alert-list');
    const lowGroups = [];
    const stockMap = stats.stockByGroup || {};
    
    for (const [group, qty] of Object.entries(stockMap)) {
        if (qty < 10.0) {
            lowGroups.push({ group, qty });
        }
    }

    if (lowGroups.length === 0) {
        alertsPanel.innerHTML = `<p class="empty-alerts">All blood group quantities are above safe operational thresholds (10 Liters).</p>`;
    } else {
        alertsPanel.innerHTML = lowGroups.map(item => `
            <div class="stock-alert-item">
                <h4>
                    <span>Group ${item.group} Deficit</span>
                    <span class="badge badge-danger">${parseFloat(item.qty).toFixed(1)} L</span>
                </h4>
                <p>Urgent donation campaign recommended for ${item.group}. Stock is below safe levels.</p>
            </div>
        `).join('');
    }
}

// 7. Search Feature for Donors
function setupDonorSearch() {
    const searchInput = document.getElementById('donor-search');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            const query = e.target.value.toLowerCase().trim();
            if (!query) {
                renderDonors(donors);
                return;
            }

            const filtered = donors.filter(d => 
                d.Name.toLowerCase().includes(query) ||
                d.BloodGroup.toLowerCase().includes(query) ||
                d.EligibilityStatus.toLowerCase().includes(query) ||
                d.Address.toLowerCase().includes(query) ||
                d.Phone.includes(query)
            );
            renderDonors(filtered);
        });
    }
}

// 8. Quick actions bindings
function setupQuickActions() {
    document.getElementById('btn-quick-donate').addEventListener('click', () => {
        openModal('modal-donor');
    });
    document.getElementById('btn-quick-request').addEventListener('click', () => {
        openModal('modal-request');
    });
}

// 9. Forms Handling
function setupFormSubmissions() {
    // Form: Donor Submission
    document.getElementById('form-donor').addEventListener('submit', async (e) => {
        e.preventDefault();
        const payload = {
            DonorID: parseInt(document.getElementById('donor-id').value),
            BG_ID: parseInt(document.getElementById('donor-bg').value),
            Name: document.getElementById('donor-name').value,
            Gender: document.getElementById('donor-gender').value,
            DateOfBirth: document.getElementById('donor-dob').value,
            Phone: document.getElementById('donor-phone').value,
            Address: document.getElementById('donor-address').value,
            LastDonationDate: document.getElementById('donor-last-date').value,
            EligibilityStatus: document.getElementById('donor-eligibility').value
        };

        await submitRecord('donors', payload, 'modal-donor', 'form-donor');
    });

    // Form: Recipient Submission
    document.getElementById('form-recipient').addEventListener('submit', async (e) => {
        e.preventDefault();
        const payload = {
            RecipientID: parseInt(document.getElementById('recipient-id').value),
            BG_ID: parseInt(document.getElementById('recipient-bg').value),
            Name: document.getElementById('recipient-name').value,
            Phone: document.getElementById('recipient-phone').value,
            RequestDate: document.getElementById('recipient-date').value
        };

        await submitRecord('recipients', payload, 'modal-recipient', 'form-recipient');
    });

    // Form: Stock Submission
    document.getElementById('form-stock').addEventListener('submit', async (e) => {
        e.preventDefault();
        const payload = {
            StockID: parseInt(document.getElementById('stock-id').value),
            BG_ID: parseInt(document.getElementById('stock-bg').value),
            QuantityAvailable: parseFloat(document.getElementById('stock-qty').value),
            ExpiryDate: document.getElementById('stock-expiry').value
        };

        await submitRecord('stock', payload, 'modal-stock', 'form-stock');
    });

    // Form: Request Ticket Submission
    document.getElementById('form-request').addEventListener('submit', async (e) => {
        e.preventDefault();
        const payload = {
            RequestID: parseInt(document.getElementById('request-id').value),
            RecipientID: parseInt(document.getElementById('request-recipient').value),
            BG_ID: parseInt(document.getElementById('request-bg').value),
            QuantityRequested: parseFloat(document.getElementById('request-qty').value),
            Status: 'Pending',
            FulfillmentDate: ''
        };

        await submitRecord('requests', payload, 'modal-request', 'form-request');
    });
}

async function submitRecord(endpoint, payload, modalId, formId) {
    try {
        const response = await fetch(`${API_BASE}/${endpoint}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.error || 'Failed to submit data');
        }

        showToast('Record saved successfully!', 'success');
        closeModal(modalId);
        document.getElementById(formId).reset();
        
        // Refresh entire UI
        loadAllData();
    } catch (err) {
        showToast('Submission error: ' + err.message, 'error');
    }
}

// 10. Modals controllers
function openModal(id) {
    document.getElementById(id).classList.add('show');
}

function closeModal(id) {
    document.getElementById(id).classList.remove('show');
}

// Close modal on background click
window.onclick = function(event) {
    document.querySelectorAll('.modal').forEach(modal => {
        if (event.target === modal) {
            modal.classList.remove('show');
        }
    });
}

// 11. Custom Toast Notification System
function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    
    let icon = '<i class="fa-solid fa-circle-info"></i>';
    if (type === 'success') icon = '<i class="fa-solid fa-circle-check"></i>';
    else if (type === 'error') icon = '<i class="fa-solid fa-circle-exclamation"></i>';

    toast.innerHTML = `
        ${icon}
        <span>${message}</span>
    `;

    container.appendChild(toast);
    
    // Automatically delete toast from DOM after animation completes
    setTimeout(() => {
        toast.remove();
    }, 4000);
}
