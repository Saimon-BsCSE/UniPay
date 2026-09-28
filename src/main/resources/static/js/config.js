/* UniPay runtime configuration.
 *
 * The Pages deployment workflow overwrites this file with demo: true so the
 * published site runs on the offline in-memory backend (js/demo-api.js). The jar
 * ships the version below, which talks to the real Spring Boot API.
 *
 * To point a static build at a real backend instead, set demo: false and put the
 * backend origin here. The server allows any origin (SecurityConfig CORS bean),
 * so a cross-origin API works without further configuration.
 */
window.UNIPAY_CONFIG = {
  demo: false,
  apiBase: ''
};
