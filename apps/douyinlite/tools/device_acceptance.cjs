'use strict';

// 仅在获授权的设备验收中运行；使用真实官网时先由用户完成登录。
// node tools/device_acceptance.cjs <CDP端口> <probe|real> <独立证据目录> [阶段]
// 阶段：all / preflight / playback / ordinary / hold / lock / unlock / background。
// 触摸全部走 adb 原生输入；CDP 只读媒体状态及存放页面内引用，不使用 CDP Input。
// 不读 Cookie，不导出视频地址/网页标题/账号信息，不截图、不点赞/分享/评论。

const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {connect} = require('./cdp.cjs');

const port = Number(process.argv[2]);
const mode = process.argv[3];
const outputDirectory = process.argv[4] && path.resolve(process.argv[4]);
const stage = process.argv[5] || 'all';
const stages = ['all', 'preflight', 'playback', 'ordinary', 'hold', 'lock', 'unlock', 'background'];
if (!Number.isInteger(port) || port < 1 || !['probe', 'real'].includes(mode)
        || !outputDirectory || !stages.includes(stage) || !process.env.ANDROID_HOME) {
    console.error('Usage: node device_acceptance.cjs PORT probe|real EVIDENCE_DIR [STAGE]; ANDROID_HOME is required.');
    process.exit(2);
}

const adb = path.join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe');
const component = 'com.zhou.douyinlite/.' + (mode === 'probe' ? 'ProbeActivity' : 'MainActivity');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const near = (a, b) => Number.isFinite(a) && Math.abs(a - b) < 0.03;
const report = {
    schemaVersion: 1,
    probe: mode === 'probe',
    port,
    startedAtEpochMs: Date.now(),
    selectedStage: {[stage]: true},
    passed: false,
    phases: {},
    unexpectedError: false,
    cleanupSucceeded: true
};
let client;
let currentPhase;
let touchDown = false;
let nativeInputStarted = false;
let sentHome = false;
const nativeDumpPath = `/sdcard/lightswipe-acceptance-${report.startedAtEpochMs}-${process.pid}.xml`;

function shell(...args) {
    return execFileSync(adb, ['shell', ...args.map(String)], {
        encoding: 'utf8', timeout: 15000, windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe']
    });
}

function nativeTouch(action, x, y) {
    nativeInputStarted = true;
    shell('input', 'touchscreen', 'motionevent', action, x, y);
    if (action === 'DOWN') touchDown = true;
    if (action === 'UP' || action === 'CANCEL') touchDown = false;
}

function nativeSwipe(upward = true) {
    nativeInputStarted = true;
    shell('input', 'touchscreen', 'swipe', 750, upward ? 2350 : 950, 750, upward ? 950 : 2350, 400);
}

function nativeButton(description) {
    // 只在内存中读取 UI 层级，不把网页/账号文字写进验收记录。
    let xml;
    try {
        shell('uiautomator', 'dump', nativeDumpPath);
        xml = shell('cat', nativeDumpPath);
    } finally { shell('rm', '-f', nativeDumpPath); }
    for (const match of xml.matchAll(/<node\b[^>]*>/g)) {
        const attributes = Object.fromEntries([...match[0].matchAll(/([\w:-]+)="([^"]*)"/g)]
            .map(value => [value[1], value[2]]));
        if (attributes.package !== 'com.zhou.douyinlite' || attributes.class !== 'android.widget.Button'
                || attributes['content-desc'] !== description || attributes.enabled !== 'true') continue;
        const bounds = attributes.bounds?.match(/^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/);
        if (!bounds) continue;
        const [, left, top, right, bottom] = bounds.map(Number);
        if (right <= left || bottom <= top || left < 0 || top < 0) continue;
        return {x: Math.round((left + right) / 2), y: Math.round((top + bottom) / 2)};
    }
    return null;
}

function nativeUnlock() {
    const button = nativeButton('恢复正常播放速度');
    // 浮层只在加速期间显示；不存在时不向视频画面发送额外点击。
    if (!button) return false;
    nativeInputStarted = true;
    shell('input', 'touchscreen', 'tap', button.x, button.y);
    return true;
}

function resumeActivity() {
    // NEW_TASK | REORDER_TO_FRONT：恢复现有页面，避免创建新实例并把“重载”当作“恢复”。
    shell('am', 'start', '-n', component, '-f', '0x10020000');
    sentHome = false;
}

function assert(name, passed) {
    report.phases[currentPhase].checks[name] = !!passed;
    if (!passed) {
        const failure = new Error('Acceptance assertion failed');
        failure.isAssertion = true;
        throw failure;
    }
}

async function phase(name, work) {
    currentPhase = name;
    report.phases[name] = {passed: false, checks: {}};
    await work(report.phases[name]);
    report.phases[name].passed = true;
}

// 视频对象、currentSrc、时间基准只存在网页内。返回值严格筛成布尔/数字。
const initializePageReferences = `(() => {
    const visibleVideo = () => {
        let best = null, bestArea = 0;
        for (const video of document.querySelectorAll('video')) {
            if (!video.isConnected) continue;
            const rect = video.getBoundingClientRect();
            let left = Math.max(0, rect.left), top = Math.max(0, rect.top);
            let right = Math.min(innerWidth, rect.right), bottom = Math.min(innerHeight, rect.bottom);
            let hidden = false;
            for (let element = video; element && element.nodeType === 1; element = element.parentElement) {
                const style = getComputedStyle(element);
                if (element.hidden || style.display === 'none' || style.visibility === 'hidden'
                    || style.visibility === 'collapse' || Number.parseFloat(style.opacity) === 0
                    || style.contentVisibility === 'hidden') { hidden = true; break; }
                if (element !== video) {
                    const parent = element.getBoundingClientRect();
                    if (style.overflowX !== 'visible') { left = Math.max(left, parent.left); right = Math.min(right, parent.right); }
                    if (style.overflowY !== 'visible') { top = Math.max(top, parent.top); bottom = Math.min(bottom, parent.bottom); }
                }
            }
            const area = hidden ? 0 : Math.max(0, right - left) * Math.max(0, bottom - top);
            if (area > bestArea || (area > 0 && area === bestArea && !video.paused && best?.paused)) {
                best = video; bestArea = area;
            }
        }
        return best;
    };
    const references = Object.create(null);
    window.__lightSwipeAcceptance = {
        mark(name) {
            const video = visibleVideo();
            references[name] = video ? {video, source: video.currentSrc, rate: video.playbackRate,
                time: video.currentTime, duration: video.duration, loop: video.loop} : null;
            return !!video;
        },
        compare(name) {
            const previous = references[name], video = visibleVideo();
            if (!previous || !video) return {referencePresent: false, changed: false,
                previousRateRestorationApplicable: false, previousRateRestored: false, timeAdvanced: false, timeDelta: 0};
            const changed = previous.video !== video || previous.source !== video.currentSrc;
            let delta = video.currentTime - previous.time;
            if (!changed && delta < 0 && previous.loop && Number.isFinite(previous.duration)) delta += previous.duration;
            return {referencePresent: true, changed,
                previousRateRestorationApplicable: previous.video !== video,
                previousRateRestored: Math.abs(previous.video.playbackRate - previous.rate) < 0.03,
                timeAdvanced: !changed && Number.isFinite(delta) && delta > 0.08,
                timeDelta: !changed && Number.isFinite(delta) ? delta : 0};
        },
        sample() {
            const status = window.LightSwipe?.status(), videos = Array.from(document.querySelectorAll('video'));
            const number = value => Number.isFinite(value) ? value : 0;
            const ui = window.LightSwipeUi?.status?.();
            return {apiReady: !!status, available: !!status?.available, playing: !!status?.playing,
                boosted: !!status?.boosted, boostRequested: !!status?.boostRequested, paused: !!status?.paused,
                rate: number(status?.rate), currentTime: number(status?.currentTime), duration: number(status?.duration),
                videoCount: number(status?.videoCount), allVideosPaused: videos.length > 0 && videos.every(v => v.paused),
                allVideoRatesOne: videos.length > 0 && videos.every(v => Math.abs(v.playbackRate - 1) < 0.03),
                uiApiReady: !!ui && typeof window.LightSwipeUi?.select === 'function'
                    && typeof window.LightSwipeUi?.setClean === 'function',
                uiChannelValid: ui?.channel === 'featured' || ui?.channel === 'recommend',
                uiCleanApplied: !!ui?.clean && !!ui?.applied,
                probeReady: !!window.probeState?.ready, probeActiveIndex: number(window.probeState?.activeIndex),
                probeTransitions: number(window.probeState?.transitions),
                probeErrorCount: number(window.probeState?.errors?.length)};
        }
    };
    return true;
})()`;

const sample = () => client.evaluate('window.__lightSwipeAcceptance.sample()');
const mark = name => client.evaluate(`window.__lightSwipeAcceptance.mark(${JSON.stringify(name)})`);
const compare = name => client.evaluate(`window.__lightSwipeAcceptance.compare(${JSON.stringify(name)})`);

async function until(predicate, timeoutMs = 7000) {
    const deadline = Date.now() + timeoutMs;
    let value;
    do {
        value = await sample();
        if (predicate(value)) return value;
        await sleep(200);
    } while (Date.now() < deadline);
    return value;
}

async function probeFirst() {
    if (mode !== 'probe') return;
    if ((await sample()).probeActiveIndex !== 0) {
        nativeSwipe(false);
        const value = await until(s => s.probeActiveIndex === 0 && s.playing);
        assert('probeReturnedToFirstCard', value.probeActiveIndex === 0 && value.playing);
    }
}

async function nativeLock() {
    nativeTouch('DOWN', 1300, 1200);
    try {
        await sleep(650);
        const held = await until(s => s.boosted && near(s.rate, 2));
        assert('holdBeforeLockAtTwo', held.boosted && near(held.rate, 2));
        nativeTouch('MOVE', 1300, 1600);
        await sleep(200);
    } finally {
        if (touchDown) nativeTouch('UP', 1300, 1600);
    }
    const locked = await until(s => s.boosted && near(s.rate, 2));
    assert('lockedAfterReleaseAtTwo', locked.boosted && near(locked.rate, 2));
    return locked;
}

async function main() {
    fs.mkdirSync(outputDirectory, {recursive: true});
    client = await connect(port, mode === 'probe' ? 'android_asset/probe.html' : 'douyin.com');
    await client.evaluate(initializePageReferences);

    await phase('preflight', async record => {
        const dimensions = [...shell('wm', 'size').matchAll(/(?:Physical|Override) size:\s*(\d+)x(\d+)/g)];
        const last = dimensions.at(-1);
        record.screenWidth = last ? Number(last[1]) : 0;
        record.screenHeight = last ? Number(last[2]) : 0;
        assert('expectedNativeScreen', record.screenWidth === 1440 && record.screenHeight === 3088);
        assert('nativeFeaturedTabPresent', !!nativeButton('精选'));
        assert('nativeRecommendedTabPresent', !!nativeButton('推荐'));
        record.initial = await until(s => s.apiReady && s.available && s.playing && (mode !== 'probe' || s.probeReady), 20000);
        assert('mediaApiReady', record.initial.apiReady);
        assert('visibleVideoAvailable', record.initial.available);
        assert('visibleVideoPlaying', record.initial.playing);
        assert('startsAtNormalRate', near(record.initial.rate, 1) && !record.initial.boosted);
        if (mode === 'probe') {
            assert('probeReady', record.initial.probeReady);
            assert('probeNoGenerationErrors', record.initial.probeErrorCount === 0);
            if ((stage === 'all' || stage === 'ordinary') && record.initial.probeActiveIndex !== 0) {
                nativeSwipe(false);
                const first = await until(s => s.probeActiveIndex === 0 && s.playing);
                assert('probeFirstCardPrecondition', first.probeActiveIndex === 0);
            }
        } else {
            assert('uiApiReady', record.initial.uiApiReady);
            assert('uiChannelValid', record.initial.uiChannelValid);
            assert('uiCleanApplied', record.initial.uiCleanApplied);
        }
    });

    if (stage === 'all' || stage === 'playback') await phase('playback', async record => {
        assert('playbackReferencePresent', await mark('playback'));
        await sleep(1000);
        record.measurement = await compare('playback');
        assert('sameVideoForPlaybackMeasure', !record.measurement.changed);
        assert('playbackTimeAdvanced', record.measurement.timeAdvanced);
    });

    if (stage === 'all' || stage === 'ordinary') await phase('ordinary', async record => {
        record.before = await sample();
        assert('ordinaryStartsAtNormalRate', near(record.before.rate, 1) && !record.before.boosted);
        assert('ordinaryReferencePresent', await mark('ordinary'));
        nativeSwipe();
        await sleep(1000);
        record.after = await sample();
        record.reference = await compare('ordinary');
        assert('ordinarySwipeDoesNotBoost', near(record.after.rate, 1) && !record.after.boosted && !record.after.boostRequested);
        if (mode === 'probe') assert('ordinarySwipeChangesCard', record.after.probeActiveIndex !== record.before.probeActiveIndex);
        else assert('ordinarySwipeChangesVideo', record.reference.changed);
        await probeFirst();
    });

    if (stage === 'all' || stage === 'hold') await phase('hold', async record => {
        nativeTouch('DOWN', 1300, 1400);
        try {
            await sleep(650);
            record.held = await until(s => s.boosted && near(s.rate, 2));
            assert('stationaryHoldAtTwo', record.held.boosted && near(record.held.rate, 2));
        } finally {
            if (touchDown) nativeTouch('UP', 1300, 1400);
        }
        record.released = await until(s => near(s.rate, 1) && !s.boosted);
        assert('releaseRestoresOne', near(record.released.rate, 1) && !record.released.boosted);
    });

    if (stage === 'all' || stage === 'lock') await phase('lock', async record => {
        await probeFirst();
        assert('lockedReferencePresent', await mark('locked'));
        record.before = await sample();
        record.locked = await nativeLock();
        nativeSwipe();
        await sleep(1000);
        record.afterSwipe = await until(s => s.playing && s.boosted && near(s.rate, 2));
        record.reference = await compare('locked');
        assert('lockedSwipeChangesVideo', record.reference.changed);
        assert('lockedSwipeKeepsTwo', record.afterSwipe.boosted && near(record.afterSwipe.rate, 2));
        // 官网可能复用同一个 video 节点更换 src；这种情况下当前节点继续 2× 才正确。
        assert('oldVideoRestoresPreviousRateWhenNodeChanges', !record.reference.previousRateRestorationApplicable
            || record.reference.previousRateRestored);
        if (mode === 'probe') assert('lockedSwipeChangesCard', record.afterSwipe.probeActiveIndex !== record.before.probeActiveIndex);
    });

    if (stage === 'all' || stage === 'unlock') await phase('unlock', async record => {
        if (stage === 'unlock') record.locked = await nativeLock();
        record.nativeUnlockControlTapped = nativeUnlock();
        assert('nativeUnlockControlVisible', record.nativeUnlockControlTapped);
        record.after = await until(s => near(s.rate, 1) && !s.boosted && !s.boostRequested);
        assert('nativeButtonRestoresOne', near(record.after.rate, 1) && !record.after.boosted && !record.after.boostRequested);
    });

    if (stage === 'all' || stage === 'background') await phase('background', async record => {
        nativeUnlock();
        const ready = await until(s => near(s.rate, 1) && s.playing && !s.boosted);
        assert('backgroundStartsPlayingAtOne', ready.playing && near(ready.rate, 1));
        assert('backgroundReferencePresent', await mark('background'));
        record.beforeHome = await nativeLock();
        shell('input', 'keyevent', 'KEYCODE_HOME'); sentHome = true;
        await sleep(800);
        record.hidden = await until(s => s.paused && s.allVideosPaused && !s.boosted);
        record.restoration = await compare('background');
        assert('backgroundPausesEveryVideo', record.hidden.paused && record.hidden.allVideosPaused);
        assert('backgroundRestoresOriginalRate', !record.hidden.boosted && near(record.hidden.rate, 1)
            && record.restoration.previousRateRestored);
        await mark('hiddenTime'); await sleep(800);
        record.hiddenTime = await compare('hiddenTime');
        assert('backgroundPlaybackTimeStops', !record.hiddenTime.changed && !record.hiddenTime.timeAdvanced);
        resumeActivity();
        record.resumed = await until(s => !s.paused && s.playing && near(s.rate, 1) && !s.boosted, 10000);
        record.resumedReference = await compare('background');
        assert('samePageVideoAfterResume', record.resumedReference.referencePresent && !record.resumedReference.changed);
        assert('foregroundRestoresOriginalPlayback', !record.resumed.paused && record.resumed.playing
            && near(record.resumed.rate, 1) && !record.resumed.boosted);
        await mark('resumedTime'); await sleep(900);
        record.resumedTime = await compare('resumedTime');
        assert('resumedPlaybackTimeAdvanced', record.resumedTime.timeAdvanced);
    });

    report.passed = Object.values(report.phases).every(value => value.passed);
}

(async () => {
    try {
        await main();
    } catch (error) {
        report.passed = false;
        report.unexpectedError = !error.isAssertion;
        // 不写 error.message / CDP exceptionDetails，它们可能带网页地址或页面内容。
        process.exitCode = 1;
    } finally {
        try {
            if (touchDown) nativeTouch('UP', 1300, 1600);
            if (sentHome) { resumeActivity(); await sleep(500); }
            if (nativeInputStarted) nativeUnlock();
            if (client) await client.evaluate('delete window.__lightSwipeAcceptance; true');
        } catch (_) { report.cleanupSucceeded = false; process.exitCode = 1; }
        if (client) client.close();
        report.finishedAtEpochMs = Date.now();
        report.passed = report.passed && report.cleanupSucceeded;
        fs.mkdirSync(outputDirectory, {recursive: true});
        const file = path.join(outputDirectory, `device-acceptance-${mode}-${stage}-${report.startedAtEpochMs}.json`);
        fs.writeFileSync(file, JSON.stringify(report, null, 2) + '\n', {flag: 'wx'});
        console.log(JSON.stringify({passed: report.passed, phases: Object.keys(report.phases).length,
            unexpectedError: report.unexpectedError, cleanupSucceeded: report.cleanupSucceeded}));
    }
})();
