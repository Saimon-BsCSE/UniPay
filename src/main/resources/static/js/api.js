/* UniPay API client — thin fetch wrapper with JWT header + consistent error handling. */
(function () {
  const CFG = window.UNIPAY_CONFIG || { demo: false, apiBase: '' };
  const DEMO = !!CFG.demo;
  // Absolute when hosted under a subpath (GitHub Pages) or split from the API.
  const BASE = (CFG.apiBase || '').replace(/\/$/, '');

  const API = {
    demo: DEMO,
    token: localStorage.getItem('unipay_token') || null,
    user: JSON.parse(localStorage.getItem('unipay_user') || 'null'),

    saveSession(token, user) {
      this.token = token;
      this.user = user;
      localStorage.setItem('unipay_token', token);
      localStorage.setItem('unipay_user', JSON.stringify(user));
    },

    clearSession() {
      this.token = null;
      this.user = null;
      localStorage.removeItem('unipay_token');
      localStorage.removeItem('unipay_user');
    },

    async call(path, { method = 'GET', body, params } = {}) {
      // The static demo site has no server, so it answers from memory instead.
      if (DEMO && window.DemoAPI) return window.DemoAPI.call(path, { method, body, params });

      const headers = { 'Content-Type': 'application/json' };
      if (this.token) headers['Authorization'] = 'Bearer ' + this.token;

      // Append query-string params if provided (e.g. bKash checkout, OTP resend)
      let url = path;
      if (params && Object.keys(params).length > 0) {
        const qs = new URLSearchParams(
          Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== null))
        ).toString();
        url = path + (path.includes('?') ? '&' : '?') + qs;
      }

      const res = await fetch(BASE + url, {
        method, headers,
        // body:null means no body; body:undefined also means no body; only send when defined & non-null
        body: (body !== undefined && body !== null) ? JSON.stringify(body) : undefined
      });

      if (res.status === 401 && !path.startsWith('/api/auth')) {
        this.clearSession();
        window.location.reload();
        throw new Error('Session expired — please log in again.');
      }

      const isJson = (res.headers.get('content-type') || '').includes('json');
      const data = isJson ? await res.json() : await res.text();

      if (!res.ok) {
        const message = (data && data.message) ? data.message : ('Request failed (' + res.status + ')');
        const err = new Error(message);
        err.status = res.status;
        throw err;
      }
      return data;
    },

    async blob(path) {
      if (DEMO && window.DemoAPI) return window.DemoAPI.blob(path);
      const res = await fetch(BASE + path, { headers: { 'Authorization': 'Bearer ' + this.token } });
      if (!res.ok) throw new Error('Failed to load ' + path);
      return res.blob();
    },

    uuid() {
      return (crypto.randomUUID && crypto.randomUUID()) ||
        'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
          const r = Math.random() * 16 | 0;
          return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
        });
    }
  };

  window.API = API;
})();
