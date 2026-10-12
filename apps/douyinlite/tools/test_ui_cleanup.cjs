'use strict';

// 无第三方依赖：检查可逆布局、官网边界、SPA 路由和 DOM 挂载时序。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/ui-cleanup.js'), 'utf8');
let checks = 0;

class Events {
    constructor() { this.listeners = new Map(); this.dispatched = []; }
    addEventListener(name, callback) {
        if (!this.listeners.has(name)) this.listeners.set(name, new Set());
        this.listeners.get(name).add(callback);
    }
    removeEventListener(name, callback) { this.listeners.get(name)?.delete(callback); }
    fire(name) { for (const callback of this.listeners.get(name) || []) callback(); }
    dispatchEvent(event) {
        this.dispatched.push(event);
        for (const callback of this.listeners.get(event.type) || []) callback(event);
        return true;
    }
}

function harness(initialUrl = 'https://www.douyin.com/?recommend=1') {
    const document = new Events();
    const window = new Events();
    const timers = new Map();
    const observers = [];
    const nodes = [];
    const selectors = new Map();
    const navigations = [];
    let timerId = 0;
    let now = 0;
    let currentUrl = new URL(initialUrl);
    function mutate(type = 'childList', name) {
        for (const observer of observers) {
            if (!observer.active) continue;
            if (type === 'attributes' && !observer.options.attributeFilter.includes(name)) continue;
            observer.callback([{ type, attributeName: name }]);
        }
    }
    class Element {
        constructor() {
            this.attributes = new Map();
            this.classes = new Set();
            this.classList = { contains: name => this.classes.has(name) };
            this.isConnected = true;
            this.parentElement = null;
            this.textContent = '';
            this.clicks = 0;
            this.id = '';
            this.nodeType = 1;
            this.hidden = false;
            this.style = {};
            nodes.push(this);
        }
        getAttribute(name) { return this.attributes.get(name) ?? null; }
        hasAttribute(name) { return this.attributes.has(name); }
        setAttribute(name, value) { this.attributes.set(name, value); mutate('attributes', name); }
        removeAttribute(name) {
            if (this.attributes.delete(name)) mutate('attributes', name);
        }
        appendChild(child) { child.isConnected = true; child.parentElement = this; mutate(); }
        remove() { this.isConnected = false; mutate(); }
        click() { this.clicks++; if (this.onClick) this.onClick(); }
        getBoundingClientRect() { return { left: 0, top: 0, width: 300, height: 600, right: 300, bottom: 600 }; }
    }
    document.documentElement = new Element();
    document.head = new Element();
    document.hidden = false;
    document.getElementById = id => nodes.find(node => node.isConnected && node.id === id) || null;
    document.createElement = tag => {
        assert.equal(tag, 'style');
        return new Element();
    };
    document.querySelectorAll = selector => {
        assert(!/(^|[ >+~,(])video(?:[.#\[:\s>+~,)\]]|$)/.test(selector), 'layout code must not inspect media');
        return selectors.get(selector) || [];
    };
    document.querySelector = selector => document.querySelectorAll(selector)[0] || null;
    Object.defineProperty(document, 'cookie', { get() { throw new Error('cookie must not be accessed'); } });
    Object.defineProperty(document, 'title', { get() { throw new Error('page title must not be accessed'); } });
    window.location = {
        get protocol() { return currentUrl.protocol; },
        get hostname() { return currentUrl.hostname; },
        get pathname() { return currentUrl.pathname; },
        get search() { return currentUrl.search; },
        get href() { return currentUrl.href; },
        assign(url) { navigations.push(url); currentUrl = new URL(url); }
    };
    window.history = {
        pushState(state, unused, url) { if (url) currentUrl = new URL(url, currentUrl); return 'push-result'; },
        replaceState(state, unused, url) { if (url) currentUrl = new URL(url, currentUrl); return 'replace-result'; }
    };
    const originalPushState = window.history.pushState;
    window.setTimeout = (callback, delay) => {
        timers.set(++timerId, { callback, at: now + delay });
        return timerId;
    };
    window.clearTimeout = id => timers.delete(id);
    window.setInterval = () => { throw new Error('UI layout must not poll'); };
    window.getComputedStyle = element => ({ display: 'block', visibility: 'visible', opacity: '1', ...element.style });
    class Observer {
        constructor(callback) { this.callback = callback; this.active = false; observers.push(this); }
        observe(target, options) { this.active = true; this.options = options; }
        disconnect() { this.active = false; }
    }
    class KeyboardEvent {
        constructor(type, options) { this.type = type; Object.assign(this, options); }
    }
    const context = vm.createContext({ window, document, MutationObserver: Observer, KeyboardEvent, URL, URLSearchParams, Set });
    const install = () => vm.runInContext(source, context);
    install();
    return {
        window, document, timers, navigations, selectors, originalPushState, mutate, install,
        api: () => window.LightSwipeUi,
        element: options => Object.assign(new Element(), options),
        at: selector => selectors.get(selector)?.[0],
        mount: (selector, ...elements) => { selectors.set(selector, elements); mutate(); },
        navigate: url => { currentUrl = new URL(url, currentUrl); },
        observers: () => observers.filter(item => item.active).length,
        tick(ms = 100) {
            now += ms;
            for (const [id, timer] of Array.from(timers)) {
                if (timer.at > now || !timers.has(id)) continue;
                timers.delete(id);
                timer.callback();
            }
        },
        css: () => document.getElementById('light-swipe-clean-style')?.textContent
    };
}

function check(name, test) {
    test();
    checks++;
    console.log(`PASS ${name}`);
}

check('only the two official HTTPS hosts receive styles and routing hooks', () => {
    for (const url of ['http://www.douyin.com/', 'https://douyin.com.evil.test/',
        'https://sso.douyin.com/', 'https://example.test/?recommend=1']) {
        const h = harness(url);
        assert.equal(h.api().status().applied, false);
        assert.equal(h.api().status().channel, null);
        assert.equal(h.api().select('recommend'), false);
        assert.equal(h.css(), undefined);
        assert.equal(h.observers(), 0);
        assert.equal(h.window.history.pushState, h.originalPushState);
    }
    assert.equal(harness('https://douyin.com/?recommend=1').api().status().applied, true);
});

check('status exposes only layout state and does not read account or media fields', () => {
    const h = harness();
    const state = h.api().status();
    assert.deepEqual(Object.keys(state).sort(), ['applied', 'channel', 'clean', 'layoutReady', 'playerActive']);
    assert.equal(state.channel, 'recommend');
    assert.equal(state.layoutReady, false);
    h.element({ id: 'douyin-right-container' });
    h.mount('#slidelist.recommend-slidelist', h.element());
    assert.equal(h.api().status().layoutReady, true);
});

check('styles are scoped and preserve navigation nodes, dialogs and playback', () => {
    const h = harness();
    const css = h.css();
    assert(css.includes('html[data-light-swipe-clean] #douyin-navigation'));
    assert(css.includes('#douyin-right-container{width:100%'));
    assert(css.includes('padding-top:0!important'));
    assert(css.includes('height:100vh!important;height:100dvh!important'));
    assert(!css.includes('#douyin-sidebar'));
    assert(!/(^|,)\s*(header|nav|aside|dialog)\s*\{/.test(css));
    assert(!source.includes('removeChild'));
    assert(!source.includes('playbackRate'));
});

check('desktop playback controls and reserved height change only inside the two full player roots', () => {
    const h = harness();
    const rules = h.css().split('\n');
    const controls = rules.filter(rule => rule.includes('xg-controls'));
    assert.equal(controls.length, 1);
    const heights = rules.filter(rule => rule.includes('xg-video-container'));
    assert.equal(heights.length, 1);
    for (const rule of [...controls, ...heights]) {
        const selectors = rule.slice(0, rule.indexOf('{')).split(',');
        assert.equal(selectors.length, 2);
        assert(selectors.every(selector => selector.startsWith('html[data-light-swipe-clean] ')), 'account mode must opt out of every playback override');
        assert(selectors.some(selector => selector.includes('#slidelist.recommend-slidelist .xgplayer ')), 'recommendation override needs the full player root');
        assert(selectors.some(selector => selector.includes('.modal-video-container .xgplayer ')), 'featured override needs the modal player root');
        assert(selectors.every(selector => !selector.includes('.jingxuanVideoCard')), 'grid preview players remain untouched');
    }
    assert(controls[0].includes('xg-controls.xgplayer-controls'));
    assert(controls[0].includes('display:none!important'));
    assert(heights[0].includes('height:100%!important'));
    assert(!h.css().includes('.player-position-box-bottom'), 'metadata offset waits for device visual evidence');
    h.api().setClean(false);
    assert.equal(h.css(), undefined, 'account mode restores website control styling');
    assert.equal(h.document.documentElement.hasAttribute('data-light-swipe-clean'), false);
});

check('playerActive identifies full recommendation players and visible featured modals only', () => {
    const h = harness();
    assert.equal(h.api().status().playerActive, false);
    const list = h.element();
    h.mount('#slidelist.recommend-slidelist', list);
    assert.equal(h.api().status().playerActive, true);
    h.navigate('/jingxuan');
    h.mount('#douyin-right-container .jingxuan-scroll-element .discover-video-card-item', h.element());
    h.mount('.jingxuanVideoCard .xgplayer', h.element());
    assert.equal(h.api().status().playerActive, false, 'grid previews and a stale recommendation route do not show native playback controls');
    const modal = h.element(); h.mount('.modal-video-container', modal);
    assert.equal(h.api().status().playerActive, true);
    modal.hidden = true;
    assert.equal(h.api().status().playerActive, false);
    assert.equal(h.document.dispatched.length, 0, 'observing active player does not close the modal');
});

check('playerActive excludes zero-sized, detached and ancestor-hidden complete players', () => {
    const h = harness();
    const list = h.element(); h.mount('#slidelist.recommend-slidelist', list);
    const parent = h.element(); list.parentElement = parent;
    assert.equal(h.api().status().playerActive, true);
    for (const style of [{ display: 'none' }, { visibility: 'hidden' }, { opacity: '0' }]) {
        parent.style = style;
        assert.equal(h.api().status().playerActive, false);
    }
    parent.style = {}; parent.hidden = true;
    assert.equal(h.api().status().playerActive, false);
    parent.hidden = false;
    list.getBoundingClientRect = () => ({ width: 300, height: 0 });
    assert.equal(h.api().status().playerActive, false);
    list.getBoundingClientRect = () => ({ width: 300, height: 600 });
    list.isConnected = false;
    assert.equal(h.api().status().playerActive, false);
    list.isConnected = true;
    assert.equal(h.api().status().playerActive, true);
});

check('playerActive is disabled for account mode, nonofficial pages, hidden documents and destroyed instances', () => {
    const h = harness();
    h.mount('#slidelist.recommend-slidelist', h.element());
    h.mount('.modal-video-container', h.element());
    assert.equal(h.api().status().playerActive, true);
    assert.equal(h.api().setClean(false).playerActive, false);
    assert.equal(h.api().setClean(true).playerActive, true);
    h.document.hidden = true;
    assert.equal(h.api().status().playerActive, false);
    h.document.hidden = false;
    h.navigate('https://example.test/?recommend=1');
    assert.equal(h.api().status().playerActive, false);
    h.navigate('https://www.douyin.com/?recommend=1');
    assert.equal(h.api().status().playerActive, true);
    h.api().destroy();
    assert.equal(h.api().status().playerActive, false);
});

check('featured pathname takes precedence over a stale recommendation tab', () => {
    const h = harness('https://www.douyin.com/jingxuan?from_nav=1&recommend=1');
    const tab = h.element(); tab.classes.add('active');
    h.mount('[data-e2e="tab-recommend"],.tab-recommend,#tab-recommend', tab);
    assert.equal(h.api().status().channel, 'featured');
    h.navigate('/jingxuan/detail');
    assert.equal(h.api().status().channel, 'featured');
    h.navigate('/jingxuan-other');
    assert.equal(h.api().status().channel, 'recommend');
});

check('active recommendation tab and query both identify the channel', () => {
    const h = harness('https://www.douyin.com/');
    assert.equal(h.api().status().channel, null);
    const tab = h.element();
    h.mount('[data-e2e="tab-recommend"],.tab-recommend,#tab-recommend', tab);
    tab.classes.add('active');
    assert.equal(h.api().status().channel, 'recommend');
    tab.classes.clear(); tab.setAttribute('aria-selected', 'true');
    assert.equal(h.api().status().channel, 'recommend');
    tab.removeAttribute('aria-selected');
    h.navigate('/?recommend=0');
    assert.equal(h.api().status().channel, null);
});

check('featured grid uses semantic card ancestry and restores every marker', () => {
    const h = harness('https://www.douyin.com/jingxuan');
    const outer = h.element(); outer.classes.add('jingxuan-scroll-element');
    const grid = h.element({ parentElement: outer });
    const card = h.element({ parentElement: grid });
    h.mount('#douyin-right-container .jingxuan-scroll-element .discover-video-card-item', card);
    h.tick();
    assert.equal(grid.hasAttribute('data-light-swipe-card-grid'), true);
    assert(h.css().includes('grid-template-columns:minmax(0,1fr)!important'));
    assert(!h.css().includes('@media(max-width:360px)'));
    h.api().setClean(false);
    assert.equal(grid.hasAttribute('data-light-swipe-card-grid'), false);
    assert.equal(h.document.documentElement.hasAttribute('data-light-swipe-clean'), false);
    assert.equal(h.css(), undefined);
    assert.equal(h.api().status().clean, false);
    assert.equal(h.api().setClean(true).applied, true);
    assert.equal(grid.hasAttribute('data-light-swipe-card-grid'), true);
});

check('SPA history hooks preserve return values and update layout without page completion', () => {
    const h = harness();
    assert.equal(h.window.history.pushState({}, '', '/jingxuan'), 'push-result');
    h.tick();
    assert.equal(h.document.documentElement.getAttribute('data-light-swipe-channel'), 'featured');
    assert.equal(h.window.history.replaceState({}, '', '/?recommend=1'), 'replace-result');
    h.tick();
    assert.equal(h.document.documentElement.getAttribute('data-light-swipe-channel'), 'recommend');
    h.navigate('/jingxuan'); h.window.fire('popstate'); h.tick();
    assert.equal(h.document.documentElement.getAttribute('data-light-swipe-channel'), 'featured');
});

check('mutation batches debounce without a polling or self-triggering loop', () => {
    const h = harness();
    h.tick();
    assert.equal(h.timers.size, 0);
    for (let i = 0; i < 50; i++) h.mutate();
    assert.equal(h.timers.size, 1);
    h.tick();
    assert.equal(h.timers.size, 0);
    for (let i = 0; i < 5; i++) h.api().status();
    assert.equal(h.timers.size, 0);
});

check('hidden pages wait until visibility resumes and retain clean mode preference', () => {
    const h = harness(); h.tick();
    h.document.hidden = true;
    h.navigate('/jingxuan'); h.mutate();
    assert.equal(h.timers.size, 0);
    h.document.hidden = false; h.document.fire('visibilitychange'); h.tick();
    assert.equal(h.document.documentElement.getAttribute('data-light-swipe-channel'), 'featured');
    h.api().setClean(false); h.tick(); h.mutate(); h.tick();
    assert.equal(h.css(), undefined);
});

check('channel selection clicks exact official anchors even when CSS hides their parent', () => {
    const h = harness();
    const wrongLabel = h.element({ textContent: '精选内容', href: 'https://www.douyin.com/jingxuan' });
    const wrongOrigin = h.element({ textContent: '精选', href: 'https://evil.test/jingxuan' });
    const official = h.element({ textContent: ' 精选\n', href: 'https://www.douyin.com/jingxuan?from_nav=1',
        onClick: () => h.window.history.pushState({}, '', '/jingxuan?from_nav=1') });
    h.mount('#douyin-navigation a', wrongLabel, wrongOrigin, official);
    assert.equal(h.api().select('featured'), true);
    assert.equal(official.clicks, 1);
    assert.equal(wrongLabel.clicks, 0);
    assert.equal(wrongOrigin.clicks, 0);
    assert.equal(h.navigations.length, 0);
    assert.equal(h.api().status().channel, 'featured');
    assert.equal(h.api().select('featured'), true);
    assert.equal(official.clicks, 1);
    assert.equal(h.api().select('live'), false);
});

check('late SPA anchors fulfill one pending channel request without page reload', () => {
    const h = harness();
    assert.equal(h.api().select('featured'), true);
    assert.equal(h.navigations.length, 0);
    const anchor = h.element({ textContent: '精选', href: 'https://www.douyin.com/jingxuan' });
    h.mount('#douyin-navigation a', anchor);
    h.tick(); h.mutate(); h.tick(1500);
    assert.equal(anchor.clicks, 1);
    assert.equal(h.navigations.length, 0);
});

check('missing anchors cause one fallback with the observed official navigation parameters', () => {
    const h = harness('https://www.douyin.com/jingxuan');
    assert.equal(h.api().select('recommend'), true);
    h.tick(1200);
    assert.deepEqual(h.navigations, ['https://www.douyin.com/?is_from_mobile_home=1&recommend=1&from_nav=1']);
    h.tick(5000); h.api().select('recommend'); h.tick(5000);
    assert.equal(h.navigations.length, 1);
});

check('a newer channel choice cancels an old pending choice', () => {
    const h = harness();
    h.api().select('featured');
    h.api().select('recommend');
    h.tick(1500);
    assert.equal(h.navigations.length, 0);
});

check('account mode cancels pending navigation before anchors appear or fallback fires', () => {
    const h = harness();
    h.api().select('featured');
    h.api().setClean(false);
    const anchor = h.element({ textContent: '精选', href: 'https://www.douyin.com/jingxuan' });
    h.mount('#douyin-navigation a', anchor);
    h.tick(2000);
    for (let i = 0; i < 5; i++) h.api().status();
    assert.equal(anchor.clicks, 0);
    assert.equal(h.navigations.length, 0);
    assert.equal(h.api().status().clean, false);
    assert.equal(h.css(), undefined);
});

check('normal video route changes and repeated status reads never create a navigation intent', () => {
    const h = harness();
    h.tick();
    h.window.history.pushState({}, '', '/video/example');
    h.tick();
    for (let i = 0; i < 10; i++) h.api().status();
    h.tick(3000);
    assert.equal(h.api().status().channel, null);
    assert.equal(h.navigations.length, 0);
    assert.equal(h.timers.size, 0);
});

check('dismissVideo does not dispatch keys without a visible official video modal', () => {
    const h = harness();
    assert.equal(h.api().dismissVideo(), false);
    const modal = h.element({ getBoundingClientRect: () => ({ width: 0, height: 600 }) });
    h.mount('.modal-video-container', modal);
    assert.equal(h.api().dismissVideo(), false);
    modal.getBoundingClientRect = () => ({ width: 300, height: 600 });
    const parent = h.element({ hidden: true }); modal.parentElement = parent;
    assert.equal(h.api().dismissVideo(), false);
    parent.hidden = false; parent.style = { display: 'none' };
    assert.equal(h.api().dismissVideo(), false);
    parent.style = { visibility: 'hidden' };
    assert.equal(h.api().dismissVideo(), false);
    parent.style = { opacity: '0' };
    assert.equal(h.api().dismissVideo(), false);
    assert.equal(h.document.dispatched.length, 0);
    const unsupported = harness('https://example.test/');
    unsupported.mount('.modal-video-container', unsupported.element());
    assert.equal(unsupported.api().dismissVideo(), false);
    assert.equal(unsupported.document.dispatched.length, 0);
});

check('visible video dismissal sends the official Escape key while status stays observational', () => {
    const h = harness('https://www.douyin.com/jingxuan?modal_id=example');
    h.mount('.modal-video-container', h.element());
    for (let i = 0; i < 5; i++) h.api().status();
    h.tick();
    assert.equal(h.document.dispatched.length, 0);
    assert.equal(h.api().dismissVideo(), true);
    assert.equal(h.document.dispatched.length, 1);
    const event = h.document.dispatched[0];
    assert.equal(event.type, 'keydown');
    assert.equal(event.key, 'Escape');
    assert.equal(event.code, 'Escape');
    assert.equal(event.keyCode, 27);
    assert.equal(event.which, 27);
    assert.equal(event.bubbles, true);
    assert.equal(h.window.location.search, '?modal_id=example', 'only the website handler may alter routing');
});

check('selecting the active featured channel closes its modal through the website handler', () => {
    const h = harness('https://www.douyin.com/jingxuan?modal_id=example');
    const modal = h.element(); h.mount('.modal-video-container', modal);
    h.document.addEventListener('keydown', event => {
        assert.equal(event.key, 'Escape');
        modal.hidden = true;
        h.window.history.replaceState({}, '', '/jingxuan');
    });
    const anchor = h.element({ textContent: '精选', href: 'https://www.douyin.com/jingxuan' });
    h.mount('#douyin-navigation a', anchor);
    assert.equal(h.api().select('featured'), true);
    assert.equal(h.document.dispatched.length, 1);
    assert.equal(anchor.clicks, 0);
    assert.equal(h.window.location.search, '');
    assert.equal(h.navigations.length, 0);
    assert.equal(h.api().dismissVideo(), false);
});

check('destroy removes style, pending actions, observers, event handlers and owned history hooks', () => {
    const h = harness();
    h.api().select('featured');
    h.api().destroy(); h.api().destroy(); h.tick(5000);
    assert.equal(h.css(), undefined);
    assert.equal(h.observers(), 0);
    assert.equal(h.timers.size, 0);
    assert.equal(h.navigations.length, 0);
    assert.equal(h.window.history.pushState, h.originalPushState);
    assert.equal(h.api().status().applied, false);
    assert.equal(h.api().select('recommend'), false);
    assert.equal(h.api().setClean(true).applied, false);
});

check('destroy preserves a newer website history wrapper', () => {
    const h = harness();
    const replacement = () => 'website wrapper';
    h.window.history.pushState = replacement;
    h.api().destroy();
    assert.equal(h.window.history.pushState, replacement);
});

check('reinjecting replaces the prior instance without duplicate observers', () => {
    const h = harness();
    h.api().select('featured');
    h.install(); h.tick(5000);
    assert.equal(h.observers(), 1);
    assert.equal(h.navigations.length, 0);
    assert.equal(h.api().status().applied, true);
});

console.log(`${checks} UI cleanup scenario groups passed.`);
