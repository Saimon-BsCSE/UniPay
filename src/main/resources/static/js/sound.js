/* UniPay sounds — enhanced WebAudio chimes with proper volume and character.
   The vendor POS chime plays on every received payment; the cash tone plays
   for the recipient of a P2P transfer. All sounds are loud and distinctive. */
(function () {
  let ctx = null;
  let enabled = localStorage.getItem('unipay_sound') !== 'off';

  // Master gain node for overall volume control
  let masterGain = null;

  // Loud by default. Persisted so the user's choice survives reloads.
  let volume = (function () {
    const stored = parseFloat(localStorage.getItem('unipay_volume'));
    return isNaN(stored) ? 1.0 : Math.max(0, Math.min(1, stored));
  })();

  function ensureCtx() {
    if (!ctx) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return null;
      ctx = new AudioCtx();
      masterGain = ctx.createGain();
      masterGain.gain.value = volume;
      masterGain.connect(ctx.destination);
    }
    if (ctx.state === 'suspended') ctx.resume();
    return ctx;
  }

  // NOTE: the param is `peak` (not `volume`) so it does not shadow the
  // module-level `volume` used by the master gain.
  function playTone(freq, duration, delay, type, peak, detune = 0) {
    const c = ensureCtx();
    if (!c) return;
    const osc = c.createOscillator();
    const gain = c.createGain();
    const t0 = c.currentTime + (delay || 0);
    osc.type = type || 'sine';
    osc.frequency.value = freq;
    osc.detune.value = detune; // Add slight detune for richer sound
    gain.gain.setValueAtTime(0.0001, t0);
    // 10ms linear-ish attack, then a short hold so the note is clearly audible
    gain.gain.exponentialRampToValueAtTime(peak || 0.4, t0 + 0.01);
    gain.gain.setValueAtTime(peak || 0.4, t0 + Math.max(0.02, duration * 0.35));
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
    osc.connect(gain).connect(masterGain);
    osc.start(t0);
    osc.stop(t0 + duration + 0.05);
  }

  function playChord(frequencies, duration, delay, type, peak) {
    frequencies.forEach((freq, i) => {
      // Slight detune per note for a richer, wider chord.
      // Use a sqrt falloff so adding notes thins the chord instead of
      // making it near-silent (plain division made chords 4x quieter).
      const detune = (i - frequencies.length / 2) * 9;
      const v = peak / Math.sqrt(frequencies.length);
      playTone(freq, duration, delay + i * 0.018, type, v, detune);
    });
  }

  const Sound = {
    get enabled() { return enabled; },
    toggle() {
      enabled = !enabled;
      localStorage.setItem('unipay_sound', enabled ? 'on' : 'off');
      if (enabled) this.blip(); // Test sound
      return enabled;
    },
    /** "You received money" — satisfying ascending major chord progression. */
    cash() {
      if (!enabled) return;
      // Quick ascending arpeggio: C5 - E5 - G5 - C6
      playTone(523.25, 0.16, 0, 'sine', 0.55);   // C5
      playTone(659.25, 0.16, 0.08, 'sine', 0.55); // E5
      playTone(783.99, 0.16, 0.16, 'sine', 0.6);  // G5
      playTone(1046.50, 0.3, 0.24, 'triangle', 0.65); // C6 - bright finish
      // Sub-bass for weight
      playTone(130.81, 0.32, 0, 'sine', 0.3); // C3
    },
    /** Vendor payment received - distinct "register ring" sound. */
    vendorPayment() {
      if (!enabled) return;
      // Classic cash register "ka-ching" - bright metallic strike
      playTone(1568.0, 0.09, 0, 'square', 0.6);   // G6
      playTone(1975.5, 0.11, 0.05, 'square', 0.5); // B6
      // Resonant tail
      setTimeout(() => {
        playTone(1318.5, 0.32, 0, 'sine', 0.5);   // E6
        playTone(1760.0, 0.42, 0.05, 'triangle', 0.45); // A6
      }, 80);
    },
    /** SplitPay request received - friendly notification. */
    splitRequest() {
      if (!enabled) return;
      // Major 7th chord - noticeable but not alarming
      playChord([659.25, 824.07, 987.77, 1108.73], 0.45, 0, 'sine', 0.55);
    },
    /** SplitPay accepted/settled - success confirmation. */
    splitSuccess() {
      if (!enabled) return;
      // Triumphant major chord: C - E - G - C
      playChord([523.25, 659.25, 783.99, 1046.50], 0.4, 0, 'triangle', 0.6);
    },
    /** Error/Decline - clear negative feedback. */
    error() {
      if (!enabled) return;
      // Descending minor third - universally understood as "wrong"
      playTone(698.46, 0.16, 0, 'sawtooth', 0.45); // F5
      playTone(587.33, 0.28, 0.1, 'sawtooth', 0.45); // D5
    },
    /** Small confirmation blip - for button presses, toggles. */
    blip() {
      if (!enabled) return;
      playTone(880, 0.07, 0, 'sine', 0.45); // A5 - crisp click
    },
    /** Master volume control (0.0 to 1.0), persisted. */
    setVolume(vol) {
      volume = Math.max(0, Math.min(1, Number(vol) || 0));
      localStorage.setItem('unipay_volume', String(volume));
      if (masterGain) masterGain.gain.value = volume;
      return volume;
    },
    getVolume() {
      return volume;
    }
  };

  window.Sound = Sound;
})();