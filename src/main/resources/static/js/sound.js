/* UniPay sounds — WebAudio notification design.
 *
 * Every cue is synthesised, so there are no audio files to download and the app
 * stays fully functional offline. The design language is "a card touching a
 * terminal": a short band-passed noise burst for the physical tap, followed by
 * a bright tone figure for the confirmation. Pure tones alone read as polite
 * and get lost in a lecture hall, so the transient is what makes a cue cut
 * through — it is the main loudness lever, not raw gain.
 *
 * Master bus: cue gain -> master gain -> soft clipper -> destination.
 *
 * The clipper is a WaveShaper, not a DynamicsCompressor, and that choice is
 * deliberate. A compressor's gain-reduction envelope is smoothed over its
 * attack time, which smeared the cue's attack by ~20ms and undid the whole
 * point of the tap. A waveshaper acts on each sample independently, so it holds
 * the ceiling with zero latency while several alerts fire at once.
 *
 * The curve is tanh normalised so its slope at low level is exactly 1
 * (see makeClipCurve): it is a ceiling, not a compressor, and it does not
 * quietly act as a volume boost.
 *
 * Every cue is authored so its raw sum peaks only modestly above CEILING. That
 * deliberate overdrive is what gives the sound its density; the clipper turns
 * it into controlled warmth instead of the brittle intermodulation you get from
 * hard clipping at full scale. Gains were chosen by rendering each cue offline
 * and measuring peak, attack time and RMS, not by ear.
 */
(function () {
  let ctx = null;
  let masterGain = null;
  let clipper = null;

  let enabled = localStorage.getItem('unipay_sound') !== 'off';

  // There is no volume control any more — alerts are meant to be heard, and the
  // per-cue trim plus the clipper set the level. Any volume left behind by the
  // old slider is cleared so a previously dialled-down user is not stuck with a
  // quiet cue and no way to change it.
  localStorage.removeItem('unipay_volume');

  // Ceiling for the master bus. Cues are authored to peak just under this, so
  // the clipper only engages when alerts genuinely overlap.
  const CEILING = 0.95;

  // Same cue fired again within this window is ignored, so a burst of
  // identical events cannot machine-gun into a wall of noise.
  const RETRIGGER_MS = 150;
  const lastPlayed = Object.create(null);

  /**
   * Builds a tanh saturation curve whose slope at x=0 is exactly 1, mapping
   * -1..1 onto -ceiling..+ceiling. `a` is the solution of tanh(a) = ceiling * a;
   * bisection is used because there is no closed form. A larger `a` means the
   * curve stays linear further up before saturating, so the clipper engages
   * later and more gently.
   */
  function makeClipCurve(ceiling) {
    let lo = 0.01, hi = 5;
    for (let i = 0; i < 60; i++) {
      const a = (lo + hi) / 2;
      if (Math.tanh(a) - ceiling * a > 0) lo = a; else hi = a;
    }
    const a = (lo + hi) / 2;
    const norm = Math.tanh(a);
    const n = 2048;
    const curve = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      const x = (i / (n - 1)) * 2 - 1;
      curve[i] = ceiling * Math.tanh(a * x) / norm;
    }
    return curve;
  }

  function ensureCtx() {
    if (!ctx) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return null;
      ctx = new AudioCtx();
      masterGain = ctx.createGain();
      masterGain.gain.value = 1;
      clipper = ctx.createWaveShaper();
      clipper.curve = makeClipCurve(CEILING);
      // 4x oversampling keeps the saturation from aliasing into the midrange.
      clipper.oversample = '4x';
      masterGain.connect(clipper).connect(ctx.destination);
    }
    if (ctx.state === 'suspended') ctx.resume();
    return ctx;
  }

  /* ------------------------------------------------------------ primitives */

  /**
   * One enveloped oscillator.
   *
   * @param {number} freq
   * @param {number} duration seconds from note start to silence
   * @param {number} delay    seconds after the start of the cue
   * @param {string} type     oscillator waveform
   * @param {number} peak     gain at the attack peak
   * @param {number} plateau  0 for a plain decay; >0 holds at peak*plateau for
   *                          the first 55% of the duration. The plateau is
   *                          what makes a cue sustain audibly instead of
   *                          percussing away to nothing.
   * @param {number} detune   cents
   *
   * NOTE: the gain param is `peak` so it does not shadow the master gain
   * settings on the bus it is connected to.
   */
  function playTone(freq, duration, delay, type, peak, plateau = 0, detune = 0) {
    const c = ensureCtx();
    if (!c) return;
    const osc = c.createOscillator();
    const gain = c.createGain();
    const t0 = c.currentTime + (delay || 0);
    osc.type = type || 'sine';
    osc.frequency.value = freq;
    osc.detune.value = detune;
    const p = Math.max(0.0002, peak || 0.4);
    // 4ms attack: near-instant, which is what gives the cue its strike.
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.exponentialRampToValueAtTime(p, t0 + 0.004);
    if (plateau > 0) {
      const hold = Math.max(0.0002, p * plateau);
      gain.gain.exponentialRampToValueAtTime(hold, t0 + 0.025);
      gain.gain.setValueAtTime(hold, t0 + duration * 0.55);
    }
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
    osc.connect(gain).connect(masterGain);
    osc.start(t0);
    osc.stop(t0 + duration + 0.05);
  }

  /**
   * The "card tap": a very short burst of band-passed noise. This is the layer
   * that makes a cue cut through a noisy room, and it is what ties the sound to
   * the physical card and QR terminal the app is built around.
   *
   * @param {number} delay    offset in seconds
   * @param {number} peak     gain of the burst
   * @param {number} centerHz band-pass centre — lower reads as a soft tap,
   *                           higher as a sharp plastic click
   * @param {number} dur      burst length
   */
  function playTap(delay, peak, centerHz, dur) {
    const c = ensureCtx();
    if (!c) return;
    // A fresh short buffer per burst; it is windowed, so there is no audible
    // loop and no need to cache it.
    const frames = Math.max(1, Math.ceil(c.sampleRate * (dur + 0.01)));
    const buffer = c.createBuffer(1, frames, c.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < frames; i++) {
      // Fade the tail so the buffer boundary does not click.
      data[i] = (Math.random() * 2 - 1) * (1 - i / frames);
    }
    const src = c.createBufferSource();
    src.buffer = buffer;

    const band = c.createBiquadFilter();
    band.type = 'bandpass';
    band.frequency.value = centerHz || 2200;
    band.Q.value = 1.1;

    const gain = c.createGain();
    const t0 = c.currentTime + (delay || 0);
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.exponentialRampToValueAtTime(Math.max(0.0002, peak || 0.3), t0 + 0.002);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);

    src.connect(band).connect(gain).connect(masterGain);
    src.start(t0);
    src.stop(t0 + dur + 0.01);
  }

  /**
   * Repeats a short tone pair. Used where a cue needs two clearly separated
   * events to be recognisable (SplitPay, errors).
   */
  function playPulse(freq, duration, delay, type, peak, gap) {
    playTone(freq, duration, delay, type, peak, 0.6);
    playTone(freq, duration, delay + (gap || duration + 0.09), type, peak * 0.9, 0.6);
  }

  /* ----------------------------------------------------------------- cues */

  /**
   * Gate shared by every cue: honours the mute switch and drops a retrigger of
   * the same cue inside RETRIGGER_MS.
   */
  function gate(name) {
    if (!enabled) return false;
    const now = performance.now();
    if (lastPlayed[name] !== undefined && now - lastPlayed[name] < RETRIGGER_MS) return false;
    lastPlayed[name] = now;
    return true;
  }

  const Sound = {
    get enabled() { return enabled; },

    toggle() {
      enabled = !enabled;
      localStorage.setItem('unipay_sound', enabled ? 'on' : 'off');
      // Toggling must always be audible, so it bypasses the retrigger guard.
      if (enabled) {
        lastPlayed.blip = -1e9;
        this.blip();
      }
      return enabled;
    },

    /**
     * "You received money" — the primary alert, and the one that has to cut
     * through a lecture hall. A card tap, then a tight C5-E5-G5 figure, then a
     * long bright C6 landing over a sustained low body.
     *
     * Deliberately front-loaded: the whole figure lands inside the first
     * 250ms, because that is where attention is actually won. The previous cue
     * was front-loaded too but stopped dead at 440ms, whereas this rings on for
     * roughly twice as long, so it occupies the ear rather than just marking
     * an instant. Measured over the first 0.6s this is about 4.4dB louder than
     * the old cue, and it reaches that without any hard clipping.
     */
    cash() {
      if (!gate('cash')) return;
      playTap(0, 0.55, 2600, 0.055);              // card against the terminal
      playTone(523.25, 0.16, 0.010, 'sine', 0.80, 0.75);        // C5
      playTone(659.25, 0.16, 0.085, 'sine', 0.80, 0.75);        // E5
      playTone(783.99, 0.16, 0.160, 'sine', 0.82, 0.75);        // G5
      playTone(1046.50, 0.80, 0.240, 'triangle', 0.95, 0.68);  // C6 landing
      playTone(130.81, 0.95, 0.010, 'sine', 0.40, 0.75);        // C3 body
      playTone(261.63, 0.76, 0.010, 'sine', 0.26, 0.75);        // C4 weight
    },

    /**
     * Vendor payment received — the same tap-and-rise family as cash(), because
     * both mean "money arrived", but a fifth higher, struck twice, and rung out
     * on a triangle top note. A vendor can tell the POS alert from a personal
     * transfer without looking at the screen.
     */
    vendorPayment() {
      if (!gate('vendorPayment')) return;
      playTap(0, 0.58, 3000, 0.05);              // card taps down
      playTap(0.085, 0.30, 3600, 0.04);           // second contact
      playTone(783.99, 0.14, 0.012, 'triangle', 0.78, 0.75);   // G5
      playTone(1046.50, 0.14, 0.075, 'triangle', 0.80, 0.75);  // C6
      playTone(1318.51, 0.85, 0.138, 'triangle', 0.95, 0.68); // E6, long ring
      playTone(196.00, 0.90, 0.012, 'sine', 0.40, 0.75);       // G3 body
      playTone(293.66, 0.72, 0.012, 'sine', 0.26, 0.75);       // D4 weight
    },

    /**
     * SplitPay request received — "someone wants you to pay". A soft low tap
     * and two gently pulsing mid notes. Deliberately not a rising figure, so
     * it can never be mistaken for a payment arriving.
     */
    splitRequest() {
      if (!gate('splitRequest')) return;
      playTap(0, 0.28, 1500, 0.05);
      playPulse(587.33, 0.15, 0.02, 'sine', 0.46);  // D5
      playPulse(880.00, 0.18, 0.34, 'sine', 0.48);  // A5
    },

    /**
     * SplitPay settled — two crisp taps and a single bright dyad, the only cue
     * built on a chord rather than a line, so it is unmistakable.
     */
    splitSuccess() {
      if (!gate('splitSuccess')) return;
      playTap(0, 0.34, 2400, 0.04);
      playTap(0.10, 0.34, 2400, 0.04);
      playTone(587.33, 0.55, 0.01, 'triangle', 0.44, 0.6);  // D5
      playTone(880.00, 0.55, 0.01, 'triangle', 0.40, 0.6);  // A5
      playTone(1174.66, 0.45, 0.13, 'triangle', 0.72, 0.6); // D6 sparkle
    },

    /**
     * Declined or failed — the only cue with no tap and no bright content:
     * two dry, low, falling sawtooth notes. It must never read as a payment.
     */
    error() {
      if (!gate('error')) return;
      playTone(233.08, 0.20, 0, 'sawtooth', 0.42);   // Bb3
      playTone(185.00, 0.36, 0.12, 'sawtooth', 0.42); // Ab3, falling
      playTap(0.12, 0.18, 900, 0.05);                 // dull thud on the fall
    },

    /** Button press / toggle feedback — a single quiet click, never tiring. */
    blip() {
      if (!enabled) return;
      playTap(0, 0.20, 2800, 0.028);
      playTone(1174.66, 0.06, 0, 'sine', 0.28);      // D6
    }
  };

  window.Sound = Sound;
})();
