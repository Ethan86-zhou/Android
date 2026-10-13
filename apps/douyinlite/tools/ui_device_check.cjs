'use strict';

// 仅在已获授权的手机上验收。先由用户完成官网账号登录。
// node tools/ui_device_check.cjs <CDP端口> <证据根目录> [channel|layout|full]
// 每次运行创建独立子目录。只记录布尔和数字，不保存账号、地址、标题、UI XML 或截图。
// 点击均走原生输入：只切频道、开视频、返回及切换官网账号界面，不点赞/评论/分享。

const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {connect} = require('./cdp.cjs');

const port = Number(process.argv[2]);
const evidenceRoot = process.argv[3] && path.resolve(process.argv[3]);
const stage = process.argv[4] || 'full';
if (!Number.isInteger(port) || port < 1 || !evidenceRoot
        || !['channel', 'layout', 'full'].includes(stage) || !process.env.ANDROID_HOME) {
    console.error('Usage: node ui_device_check.cjs PORT EVIDENCE_ROOT [channel|layout|full]; ANDROID_HOME is required.');
    process.exit(2);
}

const startedAtEpochMs = Date.now();
const runDirectory = path.join(evidenceRoot, `ui-${stage}-${startedAtEpochMs}-${process.pid}`);
const nativeDumpPath = `/sdcard/lightswipe-ui-${startedAtEpochMs}-${process.pid}.xml`;
const adb = path.join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const report = {schemaVersion: 1, startedAtEpochMs, port, selectedStage: {[stage]: true},
    phases: {}, passed: false, unexpectedError: false, cleanupSucceeded: true};
let client;
let currentPhase;
let originalFeatured;
let nativeInputStarted = false;
let nativeMenuOpen = false;

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

function rectangle(node) {
    const bounds = node?.bounds?.match(/^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/);
    if (!bounds) return null;
    const [, left, top, right, bottom] = bounds.map(Number);
    return left >= 0 && top >= 0 && right > left && bottom > top ?
        {left, top, width: right - left, height: bottom - top} : null;
}

function nativeControl(attribute, value, buttonsOnly = false) {
    return nativeNodes().find(node => node.package === 'com.zhou.douyinlite'
        && node[attribute] === value && node.enabled === 'true'
        && (!buttonsOnly || node.class === 'android.widget.Button') && rectangle(node));
}

function tapControl(attribute, value, buttonsOnly = false) {
    const node = nativeControl(attribute, value, buttonsOnly);
    const rect = rectangle(node);
    if (!rect) throw new Error('Native control unavailable');
    nativeInputStarted = true;
    shell('input', 'touchscreen', 'tap', Math.round(rect.left + rect.width / 2),
        Math.round(rect.top + rect.height / 2));
    return true;
}

function assert(name, passed) {
    report.phases[currentPhase].checks[name] = !!passed;
    if (!passed) {
        const failure = new Error('UI acceptance assertion failed');
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

// 官网的具体文字、页面地址和媒体源都留在页面进程内；返回结构只有数字/布尔。
const sampleExpression = `(() => {
    const ui = window.LightSwipeUi?.status?.();
    const media = window.LightSwipe?.status?.();
    const visible = element => {
        if (!element || !element.isConnected) return false;
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
    const main = document.getElementById('douyin-right-container');
    const header = document.getElementById('douyin-header');
    const navigation = document.getElementById('douyin-navigation');
    const rect = main?.getBoundingClientRect();
    const finite = value => Number.isFinite(value) ? value : 0;
    const cards = [...document.querySelectorAll('.discover-video-card-item .jingxuanVideoCard')];
    const loginEntryVisible = !!header && [...header.querySelectorAll('a,button,div,span')]
        .some(element => (element.textContent || '').trim() === '登录' && visible(element));
    const accountEntryVisible = !!header && [...header.querySelectorAll(
        '.avatar-component-avatar-container,[data-e2e="live-avatar"],[data-e2e*="avatar"],img[alt*="头像"]')]
        .some(visible);
    const gridReference = window.__lightSwipeUiAcceptanceGrid;
    return {
        uiApiReady: !!ui && typeof window.LightSwipeUi?.select === 'function'
            && typeof window.LightSwipeUi?.setClean === 'function',
        channelFeatured: ui?.channel === 'featured', channelRecommended: ui?.channel === 'recommend',
        clean: !!ui?.clean, applied: !!ui?.applied, layoutReady: !!ui?.layoutReady,
        headerPresent: !!header, navigationPresent: !!navigation,
        headerVisible: visible(header), navigationVisible: visible(navigation),
        mainPresent: !!main, mainLeft: finite(rect?.left), mainTop: finite(rect?.top),
        mainWidth: finite(rect?.width), viewportWidth: finite(innerWidth), viewportHeight: finite(innerHeight),
        visibleFeaturedCards: cards.filter(visible).length,
        featuredModalVisible: [...document.querySelectorAll('.modal-video-container')].some(visible),
        featuredGridReferencePresent: !!gridReference,
        onOriginalFeaturedGridRoute: !!gridReference && location.href === gridReference.address,
        leftOriginalFeaturedGridRoute: !!gridReference && location.href !== gridReference.address,
        mediaApiReady: !!media, visibleVideoAvailable: !!media?.available, visibleVideoPlaying: !!media?.playing,
        loginEntryVisible, accountEntryVisible
    };
})()`;

const sample = () => client.evaluate(sampleExpression);

async function until(predicate, timeoutMs = 20000) {
    const deadline = Date.now() + timeoutMs;
    let result;
    do {
        try {
            result = await sample();
            if (predicate(result)) return result;
        } catch (_) { /* 导航时上下文可能暂时消失；不输出包含页面内容的异常。 */ }
        await sleep(250);
    } while (Date.now() < deadline);
    return result;
}

async function selectChannel(featured) {
    tapControl('content-desc', featured ? '精选' : '推荐', true);
    return until(value => value.uiApiReady && value.clean && value.applied && value.layoutReady
        && (featured ? value.channelFeatured && value.visibleFeaturedCards > 0
            : value.channelRecommended && value.visibleVideoAvailable));
}

function checkChannel(value, featured) {
    assert('uiApiReady', value.uiApiReady);
    assert('expectedChannel', featured ? value.channelFeatured : value.channelRecommended);
    assert('cleanLayoutReady', value.clean && value.applied && value.layoutReady);
    const node = nativeControl('content-desc', featured ? '精选' : '推荐', true);
    assert('nativeTabSelected', node?.selected === 'true');
}

function checkLayout(value) {
    assert('officialHeaderPresent', value.headerPresent);
    assert('officialNavigationPresent', value.navigationPresent);
    assert('officialHeaderHidden', !value.headerVisible);
    assert('officialNavigationHidden', !value.navigationVisible);
    assert('contentPresent', value.mainPresent);
    const tolerance = Math.max(3, value.viewportWidth * 0.02);
    assert('contentAtLeftEdge', Math.abs(value.mainLeft) <= tolerance);
    assert('contentAtTopEdge', Math.abs(value.mainTop) <= tolerance);
    assert('contentFillsViewportWidth', value.viewportWidth > 0
        && Math.abs(value.mainWidth - value.viewportWidth) <= tolerance);
}

async function chooseMenuItem(text) {
    tapControl('content-desc', '设置与账号', true);
    nativeMenuOpen = true;
    await sleep(250);
    tapControl('text', text);
    nativeMenuOpen = false;
}

const featuredCardExpression = `(() => {
    const cards = [...document.querySelectorAll('.discover-video-card-item .jingxuanVideoCard')];
    for (const card of cards) {
        let hidden = false;
        for (let element = card; element?.nodeType === 1; element = element.parentElement) {
            const style = getComputedStyle(element);
            if (element.hidden || style.display === 'none' || style.visibility === 'hidden'
                || Number.parseFloat(style.opacity) === 0) { hidden = true; break; }
        }
        if (hidden) continue;
        // 卡片里也可能有作者头像；取整个卡片上部封面区域，避免误点作者头像。
        const rect = card.getBoundingClientRect();
        const left = Math.max(0, rect.left), top = Math.max(0, rect.top);
        const right = Math.min(innerWidth, rect.right), bottom = Math.min(innerHeight, rect.bottom);
        if (right - left < 24 || bottom - top < 24) continue;
        window.__lightSwipeUiAcceptanceGrid = {address: location.href};
        return {available: true, x: (left + right) / 2, y: top + (bottom - top) * 0.35,
            viewportWidth: innerWidth, viewportHeight: innerHeight};
    }
    return {available: false, x: 0, y: 0, viewportWidth: innerWidth, viewportHeight: innerHeight};
})()`;

async function main() {
    fs.mkdirSync(evidenceRoot, {recursive: true});
    fs.mkdirSync(runDirectory);
    client = await connect(port, 'douyin.com');
    await phase('preflight', async record => {
        record.initial = await until(value => value.uiApiReady && value.clean && value.applied
            && (value.channelFeatured || value.channelRecommended));
        assert('compactUiReady', record.initial.uiApiReady && record.initial.clean && record.initial.applied);
        assert('knownInitialChannel', record.initial.channelFeatured || record.initial.channelRecommended);
        originalFeatured = record.initial.channelFeatured;
        assert('nativeFeaturedTabPresent', !!nativeControl('content-desc', '精选', true));
        assert('nativeRecommendedTabPresent', !!nativeControl('content-desc', '推荐', true));
        assert('nativeSettingsPresent', !!nativeControl('content-desc', '设置与账号', true));
        assert('normalSpeedHasNoOverlay', !nativeControl('content-desc', '恢复正常播放速度', true));
    });

    for (const featured of [true, false]) {
        await phase(featured ? 'featuredChannel' : 'recommendedChannel', async record => {
            record.after = await selectChannel(featured);
            checkChannel(record.after, featured);
            if (stage !== 'channel') checkLayout(record.after);
            if (featured) {
                record.content = await until(value => value.channelFeatured && value.visibleFeaturedCards > 0);
                assert('featuredCardsAvailable', record.content.channelFeatured && record.content.visibleFeaturedCards > 0);
            }
            else {
                record.playback = await until(value => value.visibleVideoAvailable && value.visibleVideoPlaying);
                assert('recommendedVideoAvailable', record.playback.visibleVideoAvailable);
                assert('recommendedVideoPlaying', record.playback.visibleVideoPlaying);
            }
        });
    }

    if (stage === 'full') {
        await phase('featuredPlaybackAndReturn', async record => {
            record.grid = await selectChannel(true);
            checkChannel(record.grid, true);
            record.gridContent = await until(value => value.channelFeatured && value.visibleFeaturedCards > 0);
            assert('featuredGridCardsAvailable', record.gridContent.channelFeatured && record.gridContent.visibleFeaturedCards > 0);
            const card = await client.evaluate(featuredCardExpression);
            assert('visibleOfficialCardFound', card.available);
            const webNode = nativeNodes().find(node => node.package === 'com.zhou.douyinlite'
                && node.class === 'android.webkit.WebView' && rectangle(node));
            const webRect = rectangle(webNode);
            assert('webViewBoundsAvailable', !!webRect);
            assert('cssViewportDimensionsAvailable', card.viewportWidth > 0 && card.viewportHeight > 0);
            nativeInputStarted = true;
            shell('input', 'touchscreen', 'tap',
                Math.round(webRect.left + card.x * webRect.width / card.viewportWidth),
                Math.round(webRect.top + card.y * webRect.height / card.viewportHeight));
            record.opened = await until(value => value.leftOriginalFeaturedGridRoute
                && value.featuredModalVisible && value.visibleVideoAvailable && value.visibleVideoPlaying);
            assert('featuredDetailRouteOpened', record.opened.featuredGridReferencePresent
                && record.opened.leftOriginalFeaturedGridRoute);
            assert('featuredDetailModalVisible', record.opened.featuredModalVisible);
            assert('featuredCardOpensPlayingVideo', record.opened.visibleVideoAvailable && record.opened.visibleVideoPlaying);
            shell('input', 'keyevent', 'KEYCODE_BACK');
            record.returned = await until(value => value.channelFeatured && value.clean && value.applied
                && value.layoutReady && value.visibleFeaturedCards > 0
                && value.onOriginalFeaturedGridRoute && !value.featuredModalVisible);
            assert('returnedToFeaturedGrid', record.returned.channelFeatured
                && record.returned.visibleFeaturedCards > 0 && !record.returned.featuredModalVisible);
            const sameGrid = await client.evaluate(`(() => {
                const reference = window.__lightSwipeUiAcceptanceGrid;
                return {referencePresent: !!reference, sameAddress: !!reference && location.href === reference.address};
            })()`);
            record.gridRestoration = sameGrid;
            assert('returnedToOriginalGridRoute', sameGrid.referencePresent && sameGrid.sameAddress);
            checkLayout(record.returned);
        });

        await phase('officialAccountAndCompactReturn', async record => {
            await chooseMenuItem('登录 / 账号');
            record.official = await until(value => value.uiApiReady && !value.clean && !value.applied
                && value.headerVisible && value.navigationVisible
                && (value.loginEntryVisible || value.accountEntryVisible));
            assert('officialModeRestored', !record.official.clean && !record.official.applied);
            assert('officialHeaderAndNavigationVisible', record.official.headerVisible && record.official.navigationVisible);
            assert('officialLoginOrAccountEntryVisible', record.official.loginEntryVisible || record.official.accountEntryVisible);
            await chooseMenuItem('返回精简界面');
            record.compact = await until(value => value.clean && value.applied && value.layoutReady);
            assert('compactModeRestored', record.compact.clean && record.compact.applied && record.compact.layoutReady);
            checkLayout(record.compact);
        });
    }
    report.passed = Object.values(report.phases).every(value => value.passed);
}

(async () => {
    try { await main(); }
    catch (error) {
        report.passed = false;
        report.unexpectedError = !error.isAssertion;
        // 错误文本和 CDP exceptionDetails 可能含页面信息，不输出或写入记录。
        process.exitCode = 1;
    } finally {
        try {
            if (nativeMenuOpen) { shell('input', 'keyevent', 'KEYCODE_BACK'); nativeMenuOpen = false; }
            if (client && nativeInputStarted && typeof originalFeatured === 'boolean') {
                const restored = await selectChannel(originalFeatured);
                report.cleanupSucceeded = restored.clean && restored.applied && restored.layoutReady
                    && (originalFeatured ? restored.channelFeatured : restored.channelRecommended);
            }
            if (client) await client.evaluate('delete window.__lightSwipeUiAcceptanceGrid; true');
        } catch (_) { report.cleanupSucceeded = false; }
        if (client) client.close();
        report.finishedAtEpochMs = Date.now();
        report.passed = report.passed && report.cleanupSucceeded;
        if (!report.passed) process.exitCode = 1;
        fs.mkdirSync(runDirectory, {recursive: true});
        fs.writeFileSync(path.join(runDirectory, 'ui-device-check.json'), JSON.stringify(report, null, 2) + '\n', {flag: 'wx'});
        console.log(JSON.stringify({passed: report.passed, phases: Object.keys(report.phases).length,
            unexpectedError: report.unexpectedError, cleanupSucceeded: report.cleanupSucceeded}));
    }
})();
