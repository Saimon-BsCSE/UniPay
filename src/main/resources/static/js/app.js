/* UniPay SPA controller — vanilla JS single-page app served by Spring Boot.
   UIU Campus Theme with SplitPay & Real-Time STOMP WebSockets. */
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const taka = (n) => '৳' + Number(n || 0).toLocaleString('en-US',
    { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const dayStamp = (d) =>
    d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
  const timeAgo = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    const s = Math.floor((Date.now() - d.getTime()) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + ' min ago';
    if (s < 86400) return Math.floor(s / 3600) + ' h ago';
    // Beyond a day this used to return the date, which duplicated the date already
    // present in fullTimestamp() wherever the two were printed together. Callers
    // that only use timeAgo() pass it through the absolute fallback below.
    return '';
  };

  /** timeAgo() for places that print a relative time with no absolute fallback. */
  const timeAgoOrDate = (iso) => timeAgo(iso) || (iso ? dayStamp(new Date(iso)) : '');
  const fullTimestamp = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    return dayStamp(d) +
      ' ' + d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: true });
  };

  let scanner = null;          // html5-qrcode instance
  let scanned = null;          // {vendorId, presetAmount, payload}
  let currentNonce = null;     // idempotency nonce reused across retries
  let qrMode = 'STATIC';

  /* ----------------------------------------------------------- OTP State (moved to top to avoid TDZ) */
  let otpState = {             // shared OTP session state
    otpId: null,
    nonce: null,
    timerInterval: null,
    secondsLeft: 0,
    verifying: false           // guard against double-submit
  };

  /* ----------------------------------------------------------- Smooth Number Animation */
  let lastUserBalance = null;
  let lastVendorBalance = null;
  let lastLoyaltyPoints = null;

  function animateNumber(element, startVal, endVal, duration = 600, prefix = '৳', decimals = 2) {
    if (!element) return;
    const start = Number(startVal || 0);
    const end = Number(endVal || 0);
    if (Math.abs(end - start) < 0.005) {
      element.textContent = prefix + end.toLocaleString('en-US', {
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals
      });
      return;
    }
    const startTime = performance.now();
    const diff = end - start;

    function step(now) {
      const elapsed = now - startTime;
      const progress = Math.min(elapsed / duration, 1);
      const ease = 1 - Math.pow(1 - progress, 3); // ease-out cubic
      const current = start + diff * ease;
      element.textContent = prefix + current.toLocaleString('en-US', {
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals
      });
      if (progress < 1) {
        requestAnimationFrame(step);
      } else {
        element.textContent = prefix + end.toLocaleString('en-US', {
          minimumFractionDigits: decimals,
          maximumFractionDigits: decimals
        });
      }
    }
    requestAnimationFrame(step);
  }

  /* ----------------------------------------------------------- SplitPay State */
  let splitMode = 'EVEN';           // 'EVEN' or 'CUSTOM'
  let splitParticipants = [];       // [{ id, name, customAmount }]
  let currentSplitTab = 'requests'; // 'requests' or 'bills'

  /* ------------------------------------------------------------ auth view */
  function switchTab(tab) {
    const isLogin = tab === 'login';
    const activeForm = $(isLogin ? 'form-login' : 'form-register');
    const inactiveForm = $(isLogin ? 'form-register' : 'form-login');

    inactiveForm.classList.add('hidden');
    inactiveForm.classList.remove('tab-fade-enter');

    activeForm.classList.remove('hidden');
    activeForm.classList.add('tab-fade-enter');

    $('tab-login').classList.toggle('active', isLogin);
    $('tab-register').classList.toggle('active', !isLogin);
    hideError('login-error');
    hideError('register-error');
  }

  async function doLogin(e) {
    if (e) e.preventDefault();
    hideError('login-error');
    const idVal = $('login-id').value.trim();
    const passVal = $('login-pass').value;

    if (!idVal) {
      showError('login-error', 'Please enter your UIU Student / Faculty / Vendor ID.');
      $('login-id').focus();
      return;
    }
    if (!passVal) {
      showError('login-error', 'Please enter your password.');
      $('login-pass').focus();
      return;
    }

    const btn = document.querySelector('#form-login button[type="submit"]');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = '<span class="btn-spinner"></span> Logging in…';
    }
    try {
      const res = await API.call('/api/auth/login', {
        method: 'POST',
        body: { userId: idVal, password: passVal }
      });
      API.saveSession(res.token, res.user);
      boot();
    } catch (err) {
      showError('login-error', err.message);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = origHtml;
      }
    }
  }

  async function doRegister(e) {
    if (e) e.preventDefault();
    hideError('register-error');

    const idVal = $('reg-id').value.trim();
    const nameVal = $('reg-name').value.trim();
    const phoneVal = $('reg-phone').value.trim();
    const passVal = $('reg-pass').value;
    const role = $('reg-role').value;

    if (!idVal) {
      showError('register-error', 'Please enter your University ID.');
      $('reg-id').focus();
      return;
    }
    if (!phoneVal) {
      showError('register-error', 'Please enter your 11-digit mobile number.');
      $('reg-phone').focus();
      return;
    }
    if (!/^01[3-9]\d{8}$/.test(phoneVal)) {
      showError('register-error', 'Mobile number must be an 11-digit BD number (e.g. 01712345678).');
      $('reg-phone').focus();
      return;
    }
    if (!nameVal) {
      showError('register-error', 'Please enter your full name.');
      $('reg-name').focus();
      return;
    }
    if (!passVal || passVal.length < 6) {
      showError('register-error', 'Password must be at least 6 characters.');
      $('reg-pass').focus();
      return;
    }

    const body = {
      userId: idVal,
      fullName: nameVal,
      phoneNumber: phoneVal,
      password: passVal,
      role: role
    };

    if (role === 'VENDOR') {
      const stallVal = $('reg-stall').value.trim();
      if (!stallVal) {
        showError('register-error', 'Please enter your stall name.');
        $('reg-stall').focus();
        return;
      }
      body.stallName = stallVal;
      body.stallCategory = $('reg-category').value;
    }

    const btn = document.querySelector('#form-register button[type="submit"]');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = '<span class="btn-spinner"></span> Creating Account…';
    }
    try {
      const res = await API.call('/api/auth/register', { method: 'POST', body });
      API.saveSession(res.token, res.user);
      boot();
    } catch (err) {
      showError('register-error', err.message);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = origHtml;
      }
    }
  }

  /* ------------------------------------------------------------- app view */
  function boot() {
    if (!API.token || !API.user) { showAuth(); return; }
    $('view-auth').classList.add('hidden');
    $('view-auth').classList.remove('view-enter');
    const appView = $('view-app');
    appView.classList.remove('hidden');
    appView.classList.remove('view-enter');
    void appView.offsetWidth;
    appView.classList.add('view-enter');

    $('topbar-name').textContent = API.user.fullName;
    $('topbar-id').textContent = API.user.userId + ' · ' + formatRole(API.user.role);
    updateSoundUI();
    renderTopbarAvatar(API.user.avatarUrl);
    loadNotifications();

    const isVendor = API.user.role === 'VENDOR';
    $('dash-user').classList.toggle('hidden', isVendor);
    $('dash-vendor').classList.toggle('hidden', !isVendor);

    if (isVendor) {
      initVendor3DCard();
    } else {
      init3DCard();
    }

    WS.start(onWsEvent, onWsState);
    WS.pollTick = isVendor ? refreshVendorData : refreshUserData;
    if (isVendor) {
      refreshVendorData();
    } else {
      refreshUserData();
      refreshSplitData();
    }
  }

  function formatRole(role) {
    return { STUDENT: 'Student', FACULTY: 'Faculty', STAFF: 'Staff', VENDOR: 'Campus Vendor' }[role] || role;
  }

  function showAuth() {
    $('view-app').classList.add('hidden');
    $('view-app').classList.remove('view-enter');
    const authView = $('view-auth');
    authView.classList.remove('hidden');
    authView.classList.remove('view-enter');
    void authView.offsetWidth;
    authView.classList.add('view-enter');
  }

  function logout() {
    lastUserBalance = null;
    lastVendorBalance = null;
    lastLoyaltyPoints = null;
    WS.stop();
    API.clearSession();
    showAuth();
  }

  function onWsState(connected) {
    const badge = $('ws-badge');
    badge.textContent = connected ? '● live' : '● offline';
    badge.className = 'text-[11px] px-2 py-0.5 rounded-full font-bold ' +
      (connected ? 'bg-emerald-600 text-white' : 'bg-slate-800 text-slate-400');
  }

  function onWsEvent(event) {
    if (!event || !event.type) return;

    // Push into notification center history with current timestamp
    const nowIso = new Date().toISOString();
    const newNotif = {
      id: Date.now(),
      type: event.type,
      title: event.title,
      message: event.message,
      amount: event.amount,
      senderName: event.senderName,
      transactionId: event.transactionId,
      isRead: false,
      createdAt: nowIso,
      timeAgo: 'Just now'
    };
    notificationsList.unshift(newNotif);
    const unreadCount = notificationsList.filter(n => !n.isRead).length;
    updateNotifBadge(unreadCount);
    renderNotifications(notificationsList);
    if (event.type === 'VENDOR_PAYMENT') {
      showPosBanner(event);
      Sound.vendorPayment();
      refreshVendorData();
    } else if (event.type === 'P2P_RECEIVED' || event.type === 'CASH_IN_COMPLETED') {
      toast('💰 ' + event.title, event.message, false);
      Sound.cash();
      if (API.user.role === 'VENDOR') refreshVendorData(); else refreshUserData();
    } else if (event.type === 'SPLIT_REQUEST') {
      toast('🍽️ ' + event.title, event.message, false);
      Sound.splitRequest();
      refreshUserData();
      refreshSplitData();
    } else if (event.type === 'SPLIT_ACCEPTED') {
      toast('🎉 ' + event.title, event.message, false);
      Sound.splitSuccess();
      refreshUserData();
      refreshSplitData();
    } else if (event.type === 'SPLIT_DECLINED') {
      toast('⚠️ ' + event.title, event.message, true);
      Sound.error();
      refreshSplitData();
    } else if (event.type === 'SPLIT_SETTLED') {
      toast('🎉 ' + event.title, event.message, false);
      Sound.splitSuccess();
      refreshUserData();
      refreshSplitData();
    }
  }

  /* ---------------------------------------------------------- user panels */
  async function refreshUserData() {
    try {
      const [wallet, history] = await Promise.all([
        API.call('/api/wallet'),
        API.call('/api/wallet/transactions?page=0&size=25')
      ]);
      currentRawBalance = Number(wallet.balance);
      if (isBalanceHidden) {
        $('user-balance').textContent = '••••••';
      } else {
        animateNumber($('user-balance'), lastUserBalance ?? wallet.balance, wallet.balance, 650, '৳', 2);
      }
      lastUserBalance = Number(wallet.balance);
      $('send-balance').textContent = taka(wallet.balance);

      if ($('card-holder-name') && API.user) {
        $('card-holder-name').textContent = API.user.fullName || 'UIU MEMBER';
      }
      update3DCardPAN(wallet.userId);
      update3DCardBack(API.user ? API.user.phoneNumber : null);

      // Update loyalty points widget with smooth count-up
      const pts = Number(wallet.loyaltyPoints || 0);
      animateNumber($('loyalty-points-display'), lastLoyaltyPoints ?? pts, pts, 650, '', 2);
      lastLoyaltyPoints = pts;
      const redeemBtn = $('btn-redeem-points');
      if (redeemBtn) {
        const canRedeem = pts >= 100;
        redeemBtn.disabled = !canRedeem;
        redeemBtn.title = !canRedeem
          ? `Earn ${Math.max(0, 100 - pts).toFixed(2)} more coins to unlock redemption (need 100)`
          : `Redeem ${pts.toFixed(2)} coins for ৳${pts.toFixed(2)}`;
      }

      const roleBadge = $('user-role-badge');
      if (wallet.role === 'STUDENT') roleBadge.textContent = '🎓 Verified UIU Student';
      else if (wallet.role === 'FACULTY') roleBadge.textContent = '👨‍🏫 Verified UIU Faculty';
      else if (wallet.role === 'STAFF') roleBadge.textContent = '🏢 Verified UIU Staff';
      else roleBadge.textContent = '🏪 Campus Vendor';

      $('user-role-note').textContent =
        'UIU ID: ' + wallet.userId + ' · Verified Role: ' + formatRole(wallet.role);

      renderHistory('user-history', history);
      loadExpenseTracker(currentUserExpensePeriod);
    } catch (err) { if (err.status !== 401) toast('Error', err.message, true); }
  }

  /* ------------------------------------------------------------------
   * Collapsible history lists
   * ------------------------------------------------------------------
   * Every history list (Recent Transactions, Payments Received, Cash-Out
   * History) previews only the 4 most recent entries. The button beside
   * each refresh control toggles between that preview and the complete
   * history, so one button both opens and closes full history.
   *
   * Rows are cached per list, so toggling is instant and costs no request,
   * and the expanded/collapsed choice survives re-renders -- refreshing
   * never silently collapses a list the user deliberately opened.
   */
  const HISTORY_PREVIEW_COUNT = 4;
  const historyExpanded = {};
  const historyCache = {};
  const HISTORY_TOGGLE_BTN = {
    'user-history': 'btn-toggle-history',
    'vendor-history': 'btn-toggle-vhistory',
    'vendor-cashout-list': 'btn-toggle-cashouts'
  };

  function historyRows(data) {
    const rows = Array.isArray(data) ? data : (data && Array.isArray(data.content) ? data.content : []);
    return Array.isArray(rows) ? rows : [];
  }

  /** Stores freshly loaded rows for a list, then repaints it. */
  function paintHistory(containerId, rowHtmls, emptyHtml) {
    historyCache[containerId] = { rowHtmls, emptyHtml, total: rowHtmls.length };
    repaintHistory(containerId);
  }

  /** Repaints a list from cache, honouring the 4-row preview / full toggle. */
  function repaintHistory(containerId) {
    const box = $(containerId);
    if (!box) return;
    const cache = historyCache[containerId];
    if (!cache) return;
    if (!cache.total) {
      box.innerHTML = cache.emptyHtml;
    } else {
      const rows = historyExpanded[containerId]
        ? cache.rowHtmls
        : cache.rowHtmls.slice(0, HISTORY_PREVIEW_COUNT);
      box.innerHTML = rows.join('');
    }
    syncHistoryToggle(containerId);
  }

  function syncHistoryToggle(containerId) {
    const btnId = HISTORY_TOGGLE_BTN[containerId];
    const btn = btnId ? $(btnId) : null;
    if (!btn) return;
    const total = (historyCache[containerId] || {}).total || 0;
    const expanded = !!historyExpanded[containerId];
    // Nothing to reveal when the whole list already fits inside the preview.
    btn.classList.toggle('hidden', total <= HISTORY_PREVIEW_COUNT);
    btn.setAttribute('aria-expanded', String(expanded));
    btn.textContent = expanded
      ? 'close full history'
      : 'view full history (' + total + ')';
  }

  function toggleHistoryFull(containerId) {
    const total = (historyCache[containerId] || {}).total || 0;
    if (total <= HISTORY_PREVIEW_COUNT) return;
    historyExpanded[containerId] = !historyExpanded[containerId];
    repaintHistory(containerId);
  }

  function renderHistory(containerId, data) {
    const box = $(containerId);
    if (!box) return;
    paintHistory(
      containerId,
      historyRows(data).map(txnRowHtml),
      '<p class="text-sm text-slate-400 py-6 text-center">No transactions yet.</p>'
    );
  }

  function txnRowHtml(t) {
    const incoming = t.direction === 'IN';
    let icon = incoming ? '⬇️' : '⬆️';
    let name = t.counterpartyName || '';
    const ts = fullTimestamp(t.timestamp);
    const ago = timeAgo(t.timestamp);
    let sub = (ago ? ago + ' · ' : '') + (ts || '') + ' · ' + txnLabel(t.type);
    if (t.type === 'MFS_CASH_IN') { icon = '🏦'; name = 'MFS Cash-In'; }
    if (t.type === 'VENDOR_PAYMENT') icon = incoming ? '🏪' : '🛍️';
    if (t.type === 'P2P_TRANSFER') icon = incoming ? '📥' : '📤';
    if (t.type === 'SPLIT_PAY') icon = incoming ? '🍕' : '🧾';
    if (t.type === 'VENDOR_CASHOUT') { icon = '💸'; name = name || 'Cash-Out'; }
    if (t.type === 'LOYALTY_REDEMPTION') { icon = '⭐'; name = 'Loyalty Redemption'; }
    return `
      <div class="txn-row">
        <div class="txn-ico ${incoming ? 'in' : 'out'}">${icon}</div>
        <div class="txn-meta">
          <div class="txn-name">${esc(name || 'Campus Transaction')}</div>
          <div class="txn-sub">${esc(sub)}${t.counterpartyId && t.type !== 'MFS_CASH_IN' ? ' · ' + esc(t.counterpartyId) : ''}</div>
        </div>
        <div class="txn-amt ${incoming ? 'in' : 'out'}">${incoming ? '+' : '−'}${taka(t.amount)}</div>
      </div>`;
  }

  function txnLabel(type) {
    return {
      MFS_CASH_IN: 'Top-up',
      VENDOR_PAYMENT: 'Vendor payment',
      P2P_TRANSFER: 'Transfer',
      SPLIT_PAY: 'SplitPay',
      LOYALTY_REDEMPTION: 'Loyalty Redemption'
    }[type] || type;
  }

  /* ==================== EXPENSE & EARNINGS TRACKER ======================= */
  let currentUserExpensePeriod = 'WEEKLY';
  let currentVendorExpensePeriod = 'WEEKLY';

  async function loadExpenseTracker(period) {
    const chartEl = $('expense-chart-user');
    if (!chartEl) return;
    try {
      const summary = await API.call('/api/wallet/expense-summary?period=' + (period || currentUserExpensePeriod));
      const totalNum = Number(summary.total ?? summary.totalSpent ?? 0);
      if ($('expense-total')) $('expense-total').textContent = taka(totalNum);

      const points = summary.points || [];
      const avgNum = points.length ? (totalNum / points.length) : 0;
      if ($('expense-avg')) $('expense-avg').textContent = taka(avgNum);

      let peak = 0;
      points.forEach(p => {
        const val = Number(p.amount || 0);
        if (val > peak) peak = val;
      });
      if ($('expense-peak')) $('expense-peak').textContent = taka(peak);

      if (!points.length) {
        chartEl.innerHTML = '<p class="text-sm text-slate-400 py-10 text-center">No expense activity recorded for this period.</p>';
        return;
      }

      const maxVal = Math.max(peak, 1);
      const isSingle = points.length === 1;

      const barsHtml = points.map((p, idx) => {
        const amt = Number(p.amount || 0);
        const isZero = amt === 0;
        const heightPct = isZero ? 4 : Math.max(8, Math.min(100, Math.round((amt / maxVal) * 100)));
        const d = new Date(p.date + 'T00:00:00Z');
        let label = '';
        if (points.length <= 1) {
          label = 'Today';
        } else if (points.length <= 7) {
          label = d.toLocaleDateString('en-US', { weekday: 'short', timeZone: 'UTC' });
        } else {
          label = d.getUTCDate();
        }
        const fullDate = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
        return `
          <div class="expense-bar-col ${isSingle ? 'max-w-[120px] mx-auto' : ''}" title="${esc(fullDate)}: ${taka(amt)}">
            <div class="expense-bar-tooltip">${esc(fullDate)}: ${taka(amt)}</div>
            <div class="expense-bar expense-bar-user ${isZero ? 'bar-zero' : ''}" style="height: ${heightPct}%;"></div>
            <div class="expense-bar-label">${label}</div>
          </div>
        `;
      }).join('');

      chartEl.innerHTML = `<div class="expense-chart-bars">${barsHtml}</div>`;
    } catch (err) {
      if (err.status !== 401) {
        chartEl.innerHTML = '<p class="text-sm text-slate-400 py-6 text-center">Could not load expense chart.</p>';
      }
    }
  }

  function setExpensePeriod(period) {
    currentUserExpensePeriod = period;
    document.querySelectorAll('#expense-period-toggle .expense-period-btn').forEach(btn => {
      const active = btn.dataset.period === period;
      btn.classList.toggle('active-period', active);
      btn.classList.toggle('text-slate-500', !active);
    });
    loadExpenseTracker(period);
  }

  async function loadVendorExpenseTracker(period) {
    const chartEl = $('expense-chart-vendor');
    if (!chartEl) return;
    try {
      const summary = await API.call('/api/wallet/expense-summary?period=' + (period || currentVendorExpensePeriod));
      const totalNum = Number(summary.total ?? summary.totalSpent ?? 0);
      if ($('vendor-expense-total')) $('vendor-expense-total').textContent = taka(totalNum);

      const points = summary.points || [];
      const avgNum = points.length ? (totalNum / points.length) : 0;
      if ($('vendor-expense-avg')) $('vendor-expense-avg').textContent = taka(avgNum);

      let peak = 0;
      points.forEach(p => {
        const val = Number(p.amount || 0);
        if (val > peak) peak = val;
      });
      if ($('vendor-expense-peak')) $('vendor-expense-peak').textContent = taka(peak);

      if (!points.length) {
        chartEl.innerHTML = '<p class="text-sm text-slate-400 py-10 text-center">No sales revenue recorded for this period.</p>';
        return;
      }

      const maxVal = Math.max(peak, 1);
      const isSingle = points.length === 1;

      const barsHtml = points.map((p, idx) => {
        const amt = Number(p.amount || 0);
        const isZero = amt === 0;
        const heightPct = isZero ? 4 : Math.max(8, Math.min(100, Math.round((amt / maxVal) * 100)));
        const d = new Date(p.date + 'T00:00:00Z');
        let label = '';
        if (points.length <= 1) {
          label = 'Today';
        } else if (points.length <= 7) {
          label = d.toLocaleDateString('en-US', { weekday: 'short', timeZone: 'UTC' });
        } else {
          label = d.getUTCDate();
        }
        const fullDate = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
        return `
          <div class="expense-bar-col ${isSingle ? 'max-w-[120px] mx-auto' : ''}" title="${esc(fullDate)}: ${taka(amt)}">
            <div class="expense-bar-tooltip">${esc(fullDate)}: ${taka(amt)}</div>
            <div class="expense-bar expense-bar-vendor ${isZero ? 'bar-zero' : ''}" style="height: ${heightPct}%;"></div>
            <div class="expense-bar-label">${label}</div>
          </div>
        `;
      }).join('');

      chartEl.innerHTML = `<div class="expense-chart-bars">${barsHtml}</div>`;
    } catch (err) {
      if (err.status !== 401) {
        chartEl.innerHTML = '<p class="text-sm text-slate-400 py-6 text-center">Could not load earnings chart.</p>';
      }
    }
  }

  function setVendorExpensePeriod(period) {
    currentVendorExpensePeriod = period;
    document.querySelectorAll('#vendor-expense-period-toggle .vendor-expense-period-btn').forEach(btn => {
      const active = btn.dataset.period === period;
      btn.classList.toggle('active-period-vendor', active);
      btn.classList.toggle('text-slate-500', !active);
    });
    loadVendorExpenseTracker(period);
  }

  /* ----------------------------------------------------- SplitPay Functionality */
  function switchSplitTab(tab) {
    currentSplitTab = tab;
    $('tab-split-requests').className = (tab === 'requests'
      ? 'px-3.5 py-1.5 rounded-xl text-xs font-bold transition bg-brand-50 text-brand-700 border border-brand-200 flex items-center gap-1.5 shadow-sm'
      : 'px-3.5 py-1.5 rounded-xl text-xs font-bold transition bg-slate-100 text-slate-600 hover:bg-slate-200 flex items-center gap-1.5');

    $('tab-split-bills').className = (tab === 'bills'
      ? 'px-3.5 py-1.5 rounded-xl text-xs font-bold transition bg-brand-50 text-brand-700 border border-brand-200 flex items-center gap-1.5 shadow-sm'
      : 'px-3.5 py-1.5 rounded-xl text-xs font-bold transition bg-slate-100 text-slate-600 hover:bg-slate-200 flex items-center gap-1.5');

    const inBox = $('split-incoming-box');
    const outBox = $('split-outgoing-box');

    if (tab === 'requests') {
      outBox.classList.add('hidden');
      outBox.classList.remove('tab-fade-enter');
      inBox.classList.remove('hidden');
      inBox.classList.add('tab-fade-enter');
    } else {
      inBox.classList.add('hidden');
      inBox.classList.remove('tab-fade-enter');
      outBox.classList.remove('hidden');
      outBox.classList.add('tab-fade-enter');
    }
  }

  async function refreshSplitData() {
    try {
      const [requests, bills, countRes] = await Promise.all([
        API.call('/api/splitpay/my-requests'),
        API.call('/api/splitpay/my-bills'),
        API.call('/api/splitpay/pending-count')
      ]);

      const pendingCount = countRes.pendingCount || 0;
      const badge = $('btn-split-badge');
      const badgeCount = $('split-badge-count');
      const tabReqCount = $('tab-req-count');

      if (pendingCount > 0) {
        badge.classList.remove('hidden');
        badgeCount.textContent = pendingCount;
        tabReqCount.classList.remove('hidden');
        tabReqCount.textContent = pendingCount;
      } else {
        badge.classList.add('hidden');
        tabReqCount.classList.add('hidden');
      }

      renderSplitRequests(requests);
      renderSplitBills(bills);
    } catch (err) {
      // ignore 401
    }
  }

  function renderSplitRequests(requests) {
    const list = $('split-incoming-list');
    if (!requests || !requests.length) {
      list.innerHTML = '<p class="text-sm text-slate-400 py-6 text-center">No incoming split requests for you.</p>';
      return;
    }

    list.innerHTML = requests.map(req => {
      const isPending = req.status === 'PENDING';
      const isAccepted = req.status === 'ACCEPTED';
      const statusBadge = isPending
        ? '<span class="badge-pill badge-pending">⏳ Pending Payment</span>'
        : (isAccepted
          ? '<span class="badge-pill badge-accepted">✅ Paid</span>'
          : '<span class="badge-pill badge-declined">❌ Declined</span>');

      return `
        <div class="p-4 rounded-2xl border ${isPending ? 'border-brand-200 bg-brand-50/20' : 'border-slate-100 bg-white'} shadow-sm space-y-3">
          <div class="flex items-start justify-between gap-2">
            <div>
              <div class="font-extrabold text-slate-900 text-sm flex items-center gap-2">
                <span>🍽️</span> ${esc(req.billTitle)}
              </div>
              <div class="text-xs text-slate-500 mt-0.5">
                Requested by <strong>${esc(req.creatorName)}</strong> (${esc(req.creatorId)}) · ${timeAgoOrDate(req.createdAt)}
              </div>
              ${req.note ? `<div class="text-xs text-slate-600 bg-slate-50 px-2 py-1 rounded-lg mt-1 inline-block">"${esc(req.note)}"</div>` : ''}
            </div>
            ${statusBadge}
          </div>

          <div class="flex items-center justify-between pt-1 border-t border-slate-100">
            <div>
              <span class="text-[11px] text-slate-400 font-semibold uppercase block">Your Share</span>
              <span class="text-lg font-black text-brand-600">${taka(req.myShare)}</span>
              <span class="text-[11px] text-slate-400 ml-1">(Total Bill: ${taka(req.totalAmount)})</span>
            </div>

            ${isPending ? `
              <div class="flex gap-2">
                <button class="btn-danger py-1.5 px-3 text-xs" onclick="window.UniPaySplit.decline('${esc(req.requestId)}')">
                  Decline
                </button>
                <button class="btn-success py-1.5 px-4 text-xs font-bold" onclick="window.UniPaySplit.accept('${esc(req.requestId)}')">
                  Accept &amp; Pay
                </button>
              </div>
            ` : (isAccepted ? `
              <div class="text-[11px] text-slate-400 font-medium">Paid ${timeAgoOrDate(req.paidAt)}</div>
            ` : '')}
          </div>
        </div>
      `;
    }).join('');
  }

  function renderSplitBills(bills) {
    const list = $('split-outgoing-list');
    if (!bills || !bills.length) {
      list.innerHTML = '<p class="text-sm text-slate-400 py-6 text-center">You haven\'t split any bills yet.</p>';
      return;
    }

    list.innerHTML = bills.map(bill => {
      const isSettled = bill.status === 'SETTLED';
      const isCancelled = bill.status === 'CANCELLED';
      const statusBadge = isSettled
        ? '<span class="badge-pill badge-settled">🎉 All Paid &amp; Settled</span>'
        : (isCancelled
          ? '<span class="badge-pill badge-declined">Cancelled</span>'
          : '<span class="badge-pill badge-active">Active Split</span>');

      const totalParticipants = bill.participants ? bill.participants.length : 0;
      const paidParticipants = bill.participants ? bill.participants.filter(p => p.status === 'ACCEPTED').length : 0;
      const percent = bill.totalAmount > 0 ? Math.min(100, Math.round(((Number(bill.creatorShare || 0) + Number(bill.collectedAmount || 0)) / Number(bill.totalAmount)) * 100)) : 0;

      return `
        <div class="p-4 rounded-2xl border border-slate-100 bg-white shadow-sm space-y-3">
          <div class="flex items-start justify-between gap-2">
            <div>
              <div class="font-extrabold text-slate-900 text-sm flex items-center gap-2">
                <span>🧾</span> ${esc(bill.title)}
                <span class="text-[10px] px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 font-bold uppercase">${esc(bill.splitType)}</span>
              </div>
              <div class="text-xs text-slate-500 mt-0.5">
                Created ${timeAgoOrDate(bill.createdAt)} · Total: <strong>${taka(bill.totalAmount)}</strong>
              </div>
            </div>
            ${statusBadge}
          </div>

          <!-- Progress bar -->
          <div class="space-y-1">
            <div class="flex justify-between text-xs text-slate-600 font-semibold">
              <span>Collected: <strong class="text-brand-600">${taka(bill.collectedAmount)}</strong> (from friends)</span>
              <span>${paidParticipants}/${totalParticipants} friends paid (${percent}%)</span>
            </div>
            <div class="split-progress-bg">
              <div class="split-progress-fill" style="width: ${percent}%;"></div>
            </div>
          </div>

          <!-- Participant chips list -->
          <div class="pt-1 border-t border-slate-100">
            <div class="text-[11px] text-slate-400 font-semibold mb-1.5 uppercase">Participant Breakdown:</div>
            <div class="flex flex-wrap gap-2 text-xs">
              <div class="px-2.5 py-1 rounded-xl bg-slate-100 text-slate-700 font-semibold">
                You (Creator): ${taka(bill.creatorShare)} · Paid
              </div>
              ${(bill.participants || []).map(p => {
                const pPaid = p.status === 'ACCEPTED';
                const pDeclined = p.status === 'DECLINED';
                const bg = pPaid ? 'bg-emerald-50 text-emerald-800 border-emerald-200'
                  : (pDeclined ? 'bg-red-50 text-red-800 border-red-200' : 'bg-amber-50 text-amber-800 border-amber-200');
                const ico = pPaid ? '✅' : (pDeclined ? '❌' : '⏳');
                return `
                  <div class="px-2.5 py-1 rounded-xl border ${bg} font-semibold flex items-center gap-1">
                    <span>${ico}</span>
                    <span>${esc(p.participantName || p.participantId)}: <strong>${taka(p.amount)}</strong></span>
                  </div>
                `;
              }).join('')}
            </div>
          </div>

          ${bill.status === 'ACTIVE' ? `
            <div class="flex justify-end pt-1">
              <button class="text-xs text-slate-400 hover:text-red-600 font-semibold transition" onclick="window.UniPaySplit.cancel('${esc(bill.billId)}')">
                Cancel bill
              </button>
            </div>
          ` : ''}
        </div>
      `;
    }).join('');
  }

  /* ----------------------------------------------------- SplitPay Modal Wizard */
  function openSplitModal() {
    splitMode = 'EVEN';
    splitParticipants = [];
    $('split-title').value = '';
    $('split-total').value = '';
    $('split-note').value = '';
    $('split-friend-input').value = '';
    // Hide distribute-even btn (only visible in CUSTOM mode)
    const distributeBtn = $('btn-distribute-even');
    if (distributeBtn) distributeBtn.classList.add('hidden');
    setSplitMode('EVEN');
    renderSplitParticipants();
    hideError('split-error');
    openModal('modal-splitpay');
  }

  function setSplitMode(mode) {
    const prevMode = splitMode;
    splitMode = mode;
    $('split-mode-even').className = (mode === 'EVEN'
      ? 'py-2 rounded-xl text-xs font-bold transition bg-white text-brand-600 shadow-sm'
      : 'py-2 rounded-xl text-xs font-bold transition text-slate-600 hover:text-slate-900');
    $('split-mode-custom').className = (mode === 'CUSTOM'
      ? 'py-2 rounded-xl text-xs font-bold transition bg-white text-brand-600 shadow-sm'
      : 'py-2 rounded-xl text-xs font-bold transition text-slate-600 hover:text-slate-900');

    // When switching EVEN → CUSTOM, pre-fill each participant's share so
    // users don't have to retype amounts manually — senior UX move.
    if (prevMode === 'EVEN' && mode === 'CUSTOM' && splitParticipants.length > 0) {
      const totalRaw = parseFloat($('split-total').value);
      if (!isNaN(totalRaw) && totalRaw > 0) {
        const people = splitParticipants.length + 1; // creator + participants
        const share = Math.round((totalRaw / people) * 100) / 100;
        splitParticipants.forEach(p => {
          if (p.customAmount === null || p.customAmount === undefined) {
            p.customAmount = share;
          }
        });
      }
    }

    // Show/hide distribute button
    const distributeBtn = $('btn-distribute-even');
    if (distributeBtn) distributeBtn.classList.toggle('hidden', mode !== 'CUSTOM');

    renderSplitParticipants();
    calcSplitAmounts();
  }

  function addSplitParticipant(key, name) {
    const rawKey = (key || $('split-friend-input').value).trim();
    if (!rawKey) return;

    if (API.user && (rawKey === API.user.userId || rawKey === API.user.phoneNumber)) {
      showError('split-error', 'You cannot add yourself as a split participant.');
      return;
    }

    if (splitParticipants.some(p => p.id === rawKey || (p.phone && p.phone === rawKey))) {
      showError('split-error', 'Friend already added to this split.');
      return;
    }

    hideError('split-error');

    // If already in CUSTOM mode and total is filled, pre-fill with equal share
    let prefilledAmount = null;
    if (splitMode === 'CUSTOM') {
      const totalRaw = parseFloat($('split-total').value);
      if (!isNaN(totalRaw) && totalRaw > 0) {
        // Auto-distribute remaining among all participants incl. this new one
        const newCount = splitParticipants.length + 1;
        const people = newCount + 1; // +1 for creator
        prefilledAmount = Math.round((totalRaw / people) * 100) / 100;
      }
    }

    splitParticipants.push({
      id: rawKey,
      name: name || rawKey,
      customAmount: prefilledAmount
    });

    $('split-friend-input').value = '';
    renderSplitParticipants();
    calcSplitAmounts();
  }

  /**
   * Distribute the remaining (total − creator's minimum share) evenly
   * among all participants. Senior UX helper so no manual arithmetic needed.
   */
  function distributeRemainingEvenly() {
    const totalRaw = parseFloat($('split-total').value);
    if (isNaN(totalRaw) || totalRaw <= 0) {
      showError('split-error', 'Please enter the total bill amount first.');
      return;
    }
    if (!splitParticipants.length) {
      showError('split-error', 'Add at least one friend first.');
      return;
    }
    const people = splitParticipants.length + 1; // creator + friends
    const share = Math.round((totalRaw / people) * 100) / 100;
    splitParticipants.forEach(p => { p.customAmount = share; });
    hideError('split-error');
    renderSplitParticipants();
    calcSplitAmounts();
  }

  function removeSplitParticipant(idx) {
    splitParticipants.splice(idx, 1);
    renderSplitParticipants();
    calcSplitAmounts();
  }

  function renderSplitParticipants() {
    const list = $('split-participants-list');
    const emptyHint = $('split-empty-hint');
    const countEl = $('split-participant-count');

    if (countEl) {
      const n = splitParticipants.length;
      countEl.textContent = n === 0 ? '0 friends added' : n + ' friend' + (n > 1 ? 's' : '') + ' added';
      countEl.className = 'text-[11px] font-bold px-2 py-0.5 rounded-full ' +
        (n > 0 ? 'bg-brand-500 text-white' : 'bg-brand-100 text-brand-700');
    }

    // Hide quick-add chips for participants already added or for self
    document.querySelectorAll('.quick-friend-btn').forEach(btn => {
      const chipId = btn.getAttribute('data-id');
      const isSelf = API.user && chipId === API.user.userId;
      const alreadyAdded = splitParticipants.some(p => p.id === chipId);
      btn.style.display = (isSelf || alreadyAdded) ? 'none' : '';
    });

    // Render list or empty hint
    if (!splitParticipants.length) {
      const hint = $('split-empty-hint');
      if (hint) {
        list.innerHTML = '';
        list.appendChild(hint);
        hint.classList.remove('hidden');
      }
      $('split-calc-gauge').classList.add('hidden');
      return;
    }
    const hint2 = $('split-empty-hint');
    if (hint2) hint2.classList.add('hidden');
    $('split-calc-gauge').classList.remove('hidden');

    list.innerHTML = splitParticipants.map((p, idx) => {
      return `
        <div class="flex items-center justify-between p-2.5 rounded-xl bg-white border border-slate-200 gap-2">
          <div class="flex items-center gap-2 flex-1 min-width-0">
            <div class="w-7 h-7 rounded-lg bg-brand-100 text-brand-700 font-bold grid place-items-center text-xs">
              ${esc((p.name || p.id)[0].toUpperCase())}
            </div>
            <div class="min-w-0">
              <div class="text-xs font-bold text-slate-800 truncate">${esc(p.name)}</div>
              <div class="text-[10px] text-slate-400 truncate">${esc(p.id)}</div>
            </div>
          </div>

          ${splitMode === 'CUSTOM' ? `
            <div class="flex items-center gap-1">
              <span class="text-xs text-slate-400 font-bold">৳</span>
              <input type="number" min="1" step="0.01" class="inp py-1 px-2 text-xs w-24 text-right font-bold"
                placeholder="Share" value="${p.customAmount || ''}"
                oninput="window.UniPaySplit.updateCustomAmt(${idx}, this.value)">
            </div>
          ` : `
            <div class="text-xs font-bold text-brand-600" id="even-share-${idx}">৳0.00</div>
          `}

          <button type="button" class="text-slate-400 hover:text-red-500 font-bold text-base px-1.5" onclick="window.UniPaySplit.remove(${idx})">
            ×
          </button>
        </div>
      `;
    }).join('');
  }

  function calcSplitAmounts() {
    const totalRaw = parseFloat($('split-total').value);
    const total = isNaN(totalRaw) ? 0 : totalRaw;
    const count = splitParticipants.length;

    if (count === 0 || total <= 0) {
      $('split-calc-gauge').classList.add('hidden');
      return;
    }

    $('split-calc-gauge').classList.remove('hidden');

    if (splitMode === 'EVEN') {
      $('split-summary-text').classList.remove('hidden');
      $('split-custom-gauge').classList.add('hidden');

      const people = count + 1; // creator + participants
      const share = Math.round((total / people) * 100) / 100;
      $('split-per-person').textContent = taka(share);

      splitParticipants.forEach((_, idx) => {
        const el = $('even-share-' + idx);
        if (el) el.textContent = taka(share);
      });
    } else {
      $('split-summary-text').classList.add('hidden');
      $('split-custom-gauge').classList.remove('hidden');

      let allocated = 0;
      splitParticipants.forEach(p => {
        if (p.customAmount) allocated += p.customAmount;
      });

      const creatorShare = Math.max(0, total - allocated);
      $('split-custom-alloc').textContent = taka(allocated);
      $('split-custom-creator').textContent = taka(creatorShare);

      if (allocated > total) {
        showError('split-error', 'Allocated sum (৳' + allocated.toFixed(2) + ') exceeds total bill (৳' + total.toFixed(2) + ')');
      } else {
        hideError('split-error');
      }
    }
  }

  async function submitSplitBill() {
    hideError('split-error');
    const title = $('split-title').value.trim();
    const total = parseFloat($('split-total').value);
    const note = $('split-note').value.trim();

    if (!title) {
      showError('split-error', 'Please enter a bill or restaurant title.');
      return;
    }
    if (isNaN(total) || total < 1) {
      showError('split-error', 'Please enter a valid bill amount of at least ৳1.00.');
      return;
    }
    if (!splitParticipants.length) {
      showError('split-error', 'Please add at least one friend to split the bill.');
      return;
    }

    if (splitMode === 'CUSTOM') {
      let sumParticipants = 0;
      for (const p of splitParticipants) {
        const amt = parseFloat(p.customAmount);
        if (isNaN(amt) || amt < 0.5) {
          const name = p.name || p.id;
          showError('split-error',
            `Please enter a valid amount (min ৳0.50) for ${name}. Use "↔ Distribute Evenly" to auto-fill.`);
          return;
        }
        sumParticipants += amt;
      }
      if (Math.round(sumParticipants * 100) > Math.round(total * 100)) {
        showError('split-error',
          `Friends' total (৳${sumParticipants.toFixed(2)}) exceeds bill (৳${total.toFixed(2)}). ` +
          `Reduce amounts or click "↔ Distribute Evenly".`);
        return;
      }
    }

    const participantsPayload = splitParticipants.map(p => ({
      userIdentifier: p.id,
      amount: splitMode === 'CUSTOM' ? parseFloat(p.customAmount) : null
    }));

    const btn = $('split-confirm-btn');
    btn.disabled = true;
    btn.textContent = 'Sending requests…';

    try {
      await API.call('/api/splitpay/bills', {
        method: 'POST',
        body: {
          title,
          totalAmount: total,
          splitType: splitMode,
          note: note || null,
          participants: participantsPayload
        }
      });

      closeModals();
      const modeLabel = splitMode === 'EVEN' ? 'evenly' : 'by custom amount';
      toast(
        '🍽️ Split Created!',
        `Bill split ${modeLabel} — money requests sent to ${splitParticipants.length} friend(s). ` +
        'Once they accept, your wallet gets reimbursed.',
        false
      );
      Sound.splitSuccess();
      refreshSplitData();
      refreshUserData();
    } catch (err) {
      showError('split-error', err.message);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Send Split Requests';
    }
  }

  async function acceptSplitRequest(requestId) {
    try {
      const res = await API.call('/api/splitpay/requests/' + encodeURIComponent(requestId) + '/accept', {
        method: 'POST'
      });
      toast('✅ Split Paid', res.message, false);
      Sound.splitSuccess();
      refreshUserData();
      refreshSplitData();
    } catch (err) {
      toast('Payment Failed', err.message, true);
    }
  }

  async function declineSplitRequest(requestId) {
    try {
      await API.call('/api/splitpay/requests/' + encodeURIComponent(requestId) + '/decline', {
        method: 'POST'
      });
      toast('Request Declined', 'The requester has been notified.', false);
      Sound.error();
      refreshSplitData();
    } catch (err) {
      toast('Error', err.message, true);
    }
  }

  async function cancelSplitBill(billId) {
    if (!confirm('Are you sure you want to cancel this split bill?')) return;
    try {
      await API.call('/api/splitpay/bills/' + encodeURIComponent(billId) + '/cancel', {
        method: 'POST'
      });
      toast('Bill Cancelled', 'Active split bill cancelled.', false);
      refreshSplitData();
    } catch (err) {
      toast('Error', err.message, true);
    }
  }

  // Global namespace for split button clicks in HTML
  window.UniPaySplit = {
    accept: acceptSplitRequest,
    decline: declineSplitRequest,
    cancel: cancelSplitBill,
    remove: removeSplitParticipant,
    updateCustomAmt: (idx, val) => {
      // Use null (not 0) when field is empty so backend correctly omits it.
      const parsed = parseFloat(val);
      splitParticipants[idx].customAmount = isNaN(parsed) || val === '' ? null : parsed;
      calcSplitAmounts();
    },
    distributeEvenly: distributeRemainingEvenly
  };

  /* -------------------------------------------------------- vendor panels */
  async function refreshVendorData() {
    try {
      const wallet = await API.call('/api/wallet');
      currentVendorRawBalance = Number(wallet.balance);
      if (!isVendorBalanceHidden) {
        animateNumber($('vendor-balance'), lastVendorBalance ?? wallet.balance, wallet.balance, 650, '৳', 2);
      } else {
        $('vendor-balance').textContent = '••••••';
      }
      lastVendorBalance = Number(wallet.balance);

      const [profileRes, stats, history] = await Promise.allSettled([
        API.call('/api/vendor/profile'),
        API.call('/api/vendor/stats'),
        API.call('/api/wallet/transactions?page=0&size=25')
      ]);

      const hasProfile = profileRes.status === 'fulfilled' && profileRes.value;
      const stallName = hasProfile ? profileRes.value.stallName : '';
      const stallCategory = hasProfile ? profileRes.value.stallCategory : '';
      const qrPayload = (hasProfile && profileRes.value.qrCodeIdentifier) ? profileRes.value.qrCodeIdentifier : ('UNIPAY:VENDOR:' + ((API.user && API.user.userId) || ''));

      if ($('vendor-card-holder-name')) {
        $('vendor-card-holder-name').textContent = (stallName || (API.user && API.user.fullName) || 'CAMPUS MERCHANT').toUpperCase();
      }
      if ($('vendor-card-operator-name')) {
        $('vendor-card-operator-name').textContent = ((API.user && API.user.fullName) || 'AUTHORIZED').toUpperCase();
      }
      if ($('vendor-role-badge') && stallCategory) {
        const catIcons = { CANTEEN: '🏪', BOOKSHOP: '📚', FOOD_STALL: '🍲', OTHER: '🏬' };
        const icon = catIcons[stallCategory] || '🏪';
        $('vendor-role-badge').textContent = `${icon} ${stallCategory} · Official POS`;
      }
      if ($('vendor-card-qr-payload')) {
        $('vendor-card-qr-payload').textContent = qrPayload;
      }

      updateVendor3DCardPAN((API.user && API.user.userId) || '');
      updateVendor3DCardBack((API.user && API.user.phoneNumber) || '');

      if (profileRes.status === 'fulfilled') {
        $('vendor-stall').innerHTML =
          esc(profileRes.value.stallName) +
          '<div class="text-[11px] font-medium text-slate-300">' + esc(profileRes.value.stallCategory) + '</div>';
      } else {
        $('vendor-stall').innerHTML =
          '<span class="text-amber-400 text-sm">not configured</span>' +
          '<div class="text-[11px] font-medium text-slate-400">tap "edit profile" below</div>';
      }
      if (stats.status === 'fulfilled') {
        $('vendor-today').textContent = taka(stats.value.todaySales);
        $('vendor-count').textContent = stats.value.todayCount + ' payment' + (stats.value.todayCount === 1 ? '' : 's') + ' today';
      }
      if (history.status === 'fulfilled') renderHistory('vendor-history', history.value);
      loadQr();
      loadCashoutHistory();
      loadVendorExpenseTracker(currentVendorExpensePeriod);
    } catch (err) { if (err.status !== 401) toast('Error', err.message, true); }
  }

  async function loadQr() {
    try {
      const amountRaw = parseFloat($('qr-amount').value);
      const amount = isNaN(amountRaw) ? null : amountRaw;
      const qs = 'type=' + qrMode + (amount ? '&amount=' + amount : '');
      const blob = await API.blob('/api/vendor/qr.png?' + qs);
      if (window.__lastQrUrl) URL.revokeObjectURL(window.__lastQrUrl);
      window.__lastQrUrl = URL.createObjectURL(blob);
      $('qr-image').src = window.__lastQrUrl;
      const meta = await API.call('/api/vendor/qr?' + qs);
      $('qr-desc').textContent = 'Payload: ' + meta.payload;
      $('qr-expiry').textContent = meta.type === 'DYNAMIC'
        ? 'Expires ' + new Date(meta.expiresAt).toLocaleTimeString() + ' · one-time use'
        : 'Reusable static code — safe to print';
      window.__lastQrPayload = meta.payload;
    } catch (err) {
      $('qr-desc').textContent = 'Complete your stall profile to activate the QR.';
      $('qr-expiry').textContent = '';
    }
  }

  function setQrMode(mode) {
    qrMode = mode;
    $('qr-mode-static').classList.toggle('active', mode === 'STATIC');
    $('qr-mode-dynamic').classList.toggle('active', mode === 'DYNAMIC');
    $('qr-amount-box').classList.toggle('hidden', mode !== 'DYNAMIC');
    $('btn-qr-regen').textContent = mode === 'STATIC' ? 'Show QR' : 'Generate new QR';
    loadQr();
  }

  function showPosBanner(event) {
    $('pos-title').textContent = event.title || 'Payment received';
    $('pos-detail').textContent = (event.senderName || 'A customer') + ' · ' +
      timeAgoOrDate(event.timestamp) + ' · ' + (event.transactionId || '');
    $('pos-amount').textContent = taka(event.amount);
    const banner = $('pos-banner');
    banner.classList.remove('hidden', 'banner-out');
    clearTimeout(showPosBanner._t);
    showPosBanner._t = setTimeout(() => {
      banner.classList.add('banner-out');
      setTimeout(() => banner.classList.add('hidden'), 320);
    }, 6000);
  }

  /* -------------------------------------------------------- vendor cashout */
  let selectedCashoutChannel = 'BKASH';

  async function openCashoutModal() {
    selectedCashoutChannel = 'BKASH';
    $('cashout-amount').value = '';
    $('cashout-account').value = '';
    $('cashout-holder').value = '';
    $('cashout-bank-name').value = '';
    $('cashout-branch').value = '';
    $('cashout-bank-fields').classList.add('hidden');
    $('cashout-account-label').textContent = 'MFS Wallet Number';
    $('cashout-account').placeholder = '01XXXXXXXXX';
    hideError('cashout-error');

    // Set first channel active
    document.querySelectorAll('.cashout-channel-btn').forEach((btn, i) => {
      btn.classList.toggle('border-emerald-500', i === 0);
      btn.classList.toggle('bg-emerald-50', i === 0);
    });

    // Load current balance
    try {
      const wallet = await API.call('/api/wallet');
      $('cashout-available').textContent = taka(wallet.balance);
    } catch (e) {}

    openModal('modal-cashout');
  }

  function cashoutRowHtml(c) {
    const channelIcons = { BKASH: '📱', NAGAD: '🟢', ROCKET: '🚀', BANK: '🏦', OTHER: '💳' };
    const icon = channelIcons[c.channel] || '💸';
    const statusBadge = c.status === 'COMPLETED'
      ? '<span class="badge-pill badge-settled">✅ Completed</span>'
      : (c.status === 'PENDING'
        ? '<span class="badge-pill badge-pending">⏳ Processing</span>'
        : '<span class="badge-pill badge-declined">❌ Failed</span>');
    // The API field is `createdAt`; `requestedAt` never existed on the DTO, so this
    // line used to render a blank timestamp followed by a stray leading " · ".
    const ts = fullTimestamp(c.createdAt);
    const ago = timeAgo(c.createdAt);
    const sub = (ago ? ago + ' · ' : '') + (ts || '') +
      ' · To: ' + (c.destination || c.accountNumber || '—');
    return `
      <div class="txn-row">
        <div class="txn-ico out">${icon}</div>
        <div class="txn-meta">
          <div class="txn-name flex items-center gap-2">${esc(c.channel)} Cash-Out ${statusBadge}</div>
          <div class="txn-sub">${esc(sub)}</div>
        </div>
        <div class="txn-amt out">−${taka(c.amount)}</div>
      </div>`;
  }

  async function loadCashoutHistory() {
    if (!$('vendor-cashout-list')) return;
    try {
      const cashouts = await API.call('/api/vendor/cashouts');
      paintHistory(
        'vendor-cashout-list',
        (Array.isArray(cashouts) ? cashouts : []).map(cashoutRowHtml),
        '<p class="text-sm text-slate-400 py-6 text-center">No cash-outs yet. Tap "Cash Out" above to withdraw earnings.</p>'
      );
    } catch (err) {
      paintHistory(
        'vendor-cashout-list',
        [],
        '<p class="text-sm text-slate-400 py-6 text-center">Could not load cash-out history.</p>'
      );
    }
  }

  async function confirmCashout() {
    hideError('cashout-error');
    const btn = $('cashout-confirm');
    const origHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span class="btn-spinner"></span> Processing…';
    const amount = parseFloat($('cashout-amount').value);
    const accountNumber = $('cashout-account').value.trim();
    if (!amount || amount < 10) {
      showError('cashout-error', 'Minimum cash-out amount is ৳10.00.');
      btn.disabled = false;
      btn.innerHTML = origHtml;
      return;
    }
    if (!accountNumber) {
      showError('cashout-error', 'Please enter the account/phone number to receive funds.');
      btn.disabled = false;
      btn.innerHTML = origHtml;
      return;
    }
    const body = {
      amount,
      channel: selectedCashoutChannel,
      accountNumber,
      accountHolderName: $('cashout-holder').value.trim() || null,
      nonce: API.uuid()
    };
    if (selectedCashoutChannel === 'BANK') {
      body.bankName = $('cashout-bank-name').value.trim() || null;
      body.branchName = $('cashout-branch').value.trim() || null;
    }
    try {
      const res = await API.call('/api/vendor/cashout', { method: 'POST', body });
      closeModals();
      toast('💸 Cash-Out Initiated', `${taka(res.amount)} → ${res.destination || accountNumber}. New balance: ${taka(res.newBalance)}`, false);
      Sound.vendorPayment();
      refreshVendorData();
    } catch (err) {
      showError('cashout-error', err.message);
    } finally {
      btn.disabled = false;
      btn.innerHTML = origHtml;
    }
  }

  /* -------------------------------------------------------------- modals */
  let activeModalId = null;
  let modalCloseTimer = null;

  function openModal(id) {
    if (modalCloseTimer) {
      clearTimeout(modalCloseTimer);
      modalCloseTimer = null;
    }
    // If another modal was open, hide it immediately
    if (activeModalId && activeModalId !== id) {
      const prev = $(activeModalId);
      if (prev) {
        prev.classList.remove('active', 'modal-leave');
        prev.classList.add('hidden');
      }
    }
    activeModalId = id;
    const modal = $(id);
    const backdrop = $('modal-backdrop');
    if (!modal || !backdrop) return;

    backdrop.classList.remove('hidden');
    modal.classList.remove('hidden', 'modal-leave');
    modal.classList.add('modal-enter');

    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        backdrop.classList.add('active');
        modal.classList.remove('modal-enter');
        modal.classList.add('active');
      });
    });
  }

  function closeModals() {
    const backdrop = $('modal-backdrop');
    const openModals = document.querySelectorAll('.modal.active, .modal:not(.hidden)');

    if (backdrop) backdrop.classList.remove('active');
    openModals.forEach(m => {
      m.classList.remove('active');
      m.classList.add('modal-leave');
    });

    if (modalCloseTimer) clearTimeout(modalCloseTimer);
    modalCloseTimer = setTimeout(() => {
      if (backdrop) backdrop.classList.add('hidden');
      openModals.forEach(m => {
        m.classList.remove('modal-leave');
        m.classList.add('hidden');
      });
      activeModalId = null;
      modalCloseTimer = null;
    }, 220);

    // Reset OTP step state when modal is dismissed
    const stepA = $('cashin-step-a');
    const stepB = $('cashin-step-b');
    if (stepA && stepB) {
      stepA.classList.remove('hidden', 'wizard-slide-right', 'wizard-slide-left');
      stepB.classList.add('hidden');
      stepB.classList.remove('wizard-slide-right', 'wizard-slide-left');
    }
    clearInterval(otpState.timerInterval);
    stopScanner();
  }

  async function loadProviders() {
    const grid = $('provider-grid');
    grid.innerHTML = '<span class="text-xs text-slate-400">loading…</span>';
    try {
      const providers = await API.call('/api/mfs/providers');
      grid.innerHTML = '';
      providers.forEach((p, i) => {
        const btn = document.createElement('button');
        btn.className = 'provider-btn p-3 rounded-2xl border-2 border-slate-200 hover:border-brand-500 text-sm font-bold transition';
        // Use proper SVG logos based on provider code
        let logoHtml = '';
        const code = p.code?.toUpperCase();
        if (code === 'BKASH') {
          logoHtml = '<img src="img/bkash.svg" class="mfs-provider-logo" alt="bKash">';
        } else if (code === 'NAGAD') {
          logoHtml = '<img src="img/nagad.svg" class="mfs-provider-logo" alt="Nagad">';
        } else if (code === 'ROCKET') {
          logoHtml = '<img src="img/rocket.svg" class="mfs-provider-logo" alt="Rocket">';
        } else {
          logoHtml = '<div class="text-xl">' + (p.icon || '🏦') + '</div>';
        }
        btn.innerHTML = logoHtml + '<div class="mt-1 font-bold">' + esc(p.name) + '</div>';
        btn.onclick = () => {
          grid.querySelectorAll('.provider-btn').forEach(b => b.classList.remove('border-brand-600', 'bg-brand-50'));
          btn.classList.add('border-brand-600', 'bg-brand-50');
          window.__selectedProvider = p.code;
        };
        if (i === 0) setTimeout(() => btn.click(), 0);
        grid.appendChild(btn);
      });
    } catch (err) { grid.innerHTML = ''; }
  }

  /* ==================== USER PROFILE EDIT FLOW & AVATAR =================== */
  let currentEditingAvatar = null;

  function renderTopbarAvatar(avatarUrl) {
    const icon = $('topbar-avatar-icon');
    if (!icon) return;
    if (avatarUrl && (avatarUrl.startsWith('data:image/') || avatarUrl.startsWith('http'))) {
      icon.innerHTML = `<img src="${esc(avatarUrl)}" class="w-full h-full object-cover rounded-xl" alt="Profile Picture">`;
    } else if (avatarUrl) {
      icon.textContent = avatarUrl;
    } else {
      icon.textContent = '👤';
    }
  }

  function renderAvatarPreview(avatar) {
    const img = $('prof-avatar-img');
    const emoji = $('prof-avatar-emoji');
    const chips = document.querySelectorAll('.preset-avatar-chip');
    chips.forEach(c => c.classList.toggle('active', c.dataset.avatar === avatar));
    if (avatar && (avatar.startsWith('data:image/') || avatar.startsWith('http'))) {
      if (img) {
        img.src = avatar;
        img.classList.remove('hidden');
      }
      if (emoji) emoji.classList.add('hidden');
    } else if (avatar) {
      if (img) img.classList.add('hidden');
      if (emoji) {
        emoji.textContent = avatar;
        emoji.classList.remove('hidden');
      }
    } else {
      if (img) img.classList.add('hidden');
      if (emoji) {
        emoji.textContent = '👤';
        emoji.classList.remove('hidden');
      }
    }
  }

  function handleAvatarFileSelect(e) {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      showError('prof-error', 'Please choose a valid image file (PNG, JPG, WebP).');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      showError('prof-error', 'Image file is too large (maximum size is 5 MB).');
      return;
    }
    const reader = new FileReader();
    reader.onload = function(evt) {
      const rawData = evt.target.result;
      const tempImg = new Image();
      tempImg.onload = function() {
        const canvas = document.createElement('canvas');
        const maxDim = 256;
        let w = tempImg.width;
        let h = tempImg.height;
        if (w > h) {
          if (w > maxDim) { h = Math.round(h * (maxDim / w)); w = maxDim; }
        } else {
          if (h > maxDim) { w = Math.round(w * (maxDim / h)); h = maxDim; }
        }
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(tempImg, 0, 0, w, h);
        const compressed = canvas.toDataURL('image/jpeg', 0.88);
        currentEditingAvatar = compressed;
        renderAvatarPreview(currentEditingAvatar);
      };
      tempImg.src = rawData;
    };
    reader.readAsDataURL(file);
  }

  async function openUserProfileModal() {
    if (!API.user) return;
    hideError('prof-error');
    $('prof-user-id').textContent = API.user.userId;
    $('prof-role-badge').textContent = formatRole(API.user.role);
    $('prof-fullname').value = API.user.fullName || '';
    $('prof-phone').value = API.user.phoneNumber || '';
    $('prof-new-pass').value = '';

    currentEditingAvatar = (API.user && API.user.avatarUrl) || null;
    renderAvatarPreview(currentEditingAvatar);

    const isVendor = API.user.role === 'VENDOR';
    $('prof-vendor-section').classList.toggle('hidden', !isVendor);

    if (isVendor) {
      try {
        const vp = await API.call('/api/vendor/profile');
        $('prof-stall-name').value = vp.stallName || '';
        $('prof-stall-cat').value = vp.stallCategory || 'CANTEEN';
      } catch (e) {
        $('prof-stall-name').value = '';
      }
    }

    openModal('modal-user-profile');
  }

  async function saveUserProfile() {
    hideError('prof-error');
    const fullName = $('prof-fullname').value.trim();
    const phone = $('prof-phone').value.trim();
    const newPassword = $('prof-new-pass').value;

    if (!fullName) {
      showError('prof-error', 'Please enter your full name.');
      return;
    }
    if (!/^01[3-9]\d{8}$/.test(phone)) {
      showError('prof-error', 'Please enter a valid 11-digit Bangladeshi mobile number (01XXXXXXXXX).');
      return;
    }
    if (newPassword && newPassword.length < 6) {
      showError('prof-error', 'New password must be at least 6 characters.');
      return;
    }

    const btn = $('btn-save-user-profile');
    btn.disabled = true;
    btn.textContent = 'Saving…';

    const body = { fullName, phoneNumber: phone };
    if (newPassword) body.newPassword = newPassword;
    if (currentEditingAvatar !== null) {
      body.avatarUrl = currentEditingAvatar;
    }

    if (API.user.role === 'VENDOR') {
      const stallName = $('prof-stall-name').value.trim();
      const stallCategory = $('prof-stall-cat').value;
      if (!stallName) {
        showError('prof-error', 'Please enter your stall name.');
        btn.disabled = false;
        btn.textContent = 'Save Profile ✓';
        return;
      }
      body.stallName = stallName;
      body.stallCategory = stallCategory;
    }

    try {
      const updated = await API.call('/api/user/profile', { method: 'PUT', body });
      API.user.fullName = updated.fullName;
      API.user.phoneNumber = updated.phoneNumber;
      API.user.avatarUrl = updated.avatarUrl;
      API.saveSession(API.token, API.user);

      $('topbar-name').textContent = updated.fullName;
      $('topbar-id').textContent = updated.userId + ' · ' + formatRole(updated.role);
      renderTopbarAvatar(updated.avatarUrl);

      if ($('card-holder-name')) {
        $('card-holder-name').textContent = updated.fullName || 'UIU MEMBER';
      }

      closeModals();
      toast('✅ Profile Updated', 'Your profile details and picture have been saved.', false);
      Sound.blip();

      if (API.user.role === 'VENDOR') {
        refreshVendorData();
      } else {
        refreshUserData();
      }
    } catch (err) {
      showError('prof-error', err.message);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Save Profile ✓';
    }
  }

  /* ==================== 3D VISA / MASTERCARD INTERACTIVITY ================ */
  let isBalanceHidden = false;
  let isPanHidden = true;
  let isCardFlipped = false;
  let currentRawBalance = 0;

  function init3DCard() {
    const stage = $('card-3d-stage');
    const flipper = $('card-3d-flipper');
    if (!stage || !flipper) return;

    stage.onmousemove = (e) => {
      const rect = stage.getBoundingClientRect();
      const x = (e.clientX - rect.left) / rect.width;
      const y = (e.clientY - rect.top) / rect.height;
      const rotX = ((0.5 - y) * 16).toFixed(2);
      const rotY = ((x - 0.5) * 20).toFixed(2);
      const base = isCardFlipped ? 'rotateY(180deg) ' : '';
      flipper.style.transform = `${base}rotateX(${rotX}deg) rotateY(${rotY}deg)`;
      stage.style.setProperty('--glare-x', `${(x * 100).toFixed(1)}%`);
      stage.style.setProperty('--glare-y', `${(y * 100).toFixed(1)}%`);
    };

    stage.onmouseleave = () => {
      flipper.style.transform = isCardFlipped ? 'rotateY(180deg)' : 'none';
    };

    const flipBtn = $('btn-card-flip');
    const flipBackBtn = $('btn-card-flip-back');
    const toggleFlip = () => {
      isCardFlipped = !isCardFlipped;
      flipper.classList.toggle('is-flipped', isCardFlipped);
      flipper.style.transform = isCardFlipped ? 'rotateY(180deg)' : 'none';
    };
    if (flipBtn) flipBtn.onclick = toggleFlip;
    if (flipBackBtn) flipBackBtn.onclick = toggleFlip;

    const btnToggleBal = $('btn-toggle-balance-vis');
    if (btnToggleBal) {
      btnToggleBal.onclick = () => {
        isBalanceHidden = !isBalanceHidden;
        const eyeIcon = $('balance-eye-icon');
        const eyeText = $('balance-eye-text');
        const balEl = $('user-balance');
        if (isBalanceHidden) {
          balEl.textContent = '••••••';
          if (eyeIcon) eyeIcon.textContent = '🙈';
          if (eyeText) eyeText.textContent = 'Show';
        } else {
          balEl.textContent = taka(currentRawBalance);
          if (eyeIcon) eyeIcon.textContent = '👁️';
          if (eyeText) eyeText.textContent = 'Hide';
        }
      };
    }

    const btnTogglePan = $('btn-toggle-pan-mask');
    if (btnTogglePan) {
      btnTogglePan.onclick = () => {
        isPanHidden = !isPanHidden;
        update3DCardPAN((API.user && API.user.userId) || '');
      };
    }

    update3DCardPAN((API.user && API.user.userId) || '');
    update3DCardBack((API.user && API.user.phoneNumber) || '');
  }

  function update3DCardPAN(userId) {
    const panEl = $('card-number-display');
    if (!panEl) return;
    const rawId = (userId || (API.user && API.user.userId) || '0112330140').trim();
    if (isPanHidden) {
      if (rawId.length >= 6) {
        const p1 = rawId.substring(0, 3);
        const p2 = rawId.substring(rawId.length - 3);
        panEl.textContent = `${p1} •••• ${p2}`;
      } else {
        panEl.textContent = '••••••••';
      }
    } else {
      // Exactly the user's valid UIU ID number, NO extra numbers added!
      panEl.textContent = rawId;
    }
  }

  /* ------------------------------------------------------------------
   * Card signature name
   * ------------------------------------------------------------------
   * A card is signed with the holder's MAIN name only -- never the full
   * name, and the user is never asked to supply or confirm it. It is
   * derived automatically from the full name the backend already returns.
   *
   *   "Md Saimon Islam (Verified)"  -> "Saimon"
   *   "Dr. Nafees Ahmed"            -> "Nafees"
   *   "Osama Bin Mansur"            -> "Osama"
   *
   * Leading honorifics/titles are skipped so "Md" and "Dr." are never
   * treated as the name, and any parenthesised suffix such as
   * "(Verified)" is discarded before splitting.
   *
   * VENDOR EXCEPTION: a vendor account's full name is the stall/business
   * name, not a person's name, so taking the first token would sign
   * "UIU Central Canteen" as just "UIU". Merchant passes are therefore
   * signed with the complete stall name.
   */
  const SIGNATURE_SKIP_TOKENS = new Set([
    'md', 'mst', 'dr', 'prof', 'mr', 'mrs', 'ms', 'miss', 'mx',
    'begum', 'engr', 'eng', 'arch', 'capt', 'sir', 'mohammad',
    'mohammed', 'muhammad', 'abdul', 'sheikh', 'sk'
  ]);

  /** Personal accounts: the main (first) name only, honorifics skipped. */
  function signatureName(fullName) {
    if (!fullName) return '';
    // Drop parenthesised suffixes, e.g. "(Verified)".
    const cleaned = String(fullName).replace(/[([{][^)\]}]*[)\]}]/g, ' ').trim();
    // Split on any run of separators: spaces, dots, dashes, underscores.
    const tokens = cleaned.split(/[\s._\-/]+/).filter(Boolean);
    for (const token of tokens) {
      const letters = token.replace(/[^\p{L}\p{N}]/gu, '');
      if (!letters) continue;
      if (SIGNATURE_SKIP_TOKENS.has(letters.toLowerCase())) continue;
      return letters.charAt(0).toUpperCase() + letters.slice(1);
    }
    return '';
  }

  /** Vendor accounts: the whole stall name, so the merchant is identifiable. */
  function stallSignatureName(fullName) {
    if (!fullName) return '';
    return String(fullName)
      .replace(/[([{][^)\]}]*[)\]}]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  /**
   * Writes the auto-derived signature onto a card back. Safe to call repeatedly.
   * @param {string} elementId  target span id
   * @param {string} fullName   name as returned by the backend
   * @param {{vendor?: boolean}} [opts] pass {vendor:true} to sign the full stall name
   */
  function renderCardSignature(elementId, fullName, opts) {
    const el = $(elementId);
    if (!el) return;
    const isVendor = !!(opts && opts.vendor);
    const name = isVendor ? stallSignatureName(fullName) : signatureName(fullName);
    el.textContent = name || '—';
    el.title = name ? ('Signed electronically as ' + name) : 'Signature unavailable';
    // Long stall names need a smaller hand, more of the strip, and room to
    // wrap on narrow phones, so toggle the modifier on the strip as well.
    const long = isVendor && name.length > 8;
    el.classList.toggle('card-signature-name--long', long);
    const band = el.closest('.card-signature-band');
    if (band) band.classList.toggle('card-signature-band--long', long);
  }

  function update3DCardBack(phone) {
    const cvvEl = $('card-cvv-display');
    if (!cvvEl) return;
    const num = (phone || (API.user && API.user.phoneNumber) || '017XXXXXXXX').trim();
    cvvEl.textContent = num;
    renderCardSignature('card-signature-name', API.user && API.user.fullName);
  }

  /* ==================== VENDOR 3D SMART CARD INTERACTIVITY ================ */
  let isVendorCardFlipped = false;
  let isVendorBalanceHidden = false;
  let isVendorPanHidden = false;
  let currentVendorRawBalance = 0;

  function initVendor3DCard() {
    const stage = $('vendor-card-3d-stage');
    const flipper = $('vendor-card-3d-flipper');
    if (!stage || !flipper) return;

    stage.onmousemove = (e) => {
      const rect = stage.getBoundingClientRect();
      const x = (e.clientX - rect.left) / rect.width;
      const y = (e.clientY - rect.top) / rect.height;
      const rotX = ((0.5 - y) * 16).toFixed(2);
      const rotY = ((x - 0.5) * 20).toFixed(2);
      const base = isVendorCardFlipped ? 'rotateY(180deg) ' : '';
      flipper.style.transform = `${base}rotateX(${rotX}deg) rotateY(${rotY}deg)`;
      stage.style.setProperty('--glare-x', `${(x * 100).toFixed(1)}%`);
      stage.style.setProperty('--glare-y', `${(y * 100).toFixed(1)}%`);
    };

    stage.onmouseleave = () => {
      flipper.style.transform = isVendorCardFlipped ? 'rotateY(180deg)' : 'none';
    };

    const flipBtn = $('btn-vendor-card-flip');
    const flipBackBtn = $('btn-vendor-card-flip-back');
    const toggleFlip = () => {
      isVendorCardFlipped = !isVendorCardFlipped;
      flipper.classList.toggle('is-flipped', isVendorCardFlipped);
      flipper.style.transform = isVendorCardFlipped ? 'rotateY(180deg)' : 'none';
    };
    if (flipBtn) flipBtn.onclick = toggleFlip;
    if (flipBackBtn) flipBackBtn.onclick = toggleFlip;

    const btnToggleBal = $('btn-vendor-toggle-balance-vis');
    if (btnToggleBal) {
      btnToggleBal.onclick = () => {
        isVendorBalanceHidden = !isVendorBalanceHidden;
        const eyeIcon = $('vendor-balance-eye-icon');
        const eyeText = $('vendor-balance-eye-text');
        const balEl = $('vendor-balance');
        if (isVendorBalanceHidden) {
          balEl.textContent = '••••••';
          if (eyeIcon) eyeIcon.textContent = '🙈';
          if (eyeText) eyeText.textContent = 'Show';
        } else {
          balEl.textContent = taka(currentVendorRawBalance);
          if (eyeIcon) eyeIcon.textContent = '👁️';
          if (eyeText) eyeText.textContent = 'Hide';
        }
      };
    }

    const btnTogglePan = $('btn-vendor-toggle-pan-mask');
    if (btnTogglePan) {
      btnTogglePan.onclick = () => {
        isVendorPanHidden = !isVendorPanHidden;
        updateVendor3DCardPAN((API.user && API.user.userId) || '');
      };
    }

    updateVendor3DCardPAN((API.user && API.user.userId) || '');
    updateVendor3DCardBack((API.user && API.user.phoneNumber) || '');
  }

  function updateVendor3DCardPAN(userId) {
    const panEl = $('vendor-card-number-display');
    if (!panEl) return;
    const rawId = (userId || (API.user && API.user.userId) || 'V-CAFE-01').trim();
    if (isVendorPanHidden) {
      if (rawId.length >= 6) {
        const p1 = rawId.substring(0, 3);
        const p2 = rawId.substring(rawId.length - 3);
        panEl.textContent = `${p1} •••• ${p2}`;
      } else {
        panEl.textContent = '••••••••';
      }
    } else {
      panEl.textContent = rawId;
    }
  }

  function updateVendor3DCardBack(phone) {
    const cvvEl = $('vendor-card-cvv-display');
    if (!cvvEl) return;
    const num = (phone || (API.user && API.user.phoneNumber) || '017XXXXXXXX').trim();
    cvvEl.textContent = num;
    renderCardSignature('vendor-card-signature-name', API.user && API.user.fullName, { vendor: true });
  }

  /* ==================== TWO-STEP OTP CASH-IN FLOW ======================== */

  /** Opens the Add Money modal at Step A (provider / amount / phone). */
  function openCashInModal() {
    window.__selectedProvider = null;
    currentNonce = API.uuid();
    otpState.verifying = false;
    $('cashin-amount').value = '';
    $('cashin-phone').value = (API.user && API.user.phoneNumber) || '';
    hideError('cashin-error-a');
    $('cashin-step-a').classList.remove('hidden');
    $('cashin-step-b').classList.add('hidden');
    openModal('modal-cashin');
    loadProviders();
  }

  /** Step 1: Validate inputs. For bKash → redirect to real checkout. For Nagad/Rocket → call /api/otp/generate. */
  async function sendOtp() {
    hideError('cashin-error-a');
    const amount   = parseFloat($('cashin-amount').value);
    const phone    = $('cashin-phone').value.trim();
    const provider = window.__selectedProvider;

    if (!amount || amount < 20) {
      showError('cashin-error-a', 'Please enter a valid amount (min ৳20).');
      return;
    }
    if (amount > 50000) {
      showError('cashin-error-a', 'Maximum cash-in is ৳50,000.');
      return;
    }
    if (!/^01[3-9]\d{8}$/.test(phone)) {
      showError('cashin-error-a', 'Please enter a valid 11-digit BD mobile number.');
      return;
    }
    if (!provider) {
      showError('cashin-error-a', 'Please select an MFS provider (bKash, Nagad, or Rocket).');
      return;
    }

    const btn = $('btn-send-otp');
    btn.disabled = true;

    // ── bKash: tokenized checkout with seamless fallback to sandbox OTP ──
    if (provider === 'BKASH') {
      btn.textContent = 'Connecting to bKash…';
      try {
        const nonce = currentNonce || API.uuid();
        currentNonce = nonce;
        const res = await API.call('/api/bkash/checkout', {
          method: 'POST',
          body: null,
          params: { amount: amount.toFixed(2), nonce }
        });
        if (res && res.bkashURL) {
          closeModals();
          window.open(res.bkashURL, '_blank');
          toast('🔀 Redirecting to bKash', 'Complete payment on the bKash page. Your wallet will update automatically.', false);
          currentNonce = null;
          return;
        }
      } catch (err) {
        console.warn('bKash live PGW unavailable or unconfigured, falling back to Sandbox OTP mode:', err.message);
        toast('📱 bKash Sandbox Mode', 'Live PGW unconfigured; using bKash simulated sandbox (Demo OTP: 123456)', false);
        // Continue to server-side OTP generation below
      }
    }

    // ── MFS Server-side OTP generation (bKash sandbox / Nagad / Rocket) ────────
    btn.textContent = 'Sending OTP…';
    try {
      const nonce = currentNonce || API.uuid();
      currentNonce = nonce;
      const res = await API.call('/api/otp/generate', {
        method: 'POST',
        body: { provider, mfsPhoneNumber: phone, amount, nonce }
      });
      otpState.otpId = res.otpId;
      otpState.nonce = nonce;

      // Populate Step B info banner
      $('otp-masked-phone').textContent   = res.maskedPhone;
      $('otp-provider-label').textContent = provider;
      $('otp-amount-label').textContent   = taka(amount);

      // Clear digit inputs
      for (let i = 0; i < 6; i++) {
        const d = $('otp-d' + i);
        d.value = '';
        d.classList.remove('filled', 'error');
      }
      hideError('cashin-error-b');
      otpState.verifying = false;

      // Smooth slide A → B
      const stepA = $('cashin-step-a');
      const stepB = $('cashin-step-b');
      stepA.classList.add('hidden');
      stepB.classList.remove('hidden', 'wizard-slide-left');
      stepB.classList.add('wizard-slide-right');
      setTimeout(() => {
        $('otp-d0').focus();
        startOtpTimer(res.ttlSeconds || 180);
      }, 50);
    } catch (err) {
      showError('cashin-error-a', err.message);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Send OTP 📲';
    }
  }

  /** Starts (or restarts) the 3-minute countdown. */
  function startOtpTimer(seconds) {
    clearInterval(otpState.timerInterval);
    otpState.secondsLeft = seconds;
    const pill   = $('otp-timer-pill');
    const cd     = $('otp-countdown');
    const resend = $('btn-resend-otp');
    resend.disabled = true;

    function tick() {
      const m = Math.floor(otpState.secondsLeft / 60);
      const s = otpState.secondsLeft % 60;
      cd.textContent = String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
      pill.classList.toggle('urgent', otpState.secondsLeft <= 30);

      if (otpState.secondsLeft <= 0) {
        clearInterval(otpState.timerInterval);
        cd.textContent = '00:00';
        pill.classList.add('urgent');
        resend.disabled = false;
        return;
      }
      otpState.secondsLeft--;
    }
    tick();
    otpState.timerInterval = setInterval(tick, 1000);
  }

  /** Step 2: Call /api/otp/verify — the real server verifies the BCrypt-hashed OTP and credits the wallet. */
  async function verifyOtp() {
    if (otpState.verifying) return;
    otpState.verifying = true;
    hideError('cashin-error-b');
    const btn = $('btn-verify-otp');
    btn.disabled = true;
    btn.textContent = 'Verifying…';

    const otp = [0,1,2,3,4,5].map(i => ($('otp-d' + i).value || '').trim()).join('');
    if (otp.length !== 6 || !/^\d{6}$/.test(otp)) {
      for (let i = 0; i < 6; i++) $('otp-d' + i).classList.add('error');
      setTimeout(() => { for (let i = 0; i < 6; i++) $('otp-d' + i).classList.remove('error'); }, 400);
      showError('cashin-error-b', 'Please enter all 6 OTP digits.');
      btn.disabled = false;
      btn.textContent = 'Verify & Add Money ✓';
      otpState.verifying = false;
      return;
    }

    if (!otpState.otpId) {
      showError('cashin-error-b', 'OTP session expired. Please go back and request a new OTP.');
      btn.disabled = false;
      btn.textContent = 'Verify & Add Money ✓';
      otpState.verifying = false;
      return;
    }

    try {
      const res = await API.call('/api/otp/verify', {
        method: 'POST',
        body: { otpId: otpState.otpId, otp }
      });
      clearInterval(otpState.timerInterval);
      otpState.otpId = null;
      otpState.nonce = null;
      currentNonce = null;
      otpState.verifying = false;
      toast('✅ ' + taka(res.amount) + ' added!', 'New balance: ' + taka(res.newBalance), false);
      Sound.blip();
      closeModals();
      refreshUserData();
    } catch (err) {
      for (let i = 0; i < 6; i++) {
        $('otp-d' + i).classList.add('error');
        $('otp-d' + i).value = '';
        $('otp-d' + i).classList.remove('filled');
      }
      setTimeout(() => { for (let i = 0; i < 6; i++) $('otp-d' + i).classList.remove('error'); }, 500);
      showError('cashin-error-b', err.message);
      $('otp-d0').focus();
      // If OTP is locked (429) or expired (410), clear otpId to force a new generate
      if (err.status === 429 || err.status === 410) otpState.otpId = null;
      btn.disabled = false;
      btn.textContent = 'Verify & Add Money ✓';
      otpState.verifying = false;
    }
  }

  /** Wires auto-advance and backspace navigation for the 6 OTP digit cells. */
  function initOtpDigitInputs() {
    for (let i = 0; i < 6; i++) {
      const input = $('otp-d' + i);

      input.addEventListener('keydown', (e) => {
        // Only allow digits 0-9, backspace, delete, arrows, tab
        if (!e.key.match(/^[0-9]$/) && !['Backspace','Delete','ArrowLeft','ArrowRight','Tab'].includes(e.key)) {
          e.preventDefault();
          return;
        }
        if (e.key === 'Backspace') {
          if (e.target.value) {
            e.target.value = '';
            e.target.classList.remove('filled');
          } else if (i > 0) {
            const prev = $('otp-d' + (i - 1));
            prev.value = '';
            prev.classList.remove('filled');
            prev.focus();
          }
          e.preventDefault();
        }
      });

      input.addEventListener('input', (e) => {
        // Strip non-digits and keep only the latest single digit
        const val = e.target.value.replace(/\D/g, '').slice(-1);
        e.target.value = val;
        e.target.classList.toggle('filled', val !== '');
        if (val && i < 5) {
          $('otp-d' + (i + 1)).focus();
        }
        // Auto-submit only when ALL 6 cells are filled and not already verifying
        if (!otpState.verifying && [0,1,2,3,4,5].every(j => $('otp-d' + j).value !== '')) {
          verifyOtp();
        }
      });

      input.addEventListener('paste', (e) => {
        e.preventDefault();
        const pasted = (e.clipboardData || window.clipboardData).getData('text').replace(/\D/g, '');
        if (pasted.length >= 6) {
          for (let j = 0; j < 6; j++) {
            $('otp-d' + j).value = pasted[j] || '';
            $('otp-d' + j).classList.toggle('filled', !!pasted[j]);
          }
          $('otp-d5').focus();
          setTimeout(() => verifyOtp(), 50); // brief delay so UI updates first
        } else {
          // Short paste: fill from current position
          for (let j = 0; j < pasted.length && (i + j) < 6; j++) {
            $('otp-d' + (i + j)).value = pasted[j];
            $('otp-d' + (i + j)).classList.add('filled');
          }
          const next = Math.min(i + pasted.length, 5);
          $('otp-d' + next).focus();
        }
      });
    }

    const fillBtn = $('btn-quick-fill-otp');
    if (fillBtn) {
      fillBtn.onclick = () => {
        const demoOtp = '123456';
        for (let j = 0; j < 6; j++) {
          const d = $('otp-d' + j);
          if (d) {
            d.value = demoOtp[j];
            d.classList.add('filled');
          }
        }
        const verifyBtn = $('btn-verify-otp');
        if (verifyBtn) verifyBtn.focus();
        Sound.blip();
      };
    }

    document.querySelectorAll('.cashin-preset-btn').forEach(btn => {
      btn.onclick = () => {
        const amt = btn.getAttribute('data-amt');
        if (amt && $('cashin-amount')) {
          $('cashin-amount').value = amt;
          Sound.blip();
        }
      };
    });
  }

  /* ==================== LOYALTY POINTS ===================================== */

  async function redeemPoints() {
    const btn = $('btn-redeem-points');
    if (!btn || btn.disabled) return;
    btn.disabled = true;
    btn.textContent = 'Redeeming…';
    try {
      const res = await API.call('/api/loyalty/redeem', { method: 'POST' });
      toast('⭐ Points Redeemed!', res.message + ' New balance: ' + taka(res.newWalletBalance), false);
      Sound.cash();
      refreshUserData();
    } catch (err) {
      toast('Redemption Failed', err.message, true);
    } finally {
      btn.textContent = '🎁 Redeem Points';
      // disabled state will be set by the next refreshUserData call
    }
  }

  async function confirmSend() {
    hideError('send-error');
    const btn = $('send-confirm');
    const origHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span class="btn-spinner"></span> Sending…';
    currentNonce = currentNonce || API.uuid();
    try {
      const res = await API.call('/api/payments/p2p', {
        method: 'POST',
        body: {
          recipient: $('send-to').value.trim(),
          amount: parseFloat($('send-amount').value),
          nonce: currentNonce
        }
      });
      toast('📤 Sent ' + taka(res.amount), 'To ' + res.receiverName + ' · balance ' + taka(res.payerNewBalance), false);
      Sound.blip();
      currentNonce = null;
      closeModals();
      refreshUserData();
    } catch (err) {
      showError('send-error', err.message);
      if (err.status && err.status !== 409) currentNonce = API.uuid();
    } finally {
      btn.disabled = false;
      btn.innerHTML = origHtml;
    }
  }

  /* --------------------------------------------------------- scan & pay */
  function parseQrPayload(text) {
    const parts = String(text || '').trim().split(':');
    if (parts.length < 3 || !/^unipay$/i.test(parts[0]) || !/^vendor$/i.test(parts[1]) || !parts[2]) return null;
    const result = { vendorId: parts[2], presetAmount: null, payload: text.trim() };
    for (let i = 3; i + 1 < parts.length; i += 2) {
      if (/^amt$/i.test(parts[i])) result.presetAmount = parseFloat(parts[i + 1]);
      if (/^nonce$/i.test(parts[i])) result.nonce = parts[i + 1];
    }
    return result;
  }

  function beginPayment(target) {
    scanned = target;
    $('scan-step1').classList.add('hidden');
    $('scan-step2').classList.remove('hidden');
    $('pay-vendor').textContent = target.vendorId;
    const presetNote = target.presetAmount
      ? 'QR presets the exact amount: ' + taka(target.presetAmount) : '';
    $('pay-preset').textContent = presetNote;
    $('pay-preset').classList.toggle('hidden', !presetNote);
    $('pay-amount').value = target.presetAmount || '';
    $('pay-amount').disabled = !!target.presetAmount;
    stopScanner();
  }

  async function startScanner() {
    if (!window.Html5Qrcode) return;
    try {
      scanner = new Html5Qrcode('qr-reader');
      await scanner.start(
        { facingMode: 'environment' },
        { fps: 10, qrbox: { width: 230, height: 230 } },
        (decoded) => {
          const target = parseQrPayload(decoded);
          if (!target) return;
          Sound.blip();
          beginPayment(target);
        },
        () => { /* per-frame decode misses */ }
      );
    } catch (err) {
      $('scan-cam-error').classList.remove('hidden');
      $('scan-cam-error').textContent =
        'Camera unavailable (' + (err && err.name ? err.name : 'error') + ') — use manual entry below.';
    }
  }

  function stopScanner() {
    if (scanner) {
      const s = scanner;
      scanner = null;
      const isScanning = typeof s.isScanning === 'function' ? s.isScanning() : s.isScanning;
      if (isScanning) {
        s.stop().then(() => s.clear()).catch(() => {});
      } else {
        try { s.clear(); } catch (e) {}
      }
    }
  }

  async function confirmVendorPay() {
    hideError('pay-error');
    const btn = $('pay-confirm');
    const origHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span class="btn-spinner"></span> Confirming…';
    currentNonce = currentNonce || API.uuid();
    try {
      const res = await API.call('/api/payments/vendor', {
        method: 'POST',
        body: {
          payload: scanned.payload || null,
          vendorId: scanned.payload ? null : scanned.vendorId,
          amount: parseFloat($('pay-amount').value),
          nonce: currentNonce
        }
      });
      toast('🛍️ Paid ' + taka(res.amount), res.receiverName + ' · balance ' + taka(res.payerNewBalance), false);
      Sound.blip();
      currentNonce = null;
      closeModals();
      refreshUserData();
    } catch (err) {
      showError('pay-error', err.message);
      if (err.status && err.status !== 409) currentNonce = API.uuid();
    } finally {
      btn.disabled = false;
      btn.innerHTML = origHtml;
    }
  }

  /* -------------------------------------------------------------- toasts */
  function toast(title, message, isError) {
    const container = $('toasts');
    if (!container) return;
    const el = document.createElement('div');
    el.className = 'toast' + (isError ? ' error' : ' success');
    el.innerHTML = `
      <div class="flex-1 min-w-0">
        <div class="t-title">${esc(title)}</div>
        <div class="t-msg">${esc(message)}</div>
      </div>
      <button type="button" class="t-close" title="Dismiss">✕</button>
    `;

    function dismiss() {
      if (el.dataset.dismissed) return;
      el.dataset.dismissed = 'true';
      el.classList.add('toast-out');
      setTimeout(() => el.remove(), 320);
    }

    const closeBtn = el.querySelector('.t-close');
    if (closeBtn) closeBtn.onclick = dismiss;
    container.appendChild(el);

    setTimeout(dismiss, 4500);
  }

  function showError(id, msg) {
    const p = $(id);
    if (!p) return;
    p.textContent = msg;
    p.classList.remove('hidden', 'error-shake');
    void p.offsetWidth;
    p.classList.add('error-shake');
  }

  function hideError(id) {
    const p = $(id);
    if (p) {
      p.classList.add('hidden');
      p.classList.remove('error-shake');
    }
  }

  function spinRefreshIcon(btn) {
    if (!btn) return;
    const ico = btn.querySelector('span');
    if (ico) {
      ico.classList.remove('spin-refresh');
      void ico.offsetWidth;
      ico.classList.add('spin-refresh');
      setTimeout(() => ico.classList.remove('spin-refresh'), 650);
    }
  }

  /* ==================== SOUND & NOTIFICATION ALERT UI ==================== */
  function updateSoundUI() {
    const bellIcon = $('btn-notif-bell-icon');
    if (bellIcon) {
      bellIcon.textContent = Sound.enabled ? '🔔' : '🔕';
    }
    const notifSoundIcon = $('notif-sound-icon');
    const notifSoundText = $('notif-sound-text');
    if (notifSoundIcon && notifSoundText) {
      notifSoundIcon.textContent = Sound.enabled ? '🔊' : '🔇';
      notifSoundText.textContent = Sound.enabled ? 'Alerts: ON' : 'Alerts: MUTED';
    }
    const soundBtn = $('btn-toggle-notif-sound');
    if (soundBtn) {
      if (Sound.enabled) {
        soundBtn.className = 'px-2.5 py-1 rounded-xl text-xs font-bold transition flex items-center gap-1.5 bg-emerald-50 text-emerald-700 border border-emerald-200 hover:bg-emerald-100';
      } else {
        soundBtn.className = 'px-2.5 py-1 rounded-xl text-xs font-bold transition flex items-center gap-1.5 bg-slate-100 text-slate-500 border border-slate-200 hover:bg-slate-200';
      }
    }
  }

  /* ==================== UNIFIED NOTIFICATION CENTER ==================== */
  let notificationsList = [];

  function initNotificationCenter() {
    const btnNotif = $('btn-notifications');
    if (btnNotif) {
      btnNotif.onclick = () => {
        openModal('modal-notifications');
        updateSoundUI();
        loadNotifications();
      };
    }

    const soundToggle = $('btn-toggle-notif-sound');
    if (soundToggle) {
      soundToggle.onclick = () => {
        const on = Sound.toggle();
        updateSoundUI();
        if (on) Sound.blip();
      };
    }

    /* Notification volume slider — persisted via localStorage in sound.js */
    const volSlider = $('notif-volume-slider');
    const volLabel = $('notif-volume-label');
    if (volSlider) {
      const stored = Math.round(Sound.getVolume() * 100);
      volSlider.value = stored;
      if (volLabel) volLabel.textContent = stored + '%';
      volSlider.oninput = () => {
        const pct = Number(volSlider.value);
        Sound.setVolume(pct / 100);
        if (volLabel) volLabel.textContent = pct + '%';
      };
      volSlider.onchange = () => {
        if (Sound.enabled) Sound.cash();
      };
    }

    const testSoundBtn = $('btn-test-notif-sound');
    if (testSoundBtn) {
      testSoundBtn.onclick = () => {
        if (!Sound.enabled) {
          toast('🔇 Alerts are muted', 'Turn on "Alerts: ON" to hear test sounds.', true);
          return;
        }
        Sound.vendorPayment();
        setTimeout(() => Sound.cash(), 700);
      };
    }

    const markReadBtn = $('btn-notif-mark-read');
    if (markReadBtn) {
      markReadBtn.onclick = async () => {
        try {
          await API.call('/api/notifications/read-all', { method: 'POST' });
          notificationsList.forEach(n => n.isRead = true);
          renderNotifications(notificationsList);
          updateNotifBadge(0);
        } catch (e) {
          console.error('Failed to mark notifications read:', e);
        }
      };
    }
  }

  async function loadNotifications() {
    try {
      const res = await API.call('/api/notifications');
      notificationsList = res.notifications || [];
      updateNotifBadge(res.unreadCount || 0);
      renderNotifications(notificationsList);
    } catch (err) {
      console.warn('Could not fetch notifications from API:', err);
      renderNotifications(notificationsList);
    }
  }

  function updateNotifBadge(count) {
    const badge = $('notif-badge-count');
    const modalBadge = $('notif-modal-unread');
    if (badge) {
      if (count > 0) {
        badge.textContent = count > 99 ? '99+' : count;
        badge.classList.remove('hidden');
      } else {
        badge.classList.add('hidden');
      }
    }
    if (modalBadge) {
      modalBadge.textContent = count > 0 ? `${count} New` : '0 New';
    }
  }

  function renderNotifications(items) {
    const container = $('notif-list-container');
    const emptyState = $('notif-empty-state');
    if (!container) return;

    if (!items || items.length === 0) {
      container.innerHTML = '';
      if (emptyState) emptyState.classList.remove('hidden');
      return;
    }

    if (emptyState) emptyState.classList.add('hidden');

    container.innerHTML = items.map(n => {
      const icon = getNotifIcon(n.type);
      const isUnread = !n.isRead;
      const formattedTime = formatNotifTimestamp(n.createdAt);
      const ago = n.timeAgo || timeAgoOrDate(n.createdAt);
      const amountBadge = (n.amount && Number(n.amount) > 0)
        ? `<span class="px-2 py-0.5 rounded-full text-xs font-mono font-bold bg-emerald-500/10 text-emerald-600 border border-emerald-500/20 shrink-0">৳${Number(n.amount).toFixed(2)}</span>`
        : '';

      return `
        <div class="notif-item ${isUnread ? 'unread' : ''}" data-id="${n.id || ''}">
          <div class="notif-icon ${icon.bg}">
            ${icon.emoji}
          </div>
          <div class="flex-1 min-w-0">
            <div class="flex items-center justify-between gap-2">
              <span class="font-bold text-xs sm:text-sm text-slate-800 truncate">${esc(n.title)}</span>
              ${amountBadge}
            </div>
            <p class="text-xs text-slate-500 mt-0.5 leading-snug break-words">${esc(n.message)}</p>
            <div class="flex items-center gap-2 mt-1.5 text-[10px] text-slate-400 font-medium">
              <span>⏱️ ${ago}</span>
              <span>•</span>
              <span>${formattedTime}</span>
            </div>
          </div>
        </div>
      `;
    }).join('');
  }

  function getNotifIcon(type) {
    switch (type) {
      case 'P2P_RECEIVED':
        return { emoji: '💰', bg: 'bg-emerald-100 text-emerald-600' };
      case 'VENDOR_PAYMENT':
        return { emoji: '🏪', bg: 'bg-blue-100 text-blue-600' };
      case 'CASH_IN_COMPLETED':
      case 'BKASH_CASH_IN_COMPLETED':
        return { emoji: '⚡', bg: 'bg-amber-100 text-amber-600' };
      case 'SPLIT_REQUEST':
        return { emoji: '🍽️', bg: 'bg-purple-100 text-purple-600' };
      case 'SPLIT_ACCEPTED':
      case 'SPLIT_SETTLED':
        return { emoji: '🎉', bg: 'bg-green-100 text-green-600' };
      case 'SPLIT_DECLINED':
        return { emoji: '⚠️', bg: 'bg-red-100 text-red-600' };
      default:
        return { emoji: '🔔', bg: 'bg-slate-100 text-slate-600' };
    }
  }

  function formatNotifTimestamp(isoStr) {
    if (!isoStr) return '';
    try {
      const d = new Date(isoStr);
      if (isNaN(d.getTime())) return '';
      return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) + 
        ', ' + d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
    } catch (e) {
      return '';
    }
  }

  /* ==================== THEME MANAGEMENT (LANDING PAGE SLATE-950 PALETTE) ==================== */
  function initTheme() {
    const saved = localStorage.getItem('unipay-theme');
    const isDark = saved === 'dark';
    applyTheme(isDark);

    const btnTheme = $('btn-theme-toggle');
    if (btnTheme) {
      btnTheme.onclick = toggleTheme;
    }
  }

  function toggleTheme() {
    const isDark = document.documentElement.classList.contains('dark-theme');
    applyTheme(!isDark);
  }

  function applyTheme(isDark) {
    if (isDark) {
      document.documentElement.classList.add('dark-theme');
      document.body.classList.add('dark-theme');
      localStorage.setItem('unipay-theme', 'dark');
    } else {
      document.documentElement.classList.remove('dark-theme');
      document.body.classList.remove('dark-theme');
      localStorage.setItem('unipay-theme', 'light');
    }
    const icon = $('theme-toggle-icon');
    if (icon) {
      icon.textContent = isDark ? '☀️' : '🌙';
    }
    const btn = $('btn-theme-toggle');
    if (btn) {
      btn.title = isDark ? 'Switch to Light Mode' : 'Switch to Dark Mode';
    }
    // Also update notification sound icon if notification center is open
    updateSoundUI();
  }

  /* -------------------------------------------------------------- wiring */
  document.addEventListener('DOMContentLoaded', () => {
    // Keyboard accessibility for modals
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        const activeModal = document.querySelector('.modal.active, .modal:not(.hidden)');
        if (activeModal) closeModals();
      }
    });

    $('tab-login').onclick = () => switchTab('login');
    $('tab-register').onclick = () => switchTab('register');
    $('form-login').onsubmit = doLogin;
    $('form-register').onsubmit = doRegister;
    $('reg-role').onchange = () =>
      $('reg-vendor-fields').classList.toggle('hidden', $('reg-role').value !== 'VENDOR');
    $('btn-logout').onclick = logout;
    initNotificationCenter();
    initTheme();
    $('modal-backdrop').onclick = closeModals;
    document.querySelectorAll('.modal-close').forEach(b => b.onclick = closeModals);

    // Demo chip & profile cards in landing / auth view
    document.querySelectorAll('.demo-chip, .demo-profile-card').forEach(btn => {
      btn.onclick = (e) => {
        e.preventDefault();
        switchTab('login');
        const id = btn.getAttribute('data-id');
        $('login-id').value = id;
        $('login-pass').value = 'demo1234';
        const authSection = $('section-auth');
        if (authSection) authSection.scrollIntoView({ behavior: 'smooth' });
        doLogin();
      };
    });

    // Hero demo shortcut buttons
    const heroSaimon = $('btn-hero-demo-saimon');
    if (heroSaimon) {
      heroSaimon.onclick = () => {
        switchTab('login');
        $('login-id').value = '0112330140';
        $('login-pass').value = 'demo1234';
        doLogin();
      };
    }
    const heroCanteen = $('btn-hero-demo-canteen');
    if (heroCanteen) {
      heroCanteen.onclick = () => {
        switchTab('login');
        $('login-id').value = 'V-CAFE-01';
        $('login-pass').value = 'demo1234';
        doLogin();
      };
    }

    // Nav smooth triggers
    const navLogin = $('nav-login-link');
    if (navLogin) {
      navLogin.onclick = (e) => {
        e.preventDefault();
        switchTab('login');
        const s = $('section-auth');
        if (s) s.scrollIntoView({ behavior: 'smooth' });
        setTimeout(() => $('login-id').focus(), 300);
      };
    }
    const navRegister = $('nav-register-link');
    if (navRegister) {
      navRegister.onclick = (e) => {
        e.preventDefault();
        switchTab('register');
        const s = $('section-auth');
        if (s) s.scrollIntoView({ behavior: 'smooth' });
        setTimeout(() => $('reg-id').focus(), 300);
      };
    }

    $('btn-cashin').onclick = () => openCashInModal();
    $('btn-send-otp').onclick = sendOtp;
    $('btn-otp-back').onclick = () => {
      clearInterval(otpState.timerInterval);
      otpState.verifying = false;
      const stepA = $('cashin-step-a');
      const stepB = $('cashin-step-b');
      stepB.classList.add('hidden');
      stepB.classList.remove('wizard-slide-right', 'wizard-slide-left');
      stepA.classList.remove('hidden', 'wizard-slide-right');
      stepA.classList.add('wizard-slide-left');
    };
    $('btn-resend-otp').onclick = async () => {
      otpState.verifying = false;
      for (let i = 0; i < 6; i++) {
        $('otp-d' + i).value = '';
        $('otp-d' + i).classList.remove('filled', 'error');
      }
      hideError('cashin-error-b');
      const btn = $('btn-resend-otp');
      btn.disabled = true;
      btn.textContent = 'Sending…';
      try {
        const nonce = API.uuid();
        currentNonce = nonce;
        const res = await API.call('/api/otp/generate', {
          method: 'POST',
          body: {
            provider: window.__selectedProvider,
            mfsPhoneNumber: $('cashin-phone').value.trim(),
            amount: parseFloat($('cashin-amount').value),
            nonce
          }
        });
        otpState.otpId = res.otpId;
        otpState.nonce = nonce;
        $('otp-masked-phone').textContent = res.maskedPhone;
        startOtpTimer(res.ttlSeconds || 180);
        $('otp-d0').focus();
        toast('OTP Resent', 'A new OTP was sent to your MFS number.', false);
      } catch (err) {
        showError('cashin-error-b', 'Could not resend OTP: ' + err.message);
        btn.disabled = false;
      } finally {
        btn.textContent = 'Resend OTP';
      }
    };
    $('btn-verify-otp').onclick = verifyOtp;
    initOtpDigitInputs();

    $('btn-send').onclick = () => {
      currentNonce = null; $('send-to').value = ''; $('send-amount').value = '';
      hideError('send-error'); openModal('modal-send');
      API.call('/api/wallet').then(w => { $('send-balance').textContent = taka(w.balance); }).catch(() => {});
    };
    $('send-confirm').onclick = confirmSend;
    const sendAmtInput = $('send-amount');
    if (sendAmtInput) {
      sendAmtInput.onkeydown = (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          $('send-confirm').click();
        }
      };
    }

    $('btn-scan').onclick = () => {
      currentNonce = null; $('scan-manual').value = '';
      hideError('pay-error'); hideError('scan-cam-error');
      $('scan-step1').classList.remove('hidden');
      $('scan-step2').classList.add('hidden');
      openModal('modal-scan');
      startScanner();
    };
    $('scan-manual-btn').onclick = () => {
      const val = $('scan-manual').value.trim();
      if (!val) return;
      const target = parseQrPayload(val) || { vendorId: val.toUpperCase(), presetAmount: null, payload: null };
      beginPayment(target);
    };
    const scanManualInput = $('scan-manual');
    if (scanManualInput) {
      scanManualInput.onkeydown = (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          $('scan-manual-btn').click();
        }
      };
    }
    $('pay-back').onclick = () => {
      $('scan-step2').classList.add('hidden');
      $('scan-step1').classList.remove('hidden');
      startScanner();
    };
    $('pay-confirm').onclick = confirmVendorPay;

    // SplitPay wiring
    $('btn-split').onclick = openSplitModal;
    $('btn-open-split-modal').onclick = openSplitModal;
    $('btn-split-badge').onclick = () => {
      switchSplitTab('requests');
      $('dash-user').scrollIntoView({ behavior: 'smooth' });
    };

    $('tab-split-requests').onclick = () => switchSplitTab('requests');
    $('tab-split-bills').onclick = () => switchSplitTab('bills');

    $('split-mode-even').onclick = () => setSplitMode('EVEN');
    $('split-mode-custom').onclick = () => setSplitMode('CUSTOM');

    $('btn-add-friend').onclick = () => addSplitParticipant();
    $('split-friend-input').onkeydown = (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        addSplitParticipant();
      }
    };

    $('split-total').oninput = calcSplitAmounts;

    document.querySelectorAll('.split-preset-chip').forEach(btn => {
      btn.onclick = () => {
        $('split-title').value = btn.getAttribute('data-title');
      };
    });

    document.querySelectorAll('.quick-friend-btn').forEach(btn => {
      btn.onclick = () => {
        addSplitParticipant(btn.getAttribute('data-id'), btn.getAttribute('data-name'));
      };
    });

    $('split-confirm-btn').onclick = submitSplitBill;

    // History refresh
    $('btn-refresh-history').onclick = (e) => {
      spinRefreshIcon($('btn-refresh-history'));
      refreshUserData();
      refreshSplitData();
    };
    $('btn-refresh-vhistory').onclick = (e) => {
      spinRefreshIcon($('btn-refresh-vhistory'));
      refreshVendorData();
    };

    // Cashout history refresh
    $('btn-refresh-cashouts') && ($('btn-refresh-cashouts').onclick = () => {
      spinRefreshIcon($('btn-refresh-cashouts'));
      loadCashoutHistory();
    });

    // History expand/collapse: 4 recent rows by default, full list on demand.
    $('btn-toggle-history')   && ($('btn-toggle-history').onclick   = () => toggleHistoryFull('user-history'));
    $('btn-toggle-vhistory')  && ($('btn-toggle-vhistory').onclick  = () => toggleHistoryFull('vendor-history'));
    $('btn-toggle-cashouts')  && ($('btn-toggle-cashouts').onclick  = () => toggleHistoryFull('vendor-cashout-list'));

    // Vendor action card buttons
    $('btn-vendor-cashout') && ($('btn-vendor-cashout').onclick = openCashoutModal);
    $('btn-vendor-history') && ($('btn-vendor-history').onclick = () => {
      $('vendor-history').scrollIntoView({ behavior: 'smooth' });
    });
    $('btn-vendor-qr-action') && ($('btn-vendor-qr-action').onclick = () => {
      const qrEl = $('qr-image');
      if (qrEl) qrEl.scrollIntoView({ behavior: 'smooth' });
    });
    $('btn-vendor-edit-stall-action') && ($('btn-vendor-edit-stall-action').onclick = () => {
      const btn = $('btn-stall-edit');
      if (btn) btn.click();
    });
    $('btn-vendor-sales-report') && ($('btn-vendor-sales-report').onclick = () => {
      const hist = $('vendor-history');
      if (hist) hist.scrollIntoView({ behavior: 'smooth' });
    });

    // Vendor profile
    $('btn-stall-edit').onclick = async () => {
      hideError('profile-error');
      try {
        const p = await API.call('/api/vendor/profile');
        $('stall-name').value = p.stallName;
        $('stall-category').value = p.stallCategory;
      } catch (err) { $('stall-name').value = ''; }
      openModal('modal-profile');
    };
    $('profile-save').onclick = async () => {
      hideError('profile-error');
      try {
        await API.call('/api/vendor/profile', {
          method: 'PUT',
          body: { stallName: $('stall-name').value.trim(), stallCategory: $('stall-category').value }
        });
        closeModals();
        toast('🏪 Profile saved', 'Your QR is now active', false);
        refreshVendorData();
      } catch (err) { showError('profile-error', err.message); }
    };

    $('qr-mode-static').onclick = () => setQrMode('STATIC');
    $('qr-mode-dynamic').onclick = () => setQrMode('DYNAMIC');
    $('btn-qr-regen').onclick = () => loadQr();
    $('qr-amount').onchange = () => { if (qrMode === 'DYNAMIC') loadQr(); };
    $('btn-qr-copy').onclick = () => {
      const payload = window.__lastQrPayload;
      if (!payload) return;
      // Clipboard API rejects when the document is unfocused, permission is
      // denied, or the page is on an insecure origin. Fall back to the legacy
      // copy path so the button never dies silently.
      const legacyCopy = () => {
        const ta = document.createElement('textarea');
        ta.value = payload;
        ta.setAttribute('readonly', '');
        ta.style.cssText = 'position:fixed;top:-1000px;opacity:0;';
        document.body.appendChild(ta);
        ta.select();
        let ok = false;
        try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
        document.body.removeChild(ta);
        if (ok) toast('📋 Copied', 'QR payload copied to clipboard', false);
        else toast('⚠️ Copy failed', 'Please copy the QR payload manually', true);
      };
      if (!navigator.clipboard || !navigator.clipboard.writeText) return legacyCopy();
      navigator.clipboard.writeText(payload)
        .then(() => toast('📋 Copied', 'QR payload copied to clipboard', false))
        .catch(legacyCopy);
    };

    // Cashout channel selection
    document.querySelectorAll('.cashout-channel-btn').forEach(btn => {
      btn.onclick = () => {
        selectedCashoutChannel = btn.getAttribute('data-channel');
        document.querySelectorAll('.cashout-channel-btn').forEach(b => {
          b.classList.remove('border-emerald-500', 'bg-emerald-50');
        });
        btn.classList.add('border-emerald-500', 'bg-emerald-50');
        const isBank = selectedCashoutChannel === 'BANK';
        $('cashout-bank-fields').classList.toggle('hidden', !isBank);
        $('cashout-account-label').textContent = isBank ? 'Bank Account Number' : 'MFS Wallet Number';
        $('cashout-account').placeholder = isBank ? 'Account number' : '01XXXXXXXXX';
      };
    });
    $('cashout-confirm').onclick = confirmCashout;

    // Loyalty points
    const redeemBtn = $('btn-redeem-points');
    if (redeemBtn) redeemBtn.onclick = redeemPoints;

    // User profile modal wiring
    const btnProfile = $('btn-topbar-profile');
    if (btnProfile) btnProfile.onclick = openUserProfileModal;
    const btnSaveProf = $('btn-save-user-profile');
    if (btnSaveProf) btnSaveProf.onclick = saveUserProfile;

    // Avatar upload & preset buttons
    const btnUploadPhoto = $('btn-upload-photo');
    if (btnUploadPhoto) btnUploadPhoto.onclick = () => $('prof-avatar-input').click();
    const btnTriggerUpload = $('btn-trigger-upload');
    if (btnTriggerUpload) btnTriggerUpload.onclick = () => $('prof-avatar-input').click();
    const btnRemovePhoto = $('btn-remove-photo');
    if (btnRemovePhoto) btnRemovePhoto.onclick = () => {
      currentEditingAvatar = '';
      if ($('prof-avatar-input')) $('prof-avatar-input').value = '';
      renderAvatarPreview('');
    };
    const avatarInput = $('prof-avatar-input');
    if (avatarInput) avatarInput.onchange = handleAvatarFileSelect;
    document.querySelectorAll('.preset-avatar-chip').forEach(chip => {
      chip.onclick = () => {
        currentEditingAvatar = chip.dataset.avatar;
        renderAvatarPreview(currentEditingAvatar);
      };
    });

    // Expense & Earnings Tracker Period Toggle Buttons
    document.querySelectorAll('#expense-period-toggle .expense-period-btn').forEach(btn => {
      btn.onclick = () => setExpensePeriod(btn.dataset.period);
    });
    document.querySelectorAll('#vendor-expense-period-toggle .vendor-expense-period-btn').forEach(btn => {
      btn.onclick = () => setVendorExpensePeriod(btn.dataset.period);
    });

    boot();
  });
})();
