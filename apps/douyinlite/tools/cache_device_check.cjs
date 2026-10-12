'use strict';

// 已授权的设备验收；只清理可再生缓存，不选择“重置网站数据”。
// node tools/cache_device_check.cjs <CDP端口> <证据根目录>
// 每次创建独立目录。账号检测临时恢复完整官网，并只记录数字/布尔；不保存 UI XML 或截图。

const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {connect} = require('./cdp.cjs');

const port = Number(process.argv[2]);
const evidenceRoot = process.argv[3] && path.resolve(process.argv[3]);
if (!Number.isInteger(port) || port < 1 || !evidenceRoot || !process.env.ANDROID_HOME) {
    console.error('Usage: node cache_device_check.cjs PORT EVIDENCE_ROOT; ANDROID_HOME is required.');
    process.exit(2);
}

const startedAtEpochMs = Date.now();
const runDirectory = path.join(evidenceRoot, `cache-${startedAtEpochMs}-${process.pid}`);
const nativeDumpPath = `/sdcard/lightswipe-cache-${startedAtEpochMs}-${process.pid}.xml`;
const adb = path.join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const report = {schemaVersion: 2, startedAtEpochMs, port, checks: {}, cacheMiBBefore: 0,
    cacheMiBAfter: 0, cacheReductionObserved: false, passed: false,
    unexpectedError: false, cleanupSucceeded: true};
let client;
let nativeMenuOpen = false;
let cachePanelOpen = false;

function shell(...args) {
    return execFileSync(adb, ['shell', ...args.map(String)], {encoding: 'utf8', timeout: 15000,
        windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']});
}

function nativeNodes() {
    let xml;
    try {
        shell('uiautomator', 'dump', nativeDumpPath);
        xml = shell('cat', nativeDumpPath);
    } finally { shell('rm', '-f', nativeDumpPath); }
    return [...xml.matchAll(/<node\b[^>]*>/g)].map(match =>
        Object.fromEntries([...match[0].matchAll(/([\w:-]+)="([^"]*)"/g)]
            .map(value => [value[1], value[2]])));
}

function bounds(node) {
    const match = node?.bounds?.match(/^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/);
    if (!match) return null;
    const [, left, top, right, bottom] = match.map(Number);
    return left >= 0 && top >= 0 && right > left && bottom > top ?
        {x: Math.round((left + right) / 2), y: Math.round((top + bottom) / 2)} : null;
}

function control(attribute, value, buttonsOnly = false, nodes = nativeNodes()) {
    return nodes.find(node => node.package === 'com.zhou.douyinlite'
        && node[attribute] === value && node.enabled === 'true'
        && (!buttonsOnly || node.class === 'android.widget.Button') && bounds(node));
}

function tapControl(attribute, value, buttonsOnly = false) {
    const point = bounds(control(attribute, value, buttonsOnly));
    if (!point) throw new Error('Native control unavailable');
    shell('input', 'touchscreen', 'tap', point.x, point.y);
}

function check(name, passed) {
    report.checks[name] = !!passed;
    if (!passed) {
        const failure = new Error('Cache acceptance assertion failed');
        failure.isAssertion = true;
        throw failure;
    }
}

async function until(expression, predicate, timeoutMs = 20000) {
    const deadline = Date.now() + timeoutMs;
    let result;
    do {
        try {
            result = await client.evaluate(expression);
            if (predicate(result)) return result;
        } catch (_) { /* 刷新期间的执行上下文会短暂消失；继续等本次页面，不导出错误文本。 */ }
        await sleep(250);
    } while (Date.now() < deadline);
    return result;
}

const uiExpression = `(() => {
    const ui = window.LightSwipeUi?.status?.();
    return {apiReady: !!ui && typeof window.LightSwipeUi?.select === 'function'
        && typeof window.LightSwipeUi?.setClean === 'function',
        clean: !!ui?.clean, applied: !!ui?.applied, layoutReady: !!ui?.layoutReady,
        channelValid: ui?.channel === 'featured' || ui?.channel === 'recommend'};
})()`;

const accountExpression = `(() => {
    const ui = window.LightSwipeUi?.status?.();
    const visible = element => {
        if (!element?.isConnected) return false;
        const rect = element.getBoundingClientRect();
        if (!(rect.width > 0 && rect.height > 0 && rect.right > 0 && rect.bottom > 0
            && rect.left < innerWidth && rect.top < innerHeight)) return false;
        for (let node = element; node?.nodeType === 1; node = node.parentElement) {
            const style = getComputedStyle(node);
            if (node.hidden || style.display === 'none' || style.visibility === 'hidden'
                || style.visibility === 'collapse' || Number.parseFloat(style.opacity) === 0) return false;
        }
        return true;
    };
    const header = document.getElementById('douyin-header');
    const navigation = document.getElementById('douyin-navigation');
    const fullOfficialLayout = !!ui && !ui.clean && !ui.applied && visible(header) && visible(navigation);
    const loginEntryVisible = fullOfficialLayout && [...header.querySelectorAll('button,a,div,span')]
        .some(element => (element.textContent || '').trim() === '登录' && visible(element));
    // 只检查官网 header 内的账号头像，推荐视频里的作者头像不能作为登录证据。
    const accountEntryVisible = fullOfficialLayout && [...header.querySelectorAll(
        '.avatar-component-avatar-container,[data-e2e="live-avatar"],[data-e2e*="avatar"],img[alt*="头像"]')]
        .some(visible);
    return {fullOfficialLayout, loginEntryVisible, accountEntryVisible,
        recognized: fullOfficialLayout && (loginEntryVisible || accountEntryVisible),
        loggedIn: fullOfficialLayout && accountEntryVisible && !loginEntryVisible};
})()`;

async function accountState() {
    const ready = await until(uiExpression, value => value?.apiReady && value.channelValid);
    if (!ready?.apiReady) return {fullOfficialLayout: false, loginEntryVisible: false,
        accountEntryVisible: false, recognized: false, loggedIn: false};
    try {
        await client.evaluate('window.LightSwipeUi.setClean(false); true');
        return await until(accountExpression, value => value?.fullOfficialLayout && value.recognized, 10000)
            || {fullOfficialLayout: false, loginEntryVisible: false, accountEntryVisible: false,
                recognized: false, loggedIn: false};
    } finally {
        // Native cleanUi 一直保持精简模式；检测结束立刻恢复页面样式。
        await client.evaluate('window.LightSwipeUi?.setClean(true); true');
    }
}

async function openCachePanel() {
    tapControl('content-desc', '设置与账号', true);
    nativeMenuOpen = true;
    await sleep(250);
    tapControl('text', '缓存与网站数据');
    nativeMenuOpen = false;
    cachePanelOpen = true;
    await sleep(700);
    const nodes = nativeNodes();
    const text = nodes.map(node => node.text || '').join('\n');
    const size = text.match(/可清理缓存：([\d.]+) MiB/);
    return {oneGiBThresholdVisible: text.includes('1 GiB'),
        loginPreservationExplained: text.includes('清理缓存保留登录'),
        sizeAvailable: !!size, cacheMiB: size ? Number(size[1]) : 0};
}

async function checkCompactChannels(name) {
    const ui = await until(uiExpression, value => value?.apiReady && value.clean
        && value.applied && value.layoutReady && value.channelValid);
    const nodes = nativeNodes();
    check(name, ui?.apiReady && ui.clean && ui.applied && ui.layoutReady && ui.channelValid
        && !!control('content-desc', '精选', true, nodes)
        && !!control('content-desc', '推荐', true, nodes));
}

async function main() {
    fs.mkdirSync(evidenceRoot, {recursive: true});
    fs.mkdirSync(runDirectory);
    client = await connect(port, 'douyin.com');
    await checkCompactChannels('compactChannelsBefore');
    report.accountBefore = await accountState();
    check('accountRecognizedBefore', report.accountBefore.recognized);
    check('loggedInBefore', report.accountBefore.loggedIn);

    report.panelBefore = await openCachePanel();
    check('oneGiBThresholdVisible', report.panelBefore.oneGiBThresholdVisible);
    check('loginPreservationExplained', report.panelBefore.loginPreservationExplained);
    check('cacheSizeBeforeObserved', report.panelBefore.sizeAvailable);
    report.cacheMiBBefore = report.panelBefore.cacheMiB;
    tapControl('text', '清理缓存');
    cachePanelOpen = false;
    await sleep(1300);

    report.accountAfterClear = await accountState();
    check('accountRecognizedAfterClear', report.accountAfterClear.recognized);
    check('loggedInAfterClear', report.accountAfterClear.loggedIn);
    report.panelAfter = await openCachePanel();
    check('cacheSizeAfterObserved', report.panelAfter.sizeAvailable);
    report.cacheMiBAfter = report.panelAfter.cacheMiB;
    report.cacheReductionObserved = report.cacheMiBAfter < report.cacheMiBBefore;
    // 原生显示只精确到 0.1 MiB，小缓存清理可能前后都显示 0.1。
    check('cacheBytesObservedNotHigher', report.cacheMiBAfter <= report.cacheMiBBefore);
    tapControl('text', '返回');
    cachePanelOpen = false;

    await client.call('Page.reload');
    await until(uiExpression, value => value?.apiReady && value.clean && value.applied && value.layoutReady);
    report.accountAfterReload = await accountState();
    check('accountRecognizedAfterReload', report.accountAfterReload.recognized);
    check('loggedInAfterReload', report.accountAfterReload.loggedIn);
    await checkCompactChannels('compactChannelsAfterReload');
    report.passed = Object.values(report.checks).every(Boolean);
}

(async () => {
    try { await main(); }
    catch (error) {
        report.unexpectedError = !error.isAssertion;
        report.passed = false;
        // 不输出错误消息或 CDP exceptionDetails，它们可能含页面/账号内容。
        process.exitCode = 1;
    } finally {
        try {
            if (cachePanelOpen) { tapControl('text', '返回'); cachePanelOpen = false; }
            if (nativeMenuOpen) { shell('input', 'keyevent', 'KEYCODE_BACK'); nativeMenuOpen = false; }
            if (client) {
                await client.evaluate('window.LightSwipeUi?.setClean(true); true');
                const restored = await until(uiExpression, value => value?.clean && value.applied && value.channelValid);
                report.cleanupSucceeded = !!restored?.clean && restored.applied && restored.channelValid;
            }
        } catch (_) { report.cleanupSucceeded = false; }
        if (client) client.close();
        report.finishedAtEpochMs = Date.now();
        report.passed = report.passed && report.cleanupSucceeded;
        if (!report.passed) process.exitCode = 1;
        fs.mkdirSync(runDirectory, {recursive: true});
        fs.writeFileSync(path.join(runDirectory, 'cache-check.json'), JSON.stringify(report, null, 2) + '\n', {flag: 'wx'});
        console.log(JSON.stringify({passed: report.passed, checks: Object.keys(report.checks).length,
            cacheMiBBefore: report.cacheMiBBefore, cacheMiBAfter: report.cacheMiBAfter,
            cacheReductionObserved: report.cacheReductionObserved,
            unexpectedError: report.unexpectedError, cleanupSucceeded: report.cleanupSucceeded}));
    }
})();
