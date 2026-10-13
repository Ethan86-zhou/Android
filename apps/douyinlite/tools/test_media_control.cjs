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
    const result = Object.assign(element, {
        tagName: 'VIDEO', isConnected: true, playbackRate: 1,
        preservesPitch: false, webkitPreservesPitch: false,
        currentTime: 12, duration: 60, readyState: 4, paused: false, ended: false,
        currentSrc: 'blob:mock-video', src: 'blob:mock-video', seekable: timeRanges([0, 60]),
        buffered: timeRanges(), error: null,
        pauseCalls: 0, playCalls: 0,
        pause() { this.pauseCalls++; this.paused = true; },
        play() { this.playCalls++; this.paused = false; return Promise.resolve(); }
    }, options);
    let currentTime = result.currentTime;
    result.timeWrites = 0;
    Object.defineProperty(result, 'currentTime', {
        get: () => currentTime,
        set(value) { this.timeWrites++; currentTime = value; },
        configurable: true
    });
    return result;
}

function timeRanges(...ranges) {
    return {
        length: ranges.length,
        start: index => ranges[index][0],
        end: index => ranges[index][1]
    };
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
    assert.equal(state.mediaId, 0);
    assert.equal(state.seekable, false);
    assert.deepEqual(Object.keys(state).sort(), [
        'available', 'boostRequested', 'boosted', 'currentTime', 'destroyed',
        'duration', 'mediaId', 'paused', 'playing', 'rate', 'seekable', 'videoCount',
        'bufferedAhead', 'bufferedFraction', 'bufferedUntil', 'buffering',
        'playbackRequested', 'mediaPaused', 'mediaError', 'readyState'
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

check('media identity stays stable across time and playback changes without exposing sources', () => {
    const h = harness();
    const item = video({ currentSrc: 'blob:private-current', src: 'blob:private-declared' });
    h.setVideos(item);
    const first = h.api().status();
    assert(Number.isInteger(first.mediaId) && first.mediaId > 0);
    assert.equal(first.seekable, true);
    item.currentTime = 24;
    item.paused = true;
    item.playbackRate = 1.25;
    assert.equal(h.api().status().mediaId, first.mediaId);
    assert(!JSON.stringify(first).includes('private'));
    assert.equal(h.timers.size, 0);
});

check('seek commits once and preserves playing state, exact rate and pitch', () => {
    const h = harness();
    const item = video({ playbackRate: 1.5 });
    h.setVideos(item);
    const state = h.api().seek(0.5, h.api().status().mediaId);
    assert.equal(state.accepted, true);
    assert.equal(item.currentTime, 30);
    assert.equal(item.timeWrites, 1);
    assert.equal(item.paused, false);
    assert.equal(item.playCalls, 0);
    assert.equal(item.pauseCalls, 0);
    assert.equal(item.playbackRate, 1.5);
    assert.equal(item.preservesPitch, false);
    assert.equal(h.timers.size, 0);
});

check('seek leaves a paused video paused and keeps locked boost intact', () => {
    const h = harness();
    const item = video({ paused: true, playbackRate: 1.25 });
    h.setVideos(item);
    h.api().setBoost(true);
    assert.equal(h.api().seek(0.25, h.api().status().mediaId).accepted, true);
    assert.equal(item.currentTime, 15);
    assert.equal(item.paused, true);
    assert.equal(item.playbackRate, 2);
    assert.equal(h.api().status().boostRequested, true);
    assert.equal(item.playCalls + item.pauseCalls, 0);
    h.api().setBoost(false);
    assert.equal(item.playbackRate, 1.25);
});

check('seek clamps fractions and avoids the exact duration at the final boundary', () => {
    const h = harness();
    const item = video();
    h.setVideos(item);
    const id = h.api().status().mediaId;
    assert.equal(h.api().seek(-4, id).accepted, true);
    assert.equal(item.currentTime, 0);
    assert.equal(h.api().seek(0, id).accepted, true);
    assert.equal(item.currentTime, 0);
    assert.equal(h.api().seek(1, id).accepted, true);
    assert.equal(item.currentTime, 59.95);
    assert.equal(h.api().seek(4, id).accepted, true);
    assert.equal(item.currentTime, 59.95);
    assert.equal(item.timeWrites, 4);
});

check('seek respects a nonzero start and chooses the closest range boundary across gaps', () => {
    const h = harness();
    const item = video({ duration: 100, currentTime: 50, seekable: timeRanges([10, 20], [40, 60], [80, 95]) });
    h.setVideos(item);
    const id = h.api().status().mediaId;
    assert.equal(h.api().seek(0, id).accepted, true);
    assert.equal(item.currentTime, 10);
    h.api().seek(0.28, id);
    assert.equal(item.currentTime, 20);
    h.api().seek(0.32, id);
    assert.equal(item.currentTime, 40);
    h.api().seek(1, id);
    assert.equal(item.currentTime, 95);
});

check('equidistant gaps prefer the boundary nearest the previous play position', () => {
    const h = harness();
    const item = video({ duration: 100, currentTime: 70, seekable: timeRanges([0, 20], [40, 60]) });
    h.setVideos(item);
    h.api().seek(0.3, h.api().status().mediaId);
    assert.equal(item.currentTime, 40);
    assert.equal(item.timeWrites, 1);
});

check('very short media keeps a valid final position inside its seekable interval', () => {
    const h = harness();
    const item = video({ duration: 0.02, seekable: timeRanges([0.01, 0.02]) });
    h.setVideos(item);
    assert.equal(h.api().seek(1, h.api().status().mediaId).accepted, true);
    assert.equal(item.currentTime, 0.015);
});

check('NaN, infinite and nonnumeric seek fractions never change playback', () => {
    const h = harness();
    const item = video();
    h.setVideos(item);
    const id = h.api().status().mediaId;
    for (const fraction of [NaN, Infinity, -Infinity, undefined, null, '0.5', true]) {
        assert.equal(h.api().seek(fraction, id).accepted, false);
    }
    assert.equal(item.currentTime, 12);
    assert.equal(item.timeWrites, 0);
    assert.equal(item.playCalls + item.pauseCalls, 0);
});

check('unknown live duration, empty and invalid seekable ranges disable seeking', () => {
    for (const options of [
        { duration: Infinity }, { duration: NaN }, { duration: 0 }, { duration: -1 },
        { seekable: timeRanges() }, { seekable: null },
        { seekable: timeRanges([10, 10], [40, 20], [NaN, 50], [0, Infinity], [70, 80]) },
        { seekable: { length: 1, start() { throw new Error('unavailable'); } } }
    ]) {
        const h = harness();
        const item = video(options);
        h.setVideos(item);
        const state = h.api().status();
        assert.equal(state.seekable, false);
        assert.equal(h.api().seek(0.5, state.mediaId).accepted, false);
        assert.equal(item.currentTime, 12);
        assert.equal(item.timeWrites, 0);
    }
});

check('seekable intervals are clipped to actual finite media duration', () => {
    const h = harness();
    const item = video({ seekable: timeRanges([-10, 100]) });
    h.setVideos(item);
    const id = h.api().status().mediaId;
    assert.equal(h.api().seek(-1, id).accepted, true);
    assert.equal(item.currentTime, 0);
    assert.equal(h.api().seek(1, id).accepted, true);
    assert.equal(item.currentTime, 59.95);
});

check('same-node declared source changes invalidate an old drag before currentSrc updates', () => {
    const h = harness();
    const item = video();
    h.setVideos(item);
    const oldId = h.api().status().mediaId;
    item.src = 'blob:replacement-declared';
    const denied = h.api().seek(0.8, oldId);
    assert.equal(denied.accepted, false);
    assert(denied.mediaId > oldId);
    assert.equal(item.timeWrites, 0);
    assert.equal(h.api().status().mediaId, denied.mediaId);
    assert.equal(h.api().seek(0.8, denied.mediaId).accepted, true);
});

check('same-node current source changes invalidate an old drag without a status poll', () => {
    const h = harness();
    const item = video();
    h.setVideos(item);
    const oldId = h.api().status().mediaId;
    item.currentSrc = 'blob:replacement-loaded';
    const denied = h.api().seek(0.8, oldId);
    assert.equal(denied.accepted, false);
    assert(denied.mediaId > oldId);
    assert.equal(item.timeWrites, 0);
    assert.equal(h.api().status().mediaId, denied.mediaId);
});

check('swiping to an existing offscreen video invalidates the previous drag', () => {
    const h = harness();
    const first = video();
    const next = video({ rect: { left: 0, top: 800, right: 400, bottom: 1600 } });
    h.setVideos(first, next);
    const oldId = h.api().status().mediaId;
    first.getBoundingClientRect = () => ({ left: 0, top: -800, right: 400, bottom: 0 });
    next.getBoundingClientRect = () => ({ left: 0, top: 0, right: 400, bottom: 800 });
    const denied = h.api().seek(0.8, oldId);
    assert.equal(denied.accepted, false);
    assert(denied.mediaId > oldId);
    assert.equal(first.timeWrites + next.timeWrites, 0);
    assert.equal(h.api().seek(0.8, denied.mediaId).accepted, true);
    assert.equal(next.currentTime, 48);
    assert.equal(first.currentTime, 12);
});

check('no visible target exposes mediaId zero and rejects seek and toggle', () => {
    const h = harness();
    const item = video();
    h.setVideos(item);
    const oldId = h.api().status().mediaId;
    item.isConnected = false;
    assert.equal(h.api().status().mediaId, 0);
    assert.equal(h.api().seek(0.5, oldId).accepted, false);
    assert.equal(h.api().togglePlayback().accepted, false);
    item.isConnected = true;
    assert(h.api().status().mediaId > oldId);
    assert.equal(item.timeWrites + item.playCalls + item.pauseCalls, 0);
});

check('background and destroyed APIs reject both controls without extra state changes', () => {
    const h = harness();
    const item = video();
    h.setVideos(item);
    const id = h.api().status().mediaId;
    h.api().pause();
    const pauseCalls = item.pauseCalls;
    assert.equal(h.api().seek(0.5, id).accepted, false);
    assert.equal(h.api().togglePlayback().accepted, false);
    assert.equal(item.pauseCalls, pauseCalls);
    assert.equal(item.timeWrites + item.playCalls, 0);
    h.api().resume();
    h.document.hidden = true; // 即便 visibilitychange 还未送达，也不能在后台操控。
    assert.equal(h.api().seek(0.5, id).accepted, false);
    assert.equal(h.api().togglePlayback().accepted, false);
    h.document.hidden = false;
    h.api().destroy();
    const before = item.playCalls + item.pauseCalls;
    assert.equal(h.api().seek(0.5, id).accepted, false);
    assert.equal(h.api().togglePlayback().accepted, false);
    assert.equal(item.playCalls + item.pauseCalls, before);
    assert.equal(item.timeWrites, 0);
    assert.equal(h.api().status().mediaId, 0);
});

check('toggle changes only visible media and preserves rate without idle monitoring', () => {
    const h = harness();
    const item = video({ playbackRate: 1.5 });
    const offscreen = video({ rect: { left: 0, top: 800, right: 400, bottom: 1600 } });
    h.setVideos(item, offscreen);
    assert.equal(h.api().togglePlayback().accepted, true);
    assert.equal(item.paused, true);
    assert.equal(item.pauseCalls, 1);
    assert.equal(h.api().togglePlayback().accepted, true);
    assert.equal(item.paused, false);
    assert.equal(item.playCalls, 1);
    assert.equal(item.playbackRate, 1.5);
    assert.equal(offscreen.playCalls + offscreen.pauseCalls, 0);
    assert.equal(h.timers.size, 0);
});

check('toggle handles a rejected play promise and a throwing player', () => {
    const h = harness();
    const item = video({ paused: true });
    item.play = function () { this.playCalls++; return Promise.reject(new Error('blocked')); };
    h.setVideos(item);
    const result = h.api().togglePlayback();
    assert.equal(result.accepted, true);
    assert.equal(result.playing, false);
    assert.equal(item.playCalls, 1);
    item.play = function () { throw new Error('player removed'); };
    assert.equal(h.api().togglePlayback().accepted, false);
    assert.equal(item.paused, true);
});

check('a throwing currentTime setter rejects seek without touching playback or boost', () => {
    const h = harness();
    const item = video({ playbackRate: 1.5 });
    h.setVideos(item);
    Object.defineProperty(item, 'currentTime', {
        get: () => 12,
        set() { throw new Error('unavailable'); }
    });
    assert.equal(h.api().seek(0.5, h.api().status().mediaId).accepted, false);
    assert.equal(item.currentTime, 12);
    assert.equal(item.playCalls + item.pauseCalls, 0);
    assert.equal(item.playbackRate, 1.5);
});

check('loading and waiting remain pausable while enough future data ends buffering', () => {
    const h = harness();
    const item = video();
    h.setVideos(item);
    for (let readiness = 0; readiness <= 4; readiness++) {
        item.readyState = readiness;
        const state = h.api().status();
        assert.equal(state.readyState, readiness);
        assert.equal(state.playbackRequested, true);
        assert.equal(state.buffering, readiness < 3);
        assert.equal(state.mediaPaused, false);
        assert.equal(state.mediaError, false);
    }
    item.readyState = 1;
    h.api().togglePlayback();
    assert.equal(item.paused, true);
    assert.equal(h.api().status().buffering, false);
    assert.equal(h.api().status().playbackRequested, false);
    assert.equal(h.api().status().mediaPaused, true);
});

check('user pause, finished media and media errors never masquerade as buffering', () => {
    for (const options of [
        { paused: true }, { ended: true },
        { error: { code: 2, message: 'private source or player error detail' } }
    ]) {
        const h = harness();
        const item = video({ readyState: 0, ...options });
        h.setVideos(item);
        const state = h.api().status();
        assert.equal(state.buffering, false);
        assert.equal(state.playbackRequested, false);
        assert.equal(state.mediaError, !!options.error);
        assert(!JSON.stringify(state).includes('private'));
    }
});

check('buffer progress follows the current continuous range and does not bridge gaps', () => {
    const h = harness();
    const item = video({ duration: 100, buffered: timeRanges([0, 20], [40, 70], [90, 100]) });
    h.setVideos(item);
    let state = h.api().status();
    assert.equal(state.bufferedUntil, 20);
    assert.equal(state.bufferedAhead, 8);
    assert.equal(state.bufferedFraction, 0.2);
    item.currentTime = 25;
    state = h.api().status();
    assert.equal(state.bufferedUntil, 25);
    assert.equal(state.bufferedAhead, 0);
    assert.equal(state.bufferedFraction, 0.25);
    item.currentTime = 40;
    state = h.api().status();
    assert.equal(state.bufferedUntil, 70);
    assert.equal(state.bufferedAhead, 30);
    assert.equal(state.bufferedFraction, 0.7);
    item.currentTime = 70;
    assert.equal(h.api().status().bufferedAhead, 0);
});

check('empty, invalid or unreadable buffered ranges do not invent playable data', () => {
    for (const buffered of [
        null, timeRanges(), timeRanges([20, 30]),
        timeRanges([20, 10], [12, 12], [NaN, 40], [0, Infinity]),
        { length: 1, start() { throw new Error('player removed'); } }
    ]) {
        const h = harness();
        h.setVideos(video({ buffered }));
        const state = h.api().status();
        assert.equal(state.bufferedUntil, 12);
        assert.equal(state.bufferedAhead, 0);
        assert.equal(state.bufferedFraction, 0.2);
    }
    const h = harness();
    const item = video();
    Object.defineProperty(item, 'buffered', { get() { throw new Error('resource detached'); } });
    h.setVideos(item);
    assert.equal(h.api().status().bufferedAhead, 0);
});

check('finite buffer progress is bounded while live streams still report ahead seconds', () => {
    const h = harness();
    const item = video({ buffered: timeRanges([-5, 500]) });
    h.setVideos(item);
    let state = h.api().status();
    assert.equal(state.bufferedUntil, 60);
    assert.equal(state.bufferedAhead, 48);
    assert.equal(state.bufferedFraction, 1);
    item.duration = Infinity;
    state = h.api().status();
    assert.equal(state.bufferedUntil, 500);
    assert.equal(state.bufferedAhead, 488);
    assert.equal(state.bufferedFraction, 0);
    item.currentTime = NaN;
    item.buffered = timeRanges([NaN, Infinity]);
    state = h.api().status();
    assert.equal(state.bufferedUntil, 0);
    assert.equal(state.bufferedAhead, 0);
    assert(!JSON.stringify(state).includes('null'));
});

check('buffer status is passive and does not create downloads or idle monitoring', () => {
    const h = harness();
    const item = video({ readyState: 1, preload: 'metadata' });
    item.load = () => assert.fail('observing buffering must not restart the website player');
    h.setVideos(item);
    for (let index = 0; index < 40; index++) h.api().status();
    assert.equal(item.playCalls + item.pauseCalls + item.timeWrites, 0);
    assert.equal(item.preload, 'metadata');
    assert.equal(h.timers.size, 0);
    assert.equal(h.activeObservers(), 0);
});

check('switching the visible player resets buffer and waiting state with media identity', () => {
    const h = harness();
    const first = video({ readyState: 1, buffered: timeRanges([0, 50]) });
    const second = video({ paused: true, buffered: timeRanges([0, 15]) });
    h.setVideos(first);
    const before = h.api().status();
    h.setVideos(second);
    const after = h.api().status();
    assert(after.mediaId > before.mediaId);
    assert.equal(after.buffering, false);
    assert.equal(after.bufferedUntil, 15);
    assert.equal(after.bufferedAhead, 3);
    h.setVideos();
    const empty = h.api().status();
    assert.equal(empty.buffering, false);
    assert.equal(empty.playbackRequested, false);
    assert.equal(empty.bufferedUntil + empty.bufferedAhead + empty.bufferedFraction, 0);
});

check('background during buffering resumes the same requested video only once', () => {
    for (let readyState = 0; readyState < 3; readyState++) {
        const h = harness();
        const item = video({ readyState, playbackRate: 1.5 });
        h.setVideos(item);
        assert.equal(h.api().status().buffering, true);
        h.api().pause();
        const paused = h.api().status();
        assert.equal(paused.buffering, false);
        assert.equal(paused.playbackRequested, false);
        assert.equal(item.paused, true);
        h.api().resume();
        h.api().resume();
        assert.equal(item.playCalls, 1);
        assert.equal(item.playbackRate, 1.5);
        assert.equal(h.api().status().buffering, true);
    }
});

check('source replacement or error while backgrounded prevents accidental autoplay', () => {
    for (const mutate of [
        item => { item.src = 'blob:new-source'; },
        item => { item.currentSrc = 'blob:new-loaded-source'; },
        item => { item.error = { code: 3 }; }
    ]) {
        const h = harness();
        const item = video({ readyState: 1 });
        h.setVideos(item);
        h.api().pause();
        mutate(item);
        h.api().resume();
        assert.equal(item.playCalls, 0);
        assert.equal(item.paused, true);
    }
});

check('hidden or destroyed pages suppress waiting and manual pause is preserved', () => {
    const h = harness();
    const item = video({ readyState: 1 });
    h.setVideos(item);
    h.document.hidden = true; // visibilitychange 送达前也不能显示前台加载提示。
    assert.equal(h.api().status().buffering, false);
    assert.equal(h.api().status().playbackRequested, false);
    h.document.hidden = false;
    h.api().togglePlayback();
    h.api().pause();
    h.api().resume();
    assert.equal(item.playCalls, 0);
    const state = h.api().destroy();
    assert.equal(state.buffering, false);
    assert.equal(state.playbackRequested, false);
    assert.equal(state.mediaError, false);
    assert.equal(state.bufferedAhead + state.bufferedUntil + state.bufferedFraction, 0);
});

console.log(`MediaControlCheck: ${checks} scenario checks passed`);
