'use strict';

// 无第三方依赖：模拟浏览器媒体、可见区域和生命周期，检查注入脚本的外部行为。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/media-control.js'), 'utf8');
let checks = 0;

class Events {
    constructor() { this.listeners = new Map(); }
    addEventListener(name, callback) {
        if (!this.listeners.has(name)) this.listeners.set(name, new Set());
        this.listeners.get(name).add(callback);
    }
    removeEventListener(name, callback) { this.listeners.get(name)?.delete(callback); }
    fire(name, target = this) {
        for (const callback of this.listeners.get(name) || []) callback({ target });
    }
}

function harness() {
    const document = new Events();
    const window = new Events();
    const timers = new Map();
    const observers = [];
    let timerId = 0;
    let list = [];
    document.hidden = false;
    document.documentElement = { clientWidth: 400, clientHeight: 800 };
    document.querySelectorAll = selector => {
        assert.equal(selector, 'video');
        return list;
    };
    window.innerWidth = 400;
    window.innerHeight = 800;
    window.getComputedStyle = element => ({
        display: 'block', visibility: 'visible', opacity: '1',
        contentVisibility: 'visible', overflowX: 'visible', overflowY: 'visible',
        ...element.style
    });
    window.setInterval = (callback, ms) => {
        assert(ms >= 250 && ms <= 500, 'boost monitor is deliberately low frequency');
        timers.set(++timerId, { callback, interval: true });
        return timerId;
    };
    window.setTimeout = callback => {
        timers.set(++timerId, { callback, interval: false });
        return timerId;
    };
    window.clearInterval = window.clearTimeout = id => timers.delete(id);
    class Observer {
        constructor(callback) { this.callback = callback; this.active = false; observers.push(this); }
        observe() { this.active = true; }
        disconnect() { this.active = false; }
    }
    const context = vm.createContext({ window, document, MutationObserver: Observer });
    function install() { vm.runInContext(script, context); }
    install();
    return {
        document, window, timers,
        api: () => window.LightSwipe,
        install,
        setVideos: (...items) => { list = items; },
        mutate: () => observers.filter(item => item.active).forEach(item => item.callback()),
        tick: () => {
            for (const [id, timer] of Array.from(timers)) {
                if (!timers.has(id)) continue;
                if (!timer.interval) timers.delete(id);
                timer.callback();
            }
        },
        activeObservers: () => observers.filter(item => item.active).length
    };
}

function node(rect = { left: 0, top: 0, right: 400, bottom: 800 }, style = {}) {
    return { nodeType: 1, hidden: false, parentElement: null, style, getBoundingClientRect: () => rect };
}

function video(options = {}) {
    const element = node(options.rect, options.style);
    return Object.assign(element, {
        tagName: 'VIDEO', isConnected: true, playbackRate: 1,
        preservesPitch: false, webkitPreservesPitch: false,
        currentTime: 12, duration: 60, readyState: 4, paused: false, ended: false,
        pauseCalls: 0, playCalls: 0,
        pause() { this.pauseCalls++; this.paused = true; },
        play() { this.playCalls++; this.paused = false; return Promise.resolve(); }
    }, options);
}

function check(name, test) {
    test();
    checks++;
    console.log(`PASS ${name}`);
}

check('empty page is safe and status contains no website or account metadata', () => {
    const h = harness();
    const state = h.api().setBoost(true);
    assert.equal(state.available, false);
    assert.equal(state.boosted, false);
    assert.equal(state.videoCount, 0);
    assert.equal(state.rate, null);
    assert.deepEqual(Object.keys(state).sort(), [
        'available', 'boostRequested', 'boosted', 'currentTime', 'destroyed',
        'duration', 'paused', 'playing', 'rate', 'videoCount'
    ].sort());
    assert.equal(h.api().setBoost(false).boostRequested, false);
    assert.equal(h.timers.size, 0);
    assert.equal(h.activeObservers(), 0);
});

check('largest visible intersection wins over a bigger offscreen video', () => {
    const h = harness();
    const partial = video({ rect: { left: 0, top: -700, right: 400, bottom: 100 } });
    const visible = video({ playbackRate: 1.25, rect: { left: 0, top: 100, right: 400, bottom: 700 } });
    h.setVideos(partial, visible);
    const state = h.api().setBoost(true);
    assert.equal(visible.playbackRate, 2);
    assert.equal(partial.playbackRate, 1);
    assert.equal(state.boosted, true);
    assert.equal(state.currentTime, 12);
    assert.equal(state.duration, 60);
    assert.equal(h.activeObservers(), 1);
});

check('display, visibility, zero opacity, hidden ancestors and clipping are respected', () => {
    for (const style of [{ display: 'none' }, { visibility: 'hidden' }, { opacity: '0' }, { contentVisibility: 'hidden' }]) {
        const h = harness();
        const hidden = video();
        hidden.parentElement = node(undefined, style);
        h.setVideos(hidden);
        assert.equal(h.api().setBoost(true).available, false);
        assert.equal(hidden.playbackRate, 1);
    }
    const h = harness();
    const clipped = video();
    clipped.parentElement = node({ left: 0, top: 801, right: 400, bottom: 900 }, { overflowY: 'hidden' });
    const detached = video({ isConnected: false });
    const hiddenAttribute = video();
    hiddenAttribute.parentElement = node();
    hiddenAttribute.parentElement.hidden = true;
    h.setVideos(clipped, detached, hiddenAttribute);
    assert.equal(h.api().setBoost(true).available, false);
});

check('same visible area prefers the playing video without starting paused media', () => {
    const h = harness();
    const paused = video({ paused: true });
    const playing = video();
    h.setVideos(paused, playing);
    h.api().setBoost(true);
    assert.equal(playing.playbackRate, 2);
    assert.equal(paused.playbackRate, 1);
    assert.equal(paused.playCalls, 0);
});

check('cancel restores the exact previous rate and pitch without idle polling', () => {
    const h = harness();
    const item = video({ playbackRate: 1.5, preservesPitch: false, webkitPreservesPitch: false });
    h.setVideos(item);
    h.api().setBoost(true);
    assert.equal(item.preservesPitch, true);
    h.api().setBoost(true); // 重复手势调用不能把 2 倍速误存成原倍率。
    h.api().setBoost(false);
    assert.equal(item.playbackRate, 1.5);
    assert.equal(item.preservesPitch, false);
    assert.equal(item.webkitPreservesPitch, false);
    assert.equal(h.timers.size, 0);
    assert.equal(h.activeObservers(), 0);
    h.api().status();
    assert.equal(h.timers.size, 0);
});

check('locked boost follows replacement video and restores the old video', () => {
    const h = harness();
    const first = video({ playbackRate: 0.75 });
    const next = video({ playbackRate: 1.25 });
    h.setVideos(first);
    h.api().setBoost(true);
    first.isConnected = false;
    h.setVideos(next);
    h.mutate();
    h.tick();
    assert.equal(first.playbackRate, 0.75);
    assert.equal(next.playbackRate, 2);
    assert.equal(h.api().status().boosted, true);
    h.api().setBoost(false);
    assert.equal(next.playbackRate, 1.25);
});

check('low-frequency fallback tracks video positions without a DOM mutation', () => {
    const h = harness();
    const first = video({ playbackRate: 1.25 });
    const second = video({ playbackRate: 1.75, rect: { left: 0, top: 800, right: 400, bottom: 1600 } });
    h.setVideos(first, second);
    h.api().setBoost(true);
    first.getBoundingClientRect = () => ({ left: 0, top: -800, right: 400, bottom: 0 });
    second.getBoundingClientRect = () => ({ left: 0, top: 0, right: 400, bottom: 800 });
    h.tick();
    assert.equal(first.playbackRate, 1.25);
    assert.equal(second.playbackRate, 2);
});

check('background stops all playback and resumes only the original visible playing video', () => {
    const h = harness();
    const visible = video({ playbackRate: 1.5 });
    const offscreen = video({ rect: { left: 0, top: 800, right: 400, bottom: 1600 } });
    h.setVideos(visible, offscreen);
    h.api().setBoost(true);
    h.api().pause();
    h.api().pause();
    assert.equal(visible.playbackRate, 1.5);
    assert.equal(visible.paused, true);
    assert.equal(offscreen.paused, true);
    assert.equal(h.timers.size, 0);
    assert.equal(h.activeObservers(), 0);
    assert.equal(h.api().setBoost(true).boostRequested, false);
    h.api().resume();
    h.api().resume();
    assert.equal(visible.playCalls, 1);
    assert.equal(offscreen.playCalls, 0);
    assert.equal(h.api().status().boosted, false);
});

check('paused or replaced media is not autostarted on returning to the app', () => {
    const h = harness();
    const paused = video({ paused: true });
    h.setVideos(paused);
    h.api().pause();
    h.api().resume();
    assert.equal(paused.playCalls, 0);
    const old = video();
    const next = video({ paused: true });
    h.setVideos(old);
    h.api().pause();
    h.setVideos(next);
    h.api().resume();
    assert.equal(old.playCalls, 0);
    assert.equal(next.playCalls, 0);
});

check('visibility and native lifecycle ordering preserves one resume candidate', () => {
    const h = harness();
    const item = video({ playbackRate: 1.25 });
    h.setVideos(item);
    h.api().setBoost(true);
    h.document.hidden = true;
    h.document.fire('visibilitychange');
    h.api().pause();
    h.api().resume(); // 原生 onResume 可能先于网页 visibilitychange。
    assert.equal(item.playCalls, 0);
    assert.equal(item.playbackRate, 1.25);
    h.document.hidden = false;
    h.document.fire('visibilitychange');
    assert.equal(item.playCalls, 1);
    assert.equal(h.timers.size, 0);
});

check('website autoplay is stopped while backgrounded without polling', () => {
    const h = harness();
    const item = video();
    h.setVideos(item);
    h.api().pause();
    item.paused = false;
    h.document.fire('play', item);
    assert.equal(item.paused, true);
    assert.equal(h.timers.size, 0);
    h.api().resume();
    assert.equal(item.playCalls, 1);
});

check('script injected into an already hidden page immediately pauses its media', () => {
    const h = harness();
    const item = video({ playbackRate: 1.25 });
    h.setVideos(item);
    h.document.hidden = true;
    h.install();
    assert.equal(item.paused, true);
    assert.equal(h.timers.size, 0);
    h.document.hidden = false;
    h.document.fire('visibilitychange');
    assert.equal(item.playCalls, 1);
    assert.equal(item.playbackRate, 1.25);
});

check('pagehide, destroy and reinjection restore rates and release observers', () => {
    const h = harness();
    const item = video({ playbackRate: 1.75 });
    h.setVideos(item);
    h.api().setBoost(true);
    h.window.fire('pagehide');
    assert.equal(item.playbackRate, 1.75);
    assert.equal(h.timers.size, 0);
    h.api().resume();
    h.api().setBoost(true);
    h.install();
    assert.equal(item.playbackRate, 1.75);
    assert.equal(h.timers.size, 0);
    assert.equal(h.activeObservers(), 0);
    const state = h.api().destroy();
    assert.equal(state.destroyed, true);
    assert.equal(h.api().setBoost(true).available, false);
    assert.equal(h.timers.size, 0);
});

check('non-finite media times serialize safely and denied play promises are handled', () => {
    const h = harness();
    const item = video({ duration: Infinity, currentTime: NaN });
    item.play = function () { this.playCalls++; return Promise.reject(new Error('autoplay blocked')); };
    h.setVideos(item);
    assert.equal(h.api().status().duration, 0);
    assert.equal(h.api().status().currentTime, 0);
    assert.doesNotThrow(() => JSON.stringify(h.api().status()));
    h.api().pause();
    h.api().resume();
    assert.equal(item.playCalls, 1);
});

console.log(`MediaControlCheck: ${checks} scenario checks passed`);
