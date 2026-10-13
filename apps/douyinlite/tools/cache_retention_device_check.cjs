'use strict';

// 只读缓存保留验收。唯一媒体操作为暂时 pause / finally 恢复原状态，不请求清理或写设备文件。
// node tools/cache_retention_device_check.cjs PORT OUTPUT [SERIAL]
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {connect} = require('./cdp.cjs');
const port = Number(process.argv[2]);
const root = process.argv[3] && path.resolve(process.argv[3]);
const serial = process.argv[4];
if (!Number.isInteger(port) || port < 1 || port > 65535 || !root || !process.env.ANDROID_HOME) {
    console.error('Usage: node cache_retention_device_check.cjs PORT OUTPUT [SERIAL]; ANDROID_HOME required.');
    process.exit(2);
}
const started = Date.now();
const out = path.join(root, `cache-retention-${started}-${process.pid}`);
const key = JSON.stringify(`__lightSwipeCacheRetention${started}_${process.pid}`);
const adb = path.join(process.env.ANDROID_HOME, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const adbPrefix = serial ? ['-s', serial] : [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const report = {schemaVersion:1, startedAtEpochMs:started, checks:{}, observations:{},
    requestedCacheClearing:false, physicalOneGiBPressureTested:false,
    passed:false, unexpectedError:false, cleanupSucceeded:false};
let client, sentHome = false, captured = false, captureAttempted = false;

function shell(...args) {
    return execFileSync(adb, [...adbPrefix, 'shell', ...args.map(String)],
        {encoding:'utf8', timeout:15000, windowsHide:true, stdio:['ignore','pipe','pipe']});
}
function check(name, passed) {
    report.checks[name] = !!passed;
    if (!passed) { const error = new Error('Retention assertion failed'); error.assertion = true; throw error; }
}
function diskUsage() {
    const raw = shell('run-as', 'com.zhou.douyinlite', 'du', '-sk', 'cache', 'app_webview');
    const entries = raw.trim().split(/\r?\n/).map(line => line.match(/^(\d+)\s+(cache|app_webview)$/));
    if (entries.length !== 2 || entries.some(entry => !entry)) throw new Error('Disk observation unavailable');
    const values = Object.fromEntries(entries.map(entry => [entry[2], Number(entry[1])]));
    if (!Number.isSafeInteger(values.cache) || !Number.isSafeInteger(values.app_webview)) {
        throw new Error('Invalid disk observation');
    }
    return {cacheKiB:values.cache, webViewAndSiteKiB:values.app_webview,
        totalKiB:values.cache + values.app_webview};
}
function nativeBackgroundConfirmed() {
    // 只在内存中检查系统当前 resumed Activity；其他应用名称与 dumpsys 原文不保存、不输出。
    const raw = shell('dumpsys', 'activity', 'activities');
    const resumed = raw.split(/\r?\n/).filter(line =>
        /\b(?:topResumedActivity|mResumedActivity)\s*[:=]\s*ActivityRecord\{/.test(line));
    return resumed.length > 0 && resumed.every(line => !line.includes('com.zhou.douyinlite/'));
}

const capture = `(() => {
    if (document.hidden || window[${key}]) return {captured:false};
    const ui=window.LightSwipeUi?.status?.();
    if (ui?.channel!=='recommend' || !ui?.clean || !ui?.playerActive) return {captured:false};
    const area=element=>{
        if (!element.isConnected) return 0;
        let rect=element.getBoundingClientRect();
        let left=Math.max(0,rect.left), top=Math.max(0,rect.top);
        let right=Math.min(innerWidth,rect.right), bottom=Math.min(innerHeight,rect.bottom);
        for(let node=element;node?.nodeType===1;node=node.parentElement){
            const style=getComputedStyle(node);
            if(node.hidden||style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||+style.opacity===0)return 0;
            if(node!==element&&(style.overflowX!=='visible'||style.overflowY!=='visible')){
                const parent=node.getBoundingClientRect();
                if(style.overflowX!=='visible'){left=Math.max(left,parent.left);right=Math.min(right,parent.right);}
                if(style.overflowY!=='visible'){top=Math.max(top,parent.top);bottom=Math.min(bottom,parent.bottom);}
            }
        }
        return Math.max(0,right-left)*Math.max(0,bottom-top);
    };
    const videos=[...document.querySelectorAll('video')].map(video=>({video,area:area(video)}))
        .filter(item=>item.area>0).sort((a,b)=>b.area-a.area);
    const video=videos[0]?.video;
    const source=video?.currentSrc||video?.getAttribute('src')||'';
    if(!video||!source||!Number.isFinite(video.duration)||video.duration<=0||video.readyState<2||video.ended)return {captured:false};
    window[${key}]={video,source,originalPaused:video.paused,originalRate:video.playbackRate,
        time:0,point:0,baselineReady:false};
    video.pause();
    return {captured:true,originalPaused:window[${key}].originalPaused,originalRate:video.playbackRate};
})()`;

// 媒体属性仅在前台读取。后台 WebView 可能冻结视频属性 getter，后台只读取 document.hidden。
const baseline = `(() => {
    const saved=window[${key}], video=saved?.video;
    if(!video||document.hidden||!video.isConnected||(video.currentSrc||video.getAttribute('src')||'')!==saved.source||!video.paused)return {ready:false};
    const time=video.currentTime, ranges=video.buffered;
    if(!Number.isFinite(time))return {ready:false};
    for(let index=0;index<ranges.length;index++){
        const start=ranges.start(index),end=ranges.end(index);
        if(start<=time+0.01&&end>time+0.05){
            saved.time=time; saved.point=Math.max(start,Math.min(end-0.025,time+0.025)); saved.baselineReady=true;
            return {ready:true,time,bufferPoint:saved.point,bufferStart:start,bufferEnd:end,bufferRangeCount:ranges.length};
        }
    }
    return {ready:false};
})()`;

const sample = `(() => {
    if(document.hidden)return {hidden:true};
    const saved=window[${key}],video=saved?.video;
    if(!saved||!video)return {saved:false,hidden:document.hidden};
    const ranges=video.buffered; let bufferPointRetained=false;
    for(let index=0;index<ranges.length;index++)if(ranges.start(index)<=saved.point&&ranges.end(index)>=saved.point)bufferPointRetained=true;
    return {saved:true,hidden:document.hidden,sameNode:video.isConnected,
        sameSource:(video.currentSrc||video.getAttribute('src')||'')===saved.source,
        paused:video.paused,ended:video.ended,time:Number.isFinite(video.currentTime)?video.currentTime:0,
        rate:video.playbackRate,timeDifference:Math.abs(video.currentTime-saved.time),
        baselineReady:!!saved.baselineReady,bufferPointRetained,bufferRangeCount:ranges.length};
})()`;
const visibility = '({hidden:!!document.hidden})';

async function waitFor(expression, predicate, timeout = 10000) {
    const deadline = Date.now() + timeout;
    let result;
    do {
        try { result = await client.evaluate(expression); if (predicate(result)) return result; }
        catch (_) { /* 不导出可能带网页地址的调试异常。 */ }
        await sleep(200);
    } while (Date.now() < deadline);
    return result;
}
async function reconnect() {
    if (client) { client.close(); client = null; }
    client = await connect(port, 'douyin.com');
}
async function reconnectForeground() {
    // 仅在 am start 返回前台后重连；整个后台阶段不附着调试器。
    for (let attempt=1; attempt<=3; attempt++) {
        try { await reconnect(); return; }
        catch (error) { if (attempt===3) throw error; await sleep(300); }
    }
}
function mediaChecks(prefix, state, hidden) {
    check(prefix+'SameSavedVideo', state?.saved && state.sameNode && state.sameSource && state.baselineReady);
    check(prefix+'Visibility', state.hidden === hidden);
    check(prefix+'StillPaused', state.paused && !state.ended);
    check(prefix+'TimeUnchanged', Number.isFinite(state.timeDifference) && state.timeDifference < 0.08);
    check(prefix+'BufferedPointRetained', state.bufferPointRetained && state.bufferRangeCount > 0);
}
function retainedDisk(prefix, before, after) {
    // 只把“未观察到整批清空”作为证据；系统 WebView 自身仍可能回收少量缓存。
    check(prefix+'ResourceCacheNotBulkCleared', after.cacheKiB > 0 && after.cacheKiB >= before.cacheKiB * 0.5);
    check(prefix+'TotalDataNotBulkCleared', after.totalKiB > 0 && after.totalKiB >= before.totalKiB * 0.5);
}

async function main() {
    fs.mkdirSync(out, {recursive:true});
    client = await connect(port, 'douyin.com');
    captureAttempted = true;
    report.observations.capture = await client.evaluate(capture);
    captured = !!report.observations.capture?.captured;
    check('ForegroundPlayableRecommendedVideoCaptured', captured);
    await sleep(400);
    report.observations.baseline = await waitFor(baseline, value => value?.ready);
    check('PausedBaselineHasBufferedPoint', report.observations.baseline?.ready);
    report.observations.before = await client.evaluate(sample);
    mediaChecks('Before', report.observations.before, false);
    report.observations.diskBefore = diskUsage();
    check('ExistingResourceCacheObserved', report.observations.diskBefore.cacheKiB > 0);

    client.close(); client = null;
    shell('input', 'keyevent', 'KEYCODE_HOME'); sentHome = true;
    const backgroundStarted = Date.now();
    await sleep(12_000);
    report.observations.backgroundElapsedMs = Date.now() - backgroundStarted;
    check('BackgroundWaitCrossesObservationGrace', report.observations.backgroundElapsedMs >= 12_000);
    // Android 会冻结后台调试服务；使用系统 Activity 状态确认，完全不连接后台 CDP。
    report.observations.nativeBackgroundConfirmed = nativeBackgroundConfirmed();
    check('NativeBackgroundConfirmed', report.observations.nativeBackgroundConfirmed);
    report.observations.diskBackground = diskUsage();
    retainedDisk('Background', report.observations.diskBefore, report.observations.diskBackground);

    shell('am', 'start', '-f', '0x10020000', '-n', 'com.zhou.douyinlite/.MainActivity');
    await reconnectForeground();
    // 首次回前台就复核同节点、同源、全程暂停位置和原缓冲点；没有修改进度来满足检查。
    report.observations.foreground = await waitFor(sample, value => value?.saved && !value.hidden);
    mediaChecks('Foreground', report.observations.foreground, false);
    report.observations.diskForeground = diskUsage();
    retainedDisk('Foreground', report.observations.diskBefore, report.observations.diskForeground);
    sentHome = false;
    report.passed = Object.values(report.checks).every(Boolean);
}

(async () => {
    try { await main(); }
    catch (error) { report.unexpectedError = !error.assertion; report.passed = false; }
    finally {
        try {
            if (sentHome) shell('am', 'start', '-f', '0x10020000', '-n', 'com.zhou.douyinlite/.MainActivity');
            if (!client) await reconnectForeground();
            // 若调试响应在 pause 执行后丢失，仍从页面内保存的引用恢复，不能依赖本机 captured 标志。
            const visible = await waitFor(visibility, value => value && !value.hidden, 10000);
            if (!visible || visible.hidden) throw new Error('Foreground unavailable for cleanup');
            const retained = captureAttempted ? await client.evaluate(sample) : null;
            if (retained?.saved) {
                const front = await waitFor(sample, value => value?.saved && !value.hidden, 10000);
                report.cleanupSucceeded = !!front?.saved && !front.hidden && !!(await client.evaluate(`(async()=>{
                    const saved=window[${key}],video=saved?.video;
                    if(document.hidden||!video?.isConnected||(video.currentSrc||video.getAttribute('src')||'')!==saved.source)return false;
                    video.playbackRate=saved.originalRate;
                    if(saved.originalPaused)video.pause();else {try{await video.play();}catch(_){return false;}}
                    const restored=video.paused===saved.originalPaused&&Math.abs(video.playbackRate-saved.originalRate)<0.01;
                    if(restored)delete window[${key}];
                    return restored;
                })()`));
            } else report.cleanupSucceeded = !captured;
        } catch (_) { report.cleanupSucceeded = false; }
        if (client) client.close();
        report.checks.originalPlaybackStateRestored = report.cleanupSucceeded;
        report.finishedAtEpochMs = Date.now();
        report.passed = report.passed && report.cleanupSucceeded && Object.values(report.checks).every(Boolean);
        if (!report.passed) process.exitCode = 1;
        fs.mkdirSync(out, {recursive:true});
        fs.writeFileSync(path.join(out, 'cache-retention-check.json'), JSON.stringify(report, null, 2)+'\n', {flag:'wx'});
        console.log(JSON.stringify({passed:report.passed, checks:Object.keys(report.checks).length,
            unexpectedError:report.unexpectedError, cleanupSucceeded:report.cleanupSucceeded}));
    }
})();
