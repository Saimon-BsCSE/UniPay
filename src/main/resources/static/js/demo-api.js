/* UniPay offline demo backend.
 *
 * GitHub Pages can only serve static files, so on the published demo site this
 * file replaces the Spring Boot API with an in-memory stand-in. It reproduces
 * DataSeeder's 30-day campus sandbox and the exact response shapes the real
 * controllers return, so the frontend needs no changes to run against it.
 *
 * Nothing here runs in the real application: api.js only routes to DemoAPI when
 * window.UNIPAY_CONFIG.demo is true, and that flag is false in the jar.
 */
(function () {
  'use strict';

  var DEMO_PASSWORD = 'demo1234';
  // One threshold, as on the server: LoyaltyService.MIN_REDEMPTION_POINTS = 10.
  // The demo previously reported a threshold of 10 but refused to redeem below
  // 100, so the Redeem button was dead for every demo account.
  var REDEEM_MIN_POINTS = 10;

  /* ------------------------------------------------------------------ users */
  // fullName values mirror the live database, including the "(Verified)"
  // suffix, which the card-signature logic strips back to a first name.
  var USERS = [
    { userId: '0112330140',  fullName: 'Md Saimon Islam (Verified)', phoneNumber: '01712345640', role: 'STUDENT', avatarUrl: '\u{1F9D1}\u200D\u{1F4BB}', opening: 8500,  stallName: null,             stallCategory: null },
    { userId: '0112330378',  fullName: 'Osama Bin Mansur',           phoneNumber: '01712345641', role: 'STUDENT', avatarUrl: '\u{1F468}\u200D\u{1F4BB}', opening: 8500,  stallName: null,             stallCategory: null },
    { userId: '0112330586',  fullName: 'Md Sadman Sakib',            phoneNumber: '01712345642', role: 'STUDENT', avatarUrl: '\u{1F9D1}\u200D\u{1F4BB}', opening: 6000,  stallName: null,             stallCategory: null },
    { userId: '0111910667',  fullName: 'Dr. Nafees Ahmed',           phoneNumber: '01712345643', role: 'FACULTY', avatarUrl: null,                       opening: 14000, stallName: null,             stallCategory: null },
    { userId: 'UIU-STF-101', fullName: 'Rezaul Karim',               phoneNumber: '01712345644', role: 'STAFF',   avatarUrl: null,                       opening: 5000,  stallName: null,             stallCategory: null },
    { userId: 'V-CAFE-01',   fullName: 'UIU Central Canteen',        phoneNumber: '01712345645', role: 'VENDOR',  avatarUrl: null,                       opening: 0,     stallName: 'UIU Central Canteen',  stallCategory: 'CANTEEN' },
    { userId: 'V-BOOK-01',   fullName: 'UIU Book Shop',              phoneNumber: '01712345646', role: 'VENDOR',  avatarUrl: null,                       opening: 0,     stallName: 'UIU Book Shop',        stallCategory: 'BOOKSHOP' },
    { userId: 'V-FOOD-01',   fullName: 'Campus Samosa Corner',       phoneNumber: '01712345647', role: 'VENDOR',  avatarUrl: null,                       opening: 0,     stallName: 'Campus Samosa Corner', stallCategory: 'FOOD_STALL' }
  ];

  var byId = {};
  var byPhone = {};
  USERS.forEach(function (u) {
    byId[u.userId] = u;
    if (u.phoneNumber) byPhone[u.phoneNumber] = u;
    // Registered accounts created during the session are added to byId only;
    // mirror that in byPhone so they stay reachable by phone too.
  });

  /**
   * Resolves a recipient the way PaymentService does: university ID first, then
   * phone number. Keep this in step with PaymentService#resolveRecipient.
   */
  function resolveMember(key) {
    var k = String(key == null ? '' : key).trim();
    if (!k) return null;
    return byId[k] || byPhone[k] || null;
  }

  /* ------------------------------------------------------- session + state */
  var session = null;          // { token, userId }
  var now = new Date();

  var wallets = {};            // userId -> { balance, loyalty, updatedAt }
  var ledger = {};             // userId -> [txn] newest first
  var cashouts = {};           // vendorId -> [cashout]
  var notifications = {};      // userId -> [notification]
  var bills = [];              // splitpay bills
  var otpSessions = {};        // otpId -> { userId, amount, maskedPhone, purpose }

  function dayKey(d) {
    return d.getFullYear() + '-' +
      String(d.getMonth() + 1).padStart(2, '0') + '-' +
      String(d.getDate()).padStart(2, '0');
  }
  function daysAgoAt(days, hour, minute) {
    var d = new Date(now);
    d.setDate(d.getDate() - days);
    d.setHours(hour, minute, 0, 0);
    return d;
  }
  function iso(d) { return d.toISOString(); }

  /* --------------------------------------------------------- build history */
  // Mirrors DataSeeder.run() so the demo shows the same ledger the real app does.
  function seed() {
    var seq = 0;
    USERS.forEach(function (u) {
      wallets[u.userId] = { balance: 0, loyalty: u.role === 'VENDOR' ? 0 : 25, updatedAt: iso(now) };
      ledger[u.userId] = [];
      cashouts[u.userId] = [];
      notifications[u.userId] = [];

      if (u.opening > 0) {
        wallets[u.userId].balance += u.opening;
        pushTxn({
          id: 'TXN-SEED-' + String(++seq).padStart(3, '0') + '-' + u.userId,
          from: u.userId, to: u.userId, amount: u.opening,
          type: 'MFS_CASH_IN', at: daysAgoAt(31, 9, 0)
        });
      }
    });

    var saimon = '0112330140', osama = '0112330378', sakib = '0112330586';
    var nafees = '0111910667', rezaul = 'UIU-STF-101';
    var canteen = 'V-CAFE-01', bookShop = 'V-BOOK-01', samosa = 'V-FOOD-01';
    var VP = 'VENDOR_PAYMENT', P2P = 'P2P_TRANSFER';

    function add(day, hour, minute, sender, receiver, amount, type) {
      pushTxn({
        id: 'TXN-HIST-' + String(++seq).padStart(4, '0'),
        from: sender, to: receiver, amount: amount, type: type,
        at: daysAgoAt(day, hour, minute)
      });
    }

    for (var day = 29; day >= 0; day--) {
      add(day, 12, 15, saimon,  canteen,  65 + (day * 7) % 60,  VP);
      add(day, 12, 40, osama,   canteen,  60 + (day * 11) % 55, VP);
      add(day, 13, 5,  sakib,   canteen,  50 + (day * 13) % 45, VP);
      add(day, 13, 30, nafees,  canteen,  90 + (day * 17) % 70, VP);
      add(day, 13, 50, rezaul,  canteen,  45 + (day * 5) % 35,  VP);

      add(day, 16, 20, saimon,  samosa,   30 + (day * 3) % 25,  VP);
      add(day, 16, 35, osama,   samosa,   25 + (day * 7) % 30,  VP);
      add(day, 16, 50, sakib,   samosa,   20 + (day * 5) % 25,  VP);
      add(day, 17, 10, nafees,  samosa,   35 + (day * 9) % 30,  VP);
      add(day, 17, 25, rezaul,  samosa,   25 + (day * 4) % 20,  VP);

      if (day % 3 === 0) add(day, 14, 10, saimon, bookShop, 120 + (day * 13) % 150, VP);
      if (day % 3 === 1) add(day, 14, 30, osama,  bookShop, 140 + (day * 17) % 160, VP);
      if (day % 4 === 0) add(day, 14, 45, sakib,  bookShop,  80 + (day * 7) % 100,  VP);
      if (day % 2 === 0) add(day, 15, 15, nafees, bookShop, 220 + (day * 23) % 220, VP);

      if (day % 3 === 0) add(day, 18, 0,  osama,  saimon, 75 + (day * 5) % 50, P2P);
      if (day % 4 === 1) add(day, 18, 15, saimon, osama,  50 + (day * 8) % 40, P2P);
      if (day % 5 === 2) add(day, 18, 30, sakib,  saimon, 60 + (day * 6) % 60, P2P);
      if (day % 6 === 3) add(day, 18, 45, saimon, sakib,  45 + (day * 9) % 45, P2P);
      if (day % 5 === 4) add(day, 19, 0,  osama,  sakib,  55 + (day * 4) % 45, P2P);
      if (day % 7 === 2) add(day, 19, 15, rezaul, sakib,  50, P2P);
      if (day % 7 === 5) add(day, 19, 30, sakib,  rezaul, 60, P2P);
    }

    // Split-pay bills so the SplitPay tab is not empty for any account. Every
    // non-vendor creates at least one, and each also receives requests through
    // the participant pool above.
    [
      { creator: saimon, title: "Khan's Kitchen", total: 570, day: 3,
        statuses: ['ACCEPTED', 'PENDING', 'DECLINED', 'PENDING'], note: 'Lunch bill buddy' },
      { creator: osama, title: 'Study Group Dinner', total: 480, day: 6,
        statuses: ['ACCEPTED', 'ACCEPTED'], note: 'Rollout dinner' },
      { creator: sakib, title: 'Cafeteria Run', total: 260, day: 9,
        statuses: ['PENDING'], note: 'Snacks before the lab' },
      { creator: nafees, title: 'Project Printout', total: 900, day: 12,
        statuses: ['ACCEPTED', 'PENDING'], note: 'Final year project copies' },
      { creator: rezaul, title: 'Team Lunch', total: 720, day: 15,
        statuses: ['DECLINED', 'PENDING'], note: 'Office team lunch' }
    ].forEach(function (b) {
      mkBill({
        title: b.title, totalAmount: b.total, splitType: 'EVEN', note: b.note,
        creator: b.creator, createdAt: daysAgoAt(b.day, 13, 5), statuses: b.statuses
      });
    });

    // A few settled cash-outs for the vendors. Each vendor gets well over the 4-row
    // preview threshold so the collapsible cash-out list is exercised in the demo.
    [canteen, bookShop, samosa].forEach(function (v, idx) {
      for (var n = 0; n < (idx + 1) * 4 + 4; n++) {
        mkCashout(v, 15 + n * 5, daysAgoAt(1 + n, 18, 30));
      }
    });

    rebuildNotifications();
  }

  /* ------------------------------------------------------------- mutation */
  function pushTxn(t) {
    var rec = {
      transactionId: t.id, type: t.type, amount: t.amount,
      senderId: t.from, receiverId: t.to, timestamp: iso(t.at), at: t.at
    };
    // Both sides see the row; direction is derived per viewer in txnDto().
    ledger[t.from].push(rec);
    if (t.to !== t.from) ledger[t.to].push(rec);
    // A self-to-self row is a credit, not a debit.
    if (t.from === t.to) {
      wallets[t.from].balance += t.amount;
    } else {
      wallets[t.from].balance -= t.amount;
      wallets[t.to].balance += t.amount;
    }
    wallets[t.from].updatedAt = rec.timestamp;
    wallets[t.to].updatedAt = rec.timestamp;
  }

  function mkBill(spec) {
    // Never make the creator one of their own participants, and split evenly
    // across creator + participants the way SplitType.EVEN does. The pool is
    // every non-vendor, so each account both creates a bill and is asked to pay
    // one - otherwise the Requests tab is empty for whoever is signed in.
    var pool = ['0112330140', '0112330378', 'UIU-STF-101', '0112330586', '0111910667']
      .filter(function (id) { return id !== spec.creator && byId[id]; });
    var chosen = spec.statuses.map(function (_, i) { return pool[i % pool.length]; });
    var per = Number((spec.totalAmount / (chosen.length + 1)).toFixed(2));
    var billId = 'SPLIT-' + Math.random().toString(16).slice(2, 10);
    var parts = spec.statuses.map(function (st, i) {
      var pid = chosen[i];
      var accepted = st === 'ACCEPTED';
      return {
        requestId: 'REQ-' + Math.random().toString(16).slice(2, 10),
        participantId: pid,
        participantName: byId[pid].fullName,
        amount: per,
        status: st,
        transactionId: accepted ? 'TXN-SPLIT-' + billId + '-' + i : null,
        paidAt: accepted ? iso(spec.createdAt) : null,
        createdAt: iso(spec.createdAt)
      };
    });
    var collected = parts.filter(function (p) { return p.status === 'ACCEPTED'; })
      .reduce(function (s, p) { return s + p.amount; }, 0);
    bills.push({
      billId: billId, title: spec.title, totalAmount: spec.totalAmount,
      splitType: spec.splitType, status: 'ACTIVE', note: spec.note,
      creatorId: spec.creator, creatorName: byId[spec.creator].fullName,
      creatorShare: per, collectedAmount: collected,
      remainingAmount: Number((spec.totalAmount - collected - per).toFixed(2)),
      createdAt: iso(spec.createdAt), participants: parts
    });
  }

  /**
   * Creates a bill from what the user actually asked for, mirroring
   * SplitPayService#createBill. mkBill() is only for seeded history: it invents
   * three fixed participants at total/3, which is fine for demo data but wrong
   * for a bill a person just made.
   *
   * Kept in step with SplitPayService#createBill and #mapBillToResponse.
   */
  function createBill(creator, b) {
    var title = String(b.title == null ? '' : b.title).trim();
    if (!title) throw bad('Give the bill a title.');
    var total = Number(b.totalAmount);
    if (!(total >= 1)) throw bad('Total bill amount must be at least ?1.00.');

    var splitType = b.splitType === 'CUSTOM' ? 'CUSTOM' : 'EVEN';
    var items = Array.isArray(b.participants) ? b.participants : [];
    var resolved = [];
    var seen = {};
    for (var i = 0; i < items.length; i++) {
      var key = items[i] && items[i].userIdentifier;
      if (String(key == null ? '' : key).trim() === '') continue;
      var member = resolveMember(key);
      if (!member) {
        var nf = new Error('No registered UniPay user found for ID or phone: ' + String(key).trim());
        nf.status = 404; throw nf;
      }
      if (member.userId === creator.userId) throw bad('You cannot add yourself as a split participant.');
      if (seen[member.userId]) {
        throw bad('Duplicate participant in split: ' + member.fullName + ' (' + member.userId + ')');
      }
      seen[member.userId] = true;
      resolved.push({ user: member, amount: items[i].amount });
    }
    if (!resolved.length) throw bad('At least one valid participant is required.');

    // The creator's own share is whatever the participants do not cover.
    var participantTotal = 0;
    var parts = [];
    var billId = 'SPLIT-' + Math.random().toString(16).slice(2, 14);
    var createdAt = new Date();

    if (splitType === 'EVEN') {
      // Creator is one of the people sharing, hence n + 1.
      var even = round2(total / (resolved.length + 1));
      participantTotal = round2(even * resolved.length);
      for (var e = 0; e < resolved.length; e++) {
        parts.push(newPart(billId, resolved[e].user, even, createdAt));
      }
    } else {
      for (var c = 0; c < resolved.length; c++) {
        var amt = Number(resolved[c].amount);
        if (!(amt >= 0.5)) {
          throw bad('Custom contribution for ' + resolved[c].user.fullName + ' must be at least ?0.50.');
        }
        participantTotal = round2(participantTotal + amt);
        parts.push(newPart(billId, resolved[c].user, round2(amt), createdAt));
      }
      if (round2(participantTotal) > round2(total)) {
        throw bad('Total participant contributions (?' + round2(participantTotal).toFixed(2) +
          ') exceed the bill amount (?' + round2(total).toFixed(2) + ').');
      }
    }

    var creatorShare = Math.max(0, round2(total - participantTotal));
    var remaining = Math.max(0, round2(total - creatorShare));
    var bill = {
      billId: billId, title: title, totalAmount: round2(total),
      splitType: splitType, status: 'ACTIVE',
      note: b.note == null ? '' : String(b.note).trim(),
      creatorId: creator.userId, creatorName: creator.fullName,
      creatorShare: creatorShare, collectedAmount: 0, remainingAmount: remaining,
      createdAt: iso(createdAt), participants: parts
    };
    bills.push(bill);
    rebuildNotifications();
    return bill;
  }

  function newPart(billId, user, amount, at) {
    return {
      requestId: 'REQ-' + Math.random().toString(16).slice(2, 14),
      participantId: user.userId, participantName: user.fullName,
      amount: round2(amount), status: 'PENDING',
      transactionId: null, paidAt: null, createdAt: iso(at)
    };
  }

  function round2(n) {
    return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
  }

  function mkCashout(vendorId, amount, at) {
    var csId = 'CSH-' + Math.random().toString(16).slice(2, 10);
    var txnId = 'TXN-CSH-' + Math.random().toString(16).slice(2, 12);
    cashouts[vendorId].unshift({
      cashoutId: csId, vendorId: vendorId, amount: amount, channel: 'BKASH',
      accountNumber: '01712345699', accountHolderName: 'Saimon',
      bankName: '', branchName: '', transactionId: txnId,
      status: 'COMPLETED', createdAt: iso(at)
    });
    wallets[vendorId].balance -= amount;
    ledger[vendorId].push({
      transactionId: txnId, type: 'VENDOR_CASHOUT', amount: amount,
      senderId: vendorId, receiverId: vendorId, timestamp: iso(at), at: at
    });
  }

  function rebuildNotifications() {
    Object.keys(ledger).forEach(function (uid) {
      var list = [];
      ledger[uid].slice(0, 14).forEach(function (t, i) {
        var other = t.senderId === uid ? t.receiverId : t.senderId;
        var title, message, type;
        if (t.type === 'MFS_CASH_IN') {
          type = 'CASH_IN_COMPLETED'; title = 'Cash-in successful';
          message = '৳' + t.amount.toFixed(2) + ' was added to your wallet';
        } else if (t.type === 'VENDOR_CASHOUT') {
          type = 'CASH_OUT_COMPLETED'; title = 'Cash-out completed';
          message = '৳' + t.amount.toFixed(2) + ' withdrawn to your bKash wallet';
        } else if (t.senderId === uid) {
          type = 'PAYMENT_SENT'; title = 'Payment sent';
          message = 'You paid ' + byId[other].fullName + ' ৳' + t.amount.toFixed(2);
        } else {
          type = 'VENDOR_PAYMENT'; title = 'Payment received';
          message = byId[other].fullName + ' paid ৳' + t.amount.toFixed(2);
        }
        list.push({
          id: uid.length * 1000 + i, type: type, title: title, message: message,
          amount: t.amount, senderName: byId[other] ? byId[other].fullName : 'UniPay',
          transactionId: t.transactionId, isRead: i > 2,
          createdAt: t.timestamp, timeAgo: relTime(t.at)
        });
      });
      notifications[uid] = list;
    });
  }

  function relTime(d) {
    var s = Math.floor((Date.now() - d.getTime()) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + 'm ago';
    if (s < 86400) return Math.floor(s / 3600) + 'h ago';
    return Math.floor(s / 86400) + 'd ago';
  }

  /* ------------------------------------------------------------ projections */
  // Mirrors WalletService.toDto(): direction follows the effect on the viewer's
  // wallet. The three self-to-self types must be classified by type, not by sender
  // identity — a cash-out debits the wallet even though it is booked vendor->vendor.
  function txnDto(t, viewerId) {
    var selfRow = t.senderId === t.receiverId;
    var outgoing;
    switch (t.type) {
      case 'MFS_CASH_IN':
      case 'LOYALTY_REDEMPTION': outgoing = false; break;   // credits
      case 'VENDOR_CASHOUT':     outgoing = true;  break;   // debits
      default: outgoing = !selfRow && t.senderId === viewerId;
    }
    var otherId = outgoing ? t.receiverId : t.senderId;
    return {
      transactionId: t.transactionId,
      type: t.type,
      amount: t.amount,
      direction: outgoing ? 'OUT' : 'IN',
      counterpartyId: otherId,
      counterpartyName: byId[otherId] ? byId[otherId].fullName : 'UniPay',
      timestamp: t.timestamp
    };
  }

  function publicUser(u) {
    return {
      userId: u.userId, fullName: u.fullName, phoneNumber: u.phoneNumber,
      role: u.role, avatarUrl: u.avatarUrl
    };
  }

  function requireSession() {
    if (!session) { var e = new Error('Not authenticated'); e.status = 401; throw e; }
    return byId[session.userId];
  }

  function expenseSummary(userId, period) {
    var u = byId[userId];
    var isVendor = u.role === 'VENDOR';
    var days, label;
    if (period === 'DAILY')        { days = 1;  label = 'Today'; }
    else if (period === 'MONTHLY') { days = 30; label = 'Last 30 Days'; }
    else                           { days = 7;  label = 'Last 7 Days'; }

    var totals = {};
    ledger[userId].forEach(function (t) {
      var hit = isVendor
        ? (t.receiverId === userId && t.type === 'VENDOR_PAYMENT')
        : (t.senderId === userId &&
           (t.type === 'VENDOR_PAYMENT' || t.type === 'P2P_TRANSFER' || t.type === 'SPLIT_PAY'));
      if (!hit) return;
      var k = dayKey(t.at);
      totals[k] = (totals[k] || 0) + t.amount;
    });

    var points = [], total = 0;
    for (var i = days - 1; i >= 0; i--) {
      var d = new Date(now);
      d.setDate(d.getDate() - i);
      var k = dayKey(d);
      var amt = Number((totals[k] || 0).toFixed(2));
      total += amt;
      points.push({ date: k, amount: amt });
    }
    return {
      period: period || 'WEEKLY', label: label,
      total: Number(total.toFixed(2)), points: points, isVendor: isVendor
    };
  }

  /* ------------------------------------------------------------- QR image */
  // The real endpoint returns a scannable QR PNG. Encoding a real QR in the
  // demo would be a lot of code for no benefit, so this draws a clearly
  // labelled placeholder instead of pretending to be scannable.
  function qrBlob(payload) {
    var c = document.createElement('canvas');
    c.width = 320; c.height = 320;
    var g = c.getContext('2d');
    g.fillStyle = '#ffffff'; g.fillRect(0, 0, 320, 320);
    g.fillStyle = '#0f172a'; g.fillRect(0, 0, 320, 54);
    g.fillStyle = '#ffffff';
    g.font = 'bold 20px system-ui, sans-serif';
    g.textAlign = 'center';
    g.fillText('UniPay DEMO QR', 160, 35);
    g.fillStyle = '#94a3b8';
    for (var y = 70; y < 300; y += 8) {
      for (var x = 16; x < 304; x += 8) {
        if (((x * 7 + y * 13) % 11) < 4) g.fillRect(x, y, 6, 6);
      }
    }
    g.fillStyle = '#ffffff'; g.fillRect(96, 150, 128, 128);
    g.strokeStyle = '#0f172a'; g.lineWidth = 3;
    g.strokeRect(96, 150, 128, 128);
    g.fillStyle = '#0f172a';
    g.font = 'bold 13px system-ui, sans-serif';
    var label = String(payload || '');
    var max = 22;
    if (label.length > max) label = label.slice(0, max) + '…';
    g.fillText(label, 160, 206);
    g.font = '11px system-ui, sans-serif';
    g.fillStyle = '#475569';
    g.fillText('simulated — not scannable', 160, 226);
    return new Promise(function (resolve) {
      c.toBlob(function (b) { resolve(b); }, 'image/png');
    });
  }

  /* ---------------------------------------------------------------- router */
  function split(path) {
    var i = path.indexOf('?');
    var p = i < 0 ? path : path.slice(0, i);
    var q = {};
    if (i >= 0) {
      path.slice(i + 1).split('&').forEach(function (kv) {
        if (!kv) return;
        var j = kv.indexOf('=');
        var k = decodeURIComponent(j < 0 ? kv : kv.slice(0, j));
        q[k] = decodeURIComponent((j < 0 ? '' : kv.slice(j + 1)).replace(/\+/g, ' '));
      });
    }
    return { p: p, q: q };
  }

  function bad(msg) { var e = new Error(msg); e.status = 400; return e; }

  var HANDLERS = {
    'POST /api/auth/login': function (b) {
      var id = String(b.userId || b.user_id || b.username || '').trim();
      var u = byId[id];
      if (!u) throw bad('No account found for "' + id + '".');
      if ((b.password || '') !== DEMO_PASSWORD) throw bad('Incorrect password.');
      session = { token: 'demo-' + id + '-' + Date.now(), userId: id };
      return { token: session.token, user: publicUser(u) };
    },

    'POST /api/auth/register': function (b) {
      var id = String(b.userId || '').trim();
      if (byId[id]) throw bad('That ID is already registered.');
      var u = {
        userId: id, fullName: b.fullName || 'New Member',
        phoneNumber: b.phoneNumber || '01700000000', role: 'STUDENT',
        avatarUrl: null, opening: 0, stallName: null, stallCategory: null
      };
      byId[id] = u;
      if (u.phoneNumber) byPhone[u.phoneNumber] = u;
      wallets[id] = { balance: 0, loyalty: 25, updatedAt: iso(now) };
      ledger[id] = []; cashouts[id] = []; notifications[id] = [];
      session = { token: 'demo-' + id + '-' + Date.now(), userId: id };
      return { token: session.token, user: publicUser(u) };
    },

    'GET /api/auth/me': function () { return publicUser(requireSession()); },

    'GET /api/user/profile': function () {
      var u = requireSession();
      return {
        userId: u.userId, fullName: u.fullName, phoneNumber: u.phoneNumber, role: u.role,
        stallName: u.stallName, stallCategory: u.stallCategory, avatarUrl: u.avatarUrl,
        createdAt: iso(daysAgoAt(7, 12, 0))
      };
    },

    'PUT /api/user/profile': function (b) {
      var u = requireSession();
      if (b.fullName) u.fullName = b.fullName;
      if (b.phoneNumber) {
        // Re-key the phone index so the new number stays payable and the old
        // one stops resolving.
        if (byPhone[u.phoneNumber] === u) delete byPhone[u.phoneNumber];
        u.phoneNumber = b.phoneNumber;
        byPhone[u.phoneNumber] = u;
      }
      if (b.avatarUrl !== undefined) u.avatarUrl = b.avatarUrl;
      if (b.stallName) u.stallName = b.stallName;
      if (b.stallCategory) u.stallCategory = b.stallCategory;
      return {
        userId: u.userId, fullName: u.fullName, phoneNumber: u.phoneNumber, role: u.role,
        stallName: u.stallName, stallCategory: u.stallCategory, avatarUrl: u.avatarUrl,
        createdAt: iso(daysAgoAt(7, 12, 0))
      };
    },

    'GET /api/wallet': function () {
      var u = requireSession();
      var w = wallets[u.userId];
      return {
        userId: u.userId, fullName: u.fullName, role: u.role,
        balance: Number(w.balance.toFixed(2)),
        updatedAt: w.updatedAt, loyaltyPoints: Number(w.loyalty.toFixed(4))
      };
    },

    'GET /api/wallet/transactions': function (b, q) {
      var u = requireSession();
      var size = parseInt(q.size, 10) || 25;
      var num = parseInt(q.page, 10) || 0;
      var all = ledger[u.userId].slice().sort(function (a, b) { return b.at - a.at; });
      var content = all.slice(num * size, num * size + size).map(function (t) {
        return txnDto(t, u.userId);
      });
      return {
        content: content,
        page: {
          size: size, number: num, totalElements: all.length,
          totalPages: Math.max(1, Math.ceil(all.length / size))
        }
      };
    },

    'GET /api/wallet/expense-summary': function (b, q) {
      return expenseSummary(requireSession().userId, (q.period || 'WEEKLY').toUpperCase());
    },

    'GET /api/loyalty/balance': function () {
      var w = wallets[requireSession().userId];
      return {
        points: Number(w.loyalty.toFixed(4)),
        threshold: REDEEM_MIN_POINTS,
        eligible: w.loyalty >= REDEEM_MIN_POINTS
      };
    },

    'POST /api/loyalty/redeem': function () {
      var u = requireSession();
      var w = wallets[u.userId];
      if (w.loyalty < REDEEM_MIN_POINTS) {
        var e = new Error('Insufficient loyalty points. You need at least ' + REDEEM_MIN_POINTS +
          ' coins to redeem (1 coin = ?1). Your balance: ' + Number(w.loyalty.toFixed(2)) + ' pts.');
        e.status = 402; throw e;
      }
      var pts = w.loyalty, cash = Number(pts.toFixed(2));
      w.loyalty = 0;
      pushTxn({
        id: 'LPT-' + Math.random().toString(16).slice(2, 20),
        from: u.userId, to: u.userId, amount: cash,
        type: 'LOYALTY_REDEMPTION', at: new Date()
      });
      rebuildNotifications();
      return {
        pointsRedeemed: pts, cashValue: cash, newWalletBalance: Number(w.balance.toFixed(2)),
        remainingPoints: 0,
        message: '🎁 ' + pts.toFixed(2) + ' points redeemed for ৳' + cash.toFixed(2) + '!'
      };
    },

    'GET /api/mfs/providers': function () {
      return [
        { code: 'BKASH',  name: 'bKash',  icon: '\u{1F4F1}', logoUrl: 'img/bkash.svg',  minAmount: 20, maxAmount: 50000 },
        { code: 'NAGAD',  name: 'Nagad',  icon: '\u{1F7E2}', logoUrl: 'img/nagad.svg',  minAmount: 20, maxAmount: 50000 },
        { code: 'ROCKET', name: 'Rocket', icon: '\u{1F680}', logoUrl: 'img/rocket.svg', minAmount: 20, maxAmount: 50000 }
      ];
    },

    'GET /api/notifications': function () {
      var u = requireSession();
      var list = notifications[u.userId];
      return { unreadCount: list.filter(function (n) { return !n.isRead; }).length, notifications: list };
    },

    'POST /api/notifications/read-all': function () {
      var u = requireSession();
      notifications[u.userId].forEach(function (n) { n.isRead = true; });
      return { unreadCount: 0, updated: notifications[u.userId].length };
    },

    /* -------------------------------------------------------------- vendor */
    'GET /api/vendor/profile': function () {
      var u = requireSession();
      if (u.role !== 'VENDOR') { var e = new Error('Vendor access required'); e.status = 403; throw e; }
      return {
        vendorId: u.userId, stallName: u.stallName, stallCategory: u.stallCategory,
        qrCodeIdentifier: 'UNIPAY:VENDOR:' + u.userId
      };
    },

    'PUT /api/vendor/profile': function (b) {
      var u = requireSession();
      if (b.stallName) u.stallName = b.stallName;
      if (b.stallCategory) u.stallCategory = b.stallCategory;
      return {
        vendorId: u.userId, stallName: u.stallName, stallCategory: u.stallCategory,
        qrCodeIdentifier: 'UNIPAY:VENDOR:' + u.userId
      };
    },

    'GET /api/vendor/stats': function () {
      var u = requireSession();
      var today = dayKey(new Date());
      var rows = ledger[u.userId].filter(function (t) {
        return t.receiverId === u.userId && t.type === 'VENDOR_PAYMENT' && dayKey(t.at) === today;
      });
      return {
        todaySales: Number(rows.reduce(function (s, t) { return s + t.amount; }, 0).toFixed(2)),
        todayCount: rows.length
      };
    },

    'GET /api/vendor/qr': function (b, q) {
      var u = requireSession();
      var type = q.type || 'STATIC';
      return {
        type: type, vendorId: u.userId, payload: 'UNIPAY:VENDOR:' + u.userId,
        presetAmount: q.amount ? Number(q.amount) : null,
        expiresAt: type === 'DYNAMIC' ? iso(new Date(Date.now() + 15 * 60000)) : null
      };
    },

    'GET /api/vendor/qr.png': function () {
      var u = requireSession();
      return qrBlob('UNIPAY:VENDOR:' + u.userId);
    },

    'GET /api/vendor/cashouts': function () {
      var u = requireSession();
      return cashouts[u.userId].slice();
    },

    'POST /api/vendor/cashout': function (b) {
      var u = requireSession();
      var amount = Number(b.amount);
      if (!(amount > 0)) throw bad('Enter an amount to withdraw.');
      if (amount > wallets[u.userId].balance) throw bad('Amount is more than your available balance.');
      var csId = 'CSH-' + Math.random().toString(16).slice(2, 10);
      var txnId = 'TXN-CSH-' + Math.random().toString(16).slice(2, 12);
      var at = new Date();
      var dest = b.accountNumber || b.destination || '';
      cashouts[u.userId].unshift({
        cashoutId: csId, vendorId: u.userId, amount: amount,
        channel: b.channel || 'BKASH', accountNumber: dest,
        accountHolderName: b.accountHolderName || '', bankName: b.bankName || '',
        branchName: b.branchName || '', transactionId: txnId,
        status: 'COMPLETED', createdAt: iso(at)
      });
      wallets[u.userId].balance -= amount;
      ledger[u.userId].push({
        transactionId: txnId, type: 'VENDOR_CASHOUT', amount: amount,
        senderId: u.userId, receiverId: u.userId, timestamp: iso(at), at: at
      });
      rebuildNotifications();
      return {
        cashoutId: csId, transactionId: txnId, amount: amount,
        channel: b.channel || 'BKASH', destination: dest,
        newBalance: Number(wallets[u.userId].balance.toFixed(2)),
        timestamp: iso(at), message: 'Cash-out completed.'
      };
    },

    /* ------------------------------------------------------------ payments */
    'POST /api/payments/p2p': function (b) {
      var u = requireSession();
      var amount = Number(b.amount);
      // The field is `recipient` and may be a university ID or a phone number,
      // exactly as the real endpoint accepts.
      var target = resolveMember(b.recipient);
      if (!target) throw bad('Choose a valid UniPay member to pay.');
      if (target.userId === u.userId) throw bad('You cannot send money to yourself.');
      if (!(amount > 0)) throw bad('Enter an amount greater than zero.');
      if (amount > wallets[u.userId].balance) throw bad('Amount is more than your available balance.');
      pushTxn({
        id: 'TXN-P2P-' + Math.random().toString(16).slice(2, 20),
        from: u.userId, to: target.userId, amount: amount,
        type: 'P2P_TRANSFER', at: new Date()
      });
      rebuildNotifications();
      return {
        transactionId: 'TXN-P2P', amount: amount, receiverName: target.fullName,
        receiverId: target.userId, payerNewBalance: Number(wallets[u.userId].balance.toFixed(2)),
        message: 'Sent to ' + target.fullName
      };
    },

    'POST /api/payments/vendor': function (b) {
      var u = requireSession();
      var amount = Number(b.amount);
      var target = byId[String(b.vendorId || '')];
      if (!target || target.role !== 'VENDOR') throw bad('Choose a valid campus vendor.');
      if (amount > wallets[u.userId].balance) throw bad('Amount is more than your available balance.');
      pushTxn({
        id: 'TXN-' + Math.random().toString(16).slice(2, 20),
        from: u.userId, to: target.userId, amount: amount,
        type: 'VENDOR_PAYMENT', at: new Date()
      });
      rebuildNotifications();
      return {
        transactionId: 'TXN', amount: amount, receiverName: target.stallName || target.fullName,
        receiverId: target.userId, payerNewBalance: Number(wallets[u.userId].balance.toFixed(2)),
        message: 'Paid ' + (target.stallName || target.fullName)
      };
    },

    'POST /api/bkash/checkout': function () {
      // There is no gateway in the demo, so this reports the failure honestly
      // instead of opening a bKash tab that cannot complete.
      throw bad('bKash checkout is disabled on the static demo site. Use a local run to test real payments.');
    },

    /* ----------------------------------------------------------------- MFS */
    'POST /api/otp/generate': function (b) {
      var u = requireSession();
      var otpId = 'OTP-' + Math.random().toString(16).slice(2, 14);
      var masked = u.phoneNumber.replace(/^(\d{3})\d+(\d{2})$/, '$1*****$2');
      otpSessions[otpId] = {
        userId: u.userId, amount: Number(b.amount || 0),
        purpose: b.purpose || 'MFS_CASH_IN', maskedPhone: masked
      };
      return {
        otpId: otpId, maskedPhone: masked, expiresInSeconds: 300,
        devHint: 'Demo mode: use OTP 123456', provider: b.provider || 'BKASH'
      };
    },

    'POST /api/otp/verify': function () {
      var b0 = arguments[0] || {};
      var s = otpSessions[b0.otpId];
      if (!s) throw bad('This OTP session expired. Please start again.');
      if (String(b0.otp || b0.code) !== '123456') {
        var e = new Error('That OTP is not correct.'); e.status = 400; throw e;
      }
      delete otpSessions[b0.otpId];
      var u = byId[s.userId];
      pushTxn({
        id: 'TXN-' + Math.random().toString(16).slice(2, 20),
        from: u.userId, to: u.userId, amount: s.amount,
        type: 'MFS_CASH_IN', at: new Date()
      });
      var w = wallets[u.userId];
      w.loyalty += s.amount / 100;
      rebuildNotifications();
      return {
        transactionId: 'TXN', amount: s.amount,
        newBalance: Number(w.balance.toFixed(2)),
        newLoyaltyPoints: Number(w.loyalty.toFixed(4)),
        message: 'Cash-in complete.'
      };
    },

    /* ------------------------------------------------------------ splitpay */
    'GET /api/splitpay/my-bills': function () {
      var uid = requireSession().userId;
      // A list, matching the server. Returning a single object here made the
      // Bills tab look permanently empty: renderSplitBills() checks .length and
      // then maps, and a plain object has no length, so it fell through to the
      // empty state every time without raising an error.
      return bills.filter(function (x) { return x.creatorId === uid; });
    },

    'GET /api/splitpay/my-requests': function () {
      var uid = requireSession().userId;
      var out = [];
      bills.forEach(function (x) {
        x.participants.forEach(function (p) {
          if (p.participantId === uid) {
            out.push({
              requestId: p.requestId, billId: x.billId, billTitle: x.title,
              amount: p.amount, status: p.status, creatorName: x.creatorName,
              createdAt: p.createdAt, paidAt: p.paidAt, note: x.note
            });
          }
        });
      });
      return out;
    },

    'GET /api/splitpay/pending-count': function () {
      var uid = requireSession().userId;
      var n = 0;
      bills.forEach(function (x) {
        x.participants.forEach(function (p) {
          if (p.participantId === uid && p.status === 'PENDING') n++;
        });
      });
      return { pendingCount: n };
    },

    'POST /api/splitpay/bills': function (b) {
      var u = requireSession();
      var created = createBill(byId[u.userId], b);
      return {
        billId: created.billId, title: created.title,
        totalAmount: created.totalAmount, splitType: created.splitType,
        status: created.status, note: created.note,
        creatorId: created.creatorId, creatorName: created.creatorName,
        creatorShare: created.creatorShare, collectedAmount: created.collectedAmount,
        remainingAmount: created.remainingAmount, createdAt: created.createdAt,
        participants: created.participants,
        message: 'Split bill created and shared.'
      };
    }
  };

  // POST endpoints that take a path variable, e.g. .../requests/{id}/accept
  var PARAM_ROUTES = [
    { method: 'POST', re: /^\/api\/splitpay\/requests\/([^/]+)\/accept$/, run: function (id) { return settleRequest(id, 'ACCEPTED'); } },
    { method: 'POST', re: /^\/api\/splitpay\/requests\/([^/]+)\/decline$/, run: function (id) { return settleRequest(id, 'DECLINED'); } },
    { method: 'POST', re: /^\/api\/splitpay\/bills\/([^/]+)\/cancel$/,      run: function (id) { return cancelBill(id); } }
  ];

  function findPart(requestId) {
    for (var i = 0; i < bills.length; i++) {
      var parts = bills[i].participants;
      for (var j = 0; j < parts.length; j++) {
        if (parts[j].requestId === requestId) return { bill: bills[i], part: parts[j] };
      }
    }
    var e = new Error('That request no longer exists.'); e.status = 404; throw e;
  }

  function conflict(msg) {
    var e = new Error(msg); e.status = 409; return e;
  }

  /**
   * Mirrors SplitPayService#acceptRequest / #declineRequest, including the
   * guards. Without the status check a request could be accepted repeatedly,
   * which debited the participant and credited the creator more than once.
   */
  function settleRequest(requestId, status) {
    var u = requireSession();
    var hit = findPart(requestId);
    if (hit.part.participantId !== u.userId) {
      var f = new Error(status === 'ACCEPTED'
        ? 'You are not authorized to accept this split request.'
        : 'You are not authorized to decline this split request.');
      f.status = 403; throw f;
    }
    if (hit.part.status !== 'PENDING') {
      throw conflict('This request is already ' + hit.part.status + '.');
    }
    if (status === 'ACCEPTED' && hit.bill.status !== 'ACTIVE') {
      throw conflict('This bill is no longer active (status: ' + hit.bill.status + ').');
    }

    hit.part.status = status;
    if (status === 'ACCEPTED') {
      var share = round2(hit.part.amount);
      var bal = wallets[u.userId].balance;
      if (bal < share) {
        throw conflict('Insufficient balance to pay split share. Required: ?' +
          share.toFixed(2) + ', Available: ?' + round2(bal).toFixed(2) + '.');
      }
      hit.part.paidAt = iso(new Date());
      hit.part.transactionId = 'TXN-SPLIT-' + Math.random().toString(16).slice(2, 12);
      pushTxn({
        id: hit.part.transactionId, from: u.userId, to: hit.bill.creatorId,
        amount: share, type: 'SPLIT_PAY', at: new Date()
      });
      hit.bill.collectedAmount = round2(hit.bill.collectedAmount + share);
      hit.bill.remainingAmount = Math.max(0, round2(hit.bill.remainingAmount - share));
      rebuildNotifications();
      return {
        requestId: requestId, transactionId: hit.part.transactionId, amount: share,
        payerNewBalance: round2(wallets[u.userId].balance), message: 'Split Paid'
      };
    }
    rebuildNotifications();
    return { requestId: requestId, message: 'Request declined.' };
  }

  /** Mirrors SplitPayService#cancelBill: creator-only, ACTIVE-only, and it
   *  cancels the outstanding requests too. */
  function cancelBill(billId) {
    var u = requireSession();
    var bill = bills.filter(function (x) { return x.billId === billId; })[0];
    if (!bill) { var e = new Error('Split bill not found: ' + billId); e.status = 404; throw e; }
    if (bill.creatorId !== u.userId) {
      var e2 = new Error('You can only cancel bills created by you.'); e2.status = 403; throw e2;
    }
    if (bill.status !== 'ACTIVE') {
      throw conflict('Cannot cancel bill with status ' + bill.status);
    }
    bill.status = 'CANCELLED';
    bill.participants.forEach(function (p) {
      if (p.status === 'PENDING') p.status = 'CANCELLED';
    });
    rebuildNotifications();
    return { message: 'Bill cancelled.', billId: billId };
  }

  /* --------------------------------------------------------------- public */
  function delay(fn) {
    // A small delay keeps loading spinners honest instead of flickering.
    return new Promise(function (resolve, reject) {
      setTimeout(function () {
        try { resolve(fn()); } catch (e) { reject(e); }
      }, 60);
    });
  }

  function call(path, opts) {
    opts = opts || {};
    var parts = split(path);
    var key = (opts.method || 'GET').toUpperCase() + ' ' + parts.p;
    var handler = HANDLERS[key];
    if (handler) {
      return delay(function () { return handler(opts.body || {}, parts.q); });
    }
    for (var i = 0; i < PARAM_ROUTES.length; i++) {
      var r = PARAM_ROUTES[i];
      if (r.method === (opts.method || 'GET').toUpperCase()) {
        var m = parts.p.match(r.re);
        if (m) return delay(function () { return r.run(decodeURIComponent(m[1])); });
      }
    }
    return Promise.reject(Object.assign(new Error('Demo backend has no route for ' + key), { status: 404 }));
  }

  function blob(path) {
    var parts = split(path);
    try { requireSession(); } catch (e) { return Promise.reject(e); }
    if (parts.p === '/api/vendor/qr.png') return qrBlob('UNIPAY:VENDOR:' + session.userId);
    return Promise.reject(new Error('Demo backend has no image for ' + parts.p));
  }

  seed();

  window.DemoAPI = { call: call, blob: blob, password: DEMO_PASSWORD };
})();
