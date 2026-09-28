/* UniPay real-time client — STOMP over SockJS with:
   • JWT authentication at CONNECT (falls back to ?token= handshake param)
   • automatic reconnection with exponential backoff (1s → 2s → 4s … cap 30s)
   • polling fallback when the socket stays down (proposal risk: "WebSocket
     Connection Drops")                                            */
(function () {
  const WS = {
    client: null,
    connected: false,
    manuallyClosed: false,
    attempt: 0,
    pollTimer: null,
    onEvent: null,          // (event) => void  — called for every pushed WsEvent
    onStateChange: null,    // (connected:boolean) => void

    start(onEvent, onStateChange) {
      this.stop();
      this.manuallyClosed = false;
      this.onEvent = onEvent;
      this.onStateChange = onStateChange;
      this.connect();
    },

    connect() {
      if (this.manuallyClosed || !window.SockJS || !window.Stomp) {
        this.startPolling();
        return;
      }
      const self = this;
      const socket = new SockJS('/ws?token=' + encodeURIComponent(API.token || ''));
      const client = Stomp.over(socket);
      client.debug = null; // silence console
      this.client = client;

      client.connect({ Authorization: 'Bearer ' + (API.token || '') },
        function () {          // CONNECTED
          self.connected = true;
          self.attempt = 0;
          self.stopPolling();
          if (self.onStateChange) self.onStateChange(true);
        },
        function () {          // ERROR / closed
          self.connected = false;
          if (self.onStateChange) self.onStateChange(false);
          if (self.manuallyClosed) return;
          const delay = Math.min(30000, 1000 * Math.pow(2, self.attempt++));
          setTimeout(() => { if (!self.manuallyClosed) self.connect(); }, delay);
          if (self.attempt >= 3) self.startPolling(); // socket still useful, polling covers the gap
        });

      // subscribe lazily once connected
      const waitForConnect = setInterval(() => {
        if (client.connected) {
          clearInterval(waitForConnect);
          self.subscribe(client);
        }
      }, 150);
      setTimeout(() => clearInterval(waitForConnect), 10000);
    },

    subscribe(client) {
      const user = API.user;
      if (!user) return;
      const onMsg = (frame) => {
        try {
          const event = JSON.parse(frame.body);
          if (this.onEvent) this.onEvent(event);
        } catch (e) { /* ignore malformed frame */ }
      };
      if (user.role === 'VENDOR') {
        client.subscribe('/topic/vendor/' + user.userId, onMsg);
        client.subscribe('/topic/notifications/' + user.userId, onMsg);
      } else {
        client.subscribe('/topic/notifications/' + user.userId, onMsg);
      }
    },

    startPolling() {
      if (this.pollTimer || !this.onEvent) return;
      this.pollTimer = setInterval(() => {
        if (!this.connected && API.token) {
          // lightweight drift check — the app refreshes visible data
          this.pollTick && this.pollTick();
        }
      }, 15000);
    },

    stopPolling() {
      if (this.pollTimer) { clearInterval(this.pollTimer); this.pollTimer = null; }
    },

    stop() {
      this.manuallyClosed = true;
      this.stopPolling();
      if (this.client) {
        try { this.client.disconnect(() => {}); } catch (e) { /* noop */ }
        this.client = null;
      }
      this.connected = false;
    }
  };

  window.WS = WS;
})();
