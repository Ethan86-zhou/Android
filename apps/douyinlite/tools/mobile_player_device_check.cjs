'use strict';

// 手机实机验收：触摸只经原生输入，页面只返回数值和布尔，不保存账号、地址或内容。
// node tools/mobile_player_device_check.cjs PORT EVIDENCE_ROOT
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {connect} = require('./cdp.cjs');
const port = Number(process.argv[2]);
const root = process.argv[3] && path.resolve(process.argv[3]);
if (!Number.isInteger(port) || port < 1 || port > 65535 || !root || !process.env.ANDROID_HOME) process.exit(2);
const started = Date.now();
const out = path.join(root, `mobile-player-${started}-${process.pid}`);
const dumpPath = `/sdcard/lightswipe-player-${started}-${process.pid}.xml`;
const adb = path.join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const shell = (...args) => execFileSync(adb, ['shell', ...args.map(String)],
    {encoding: 'utf8', windowsHide: true, timeout: 15000, stdio: ['ignore', 'pipe', 'pipe']});
const report = {startedAtEpochMs: started, passed: false, checks: {}, observations: {},
    unexpectedError: false, cleanupSucceeded: true};
let client, touchDown = false, sentHome = false, touchX = 0, touchY = 0, nativeDensity = 3;

function nodes() {
    let xml;
    try { shell('uiautomator', 'dump', dumpPath); xml = shell('cat', dumpPath); }
    finally { shell('rm', '-f', dumpPath); }
    return [...xml.matchAll(/<node\b[^>]*>/g)].map(m => Object.fromEntries(
        [...m[0].matchAll(/([\w:-]+)="([^"]*)"/g)].map(a => [a[1], a[2]])));
}
function rect(node) {
    const m = node?.bounds?.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/);
    if (!m) return null;
    const [, left, top, right, bottom] = m.map(Number);
    return right > left && bottom > top ? {left, top, width: right-left, height: bottom-top} : null;
}
function control(description, list = nodes()) {
    return list.find(n => n.package === 'com.zhou.douyinlite' && n['content-desc'] === description
        && n.enabled === 'true' && rect(n)
        && (description !== '视频进度' || n.class === 'android.widget.SeekBar'));
}
function tap(description) {
    const r = rect(control(description));
    if (!r) throw new Error('Native control unavailable');
    shell('input', 'touchscreen', 'tap', Math.round(r.left+r.width/2), Math.round(r.top+r.height/2));
}
function check(name, result) {
    report.checks[name] = !!result;
    if (!result) { const e = new Error('Device check failed'); e.assertion = true; throw e; }
}
const expression = `(() => {
    const m = window.LightSwipe?.status(), u = window.LightSwipeUi?.status();
    const number = v => Number.isFinite(v) ? v : 0;
    const visible = e => {
        if (!e?.isConnected) return false;
        const r=e.getBoundingClientRect();
        if (r.width<=0||r.height<=0||r.bottom<=0||r.right<=0||r.top>=innerHeight||r.left>=innerWidth) return false;
        for(let p=e;p?.nodeType===1;p=p.parentElement){const s=getComputedStyle(p);
            if(p.hidden||s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||+s.opacity===0)return false;}
        return true;
    };
    return {available:!!m?.available, playing:!!m?.playing, suspended:!!m?.paused,
        seekable:!!m?.seekable, mediaId:number(m?.mediaId), time:number(m?.currentTime), duration:number(m?.duration),
        rate:number(m?.rate), boosted:!!m?.boosted, clean:!!u?.clean, playerActive:!!u?.playerActive,
        recommended:u?.channel==='recommend', featured:u?.channel==='featured',
        desktopControlsVisible:[...document.querySelectorAll('#slidelist.recommend-slidelist .xgplayer xg-controls.xgplayer-controls,.modal-video-container .xgplayer xg-controls.xgplayer-controls')].some(visible),
        modalVisible:[...document.querySelectorAll('.modal-video-container')].some(visible),
        gridReferencePresent:!!window.__lightSwipeMobileGrid,
        sameGridAddress:!!window.__lightSwipeMobileGrid && location.href===window.__lightSwipeMobileGrid.address,
        leftGridAddress:!!window.__lightSwipeMobileGrid && location.href!==window.__lightSwipeMobileGrid.address,
        cardsVisible:[...document.querySelectorAll('.jingxuanVideoCard')].filter(visible).length};
})()`;
const sample = () => client.evaluate(expression);
async function until(predicate, timeout = 20000) {
    const end = Date.now()+timeout;
    let value;
    do {
        try { value = await sample(); if (predicate(value)) return value; }
        catch (_) { /* 导航时上下文可能暂时消失；不输出网页异常内容。 */ }
        await sleep(250);
    } while(Date.now()<end);
    return value;
}
async function paused(prefix = 'native') {
    const before = await sample();
    check(prefix+'PauseVideoAvailable', before.available && before.mediaId>0 && !before.suspended);
    if (before.playing) tap('暂停视频');
    const after = await until(v => v.mediaId===before.mediaId && v.available && !v.playing && !v.suspended);
    check(prefix+'PauseWorks', after?.mediaId===before.mediaId && after.available && !after.playing && !after.suspended);
    return after;
}
async function drag(fraction, prefix) {
    const before = await paused(prefix);
    check(prefix+'SeekableMediaReady', before.seekable && before.duration>0 && before.mediaId>0);
    // 初始位置接近目标时换一个目标，避免未发生跳转也通过。
    if (Math.abs(before.time/before.duration-fraction)<0.08) fraction += fraction<0.6 ? 0.15 : -0.15;
    const r = rect(control('视频进度'));
    check(prefix+'SeekBarAvailable', !!r);
    // SeekBar 有左右触摸留白，允许少量坐标差异；实际时间必须跟手改变。
    const y = Math.round(r.top+r.height/2);
    const startX = Math.round(r.left+r.width*0.12);
    // 与当前原生控件 10dp/12dp 留白及 4dp thumbOffset 对齐，测量目标是轨道进度。
    const endX = Math.round(r.left+10*nativeDensity+(r.width-22*nativeDensity)*fraction);
    shell('input', 'touchscreen', 'motionevent', 'DOWN', startX, y); touchDown = true; touchX=startX; touchY=y;
    shell('input', 'touchscreen', 'motionevent', 'MOVE', endX, y); touchX=endX;
    check(prefix+'DragTimeVisible', !!control('拖动进度时间'));
    shell('input', 'touchscreen', 'motionevent', 'UP', endX, y); touchDown = false;
    const after = await until(v => v.mediaId===before.mediaId
        && v.seekable && v.duration>0 && Math.abs(v.time/v.duration-fraction)<0.02);
    report.observations[prefix] = {before, after, targetFraction:fraction};
    check(prefix+'SeekReachesFingerPosition', after?.mediaId===before.mediaId && after.seekable && after.duration>0
        && Math.abs(after.time/after.duration-fraction)<0.02);
    check(prefix+'ActualPlaybackTimeChanged', Math.abs(after.time-before.time)>before.duration*0.05);
    check(prefix+'SeekKeepsPausedAndRate', !after.playing && !after.suspended && Math.abs(after.rate-before.rate)<0.03);
    check(prefix+'TimeLabelDismissed', !control('拖动进度时间'));
}
async function main() {
    fs.mkdirSync(out, {recursive:true});
    client = await connect(port, 'douyin.com');
    tap('推荐');
    const ready = await until(v => v.clean && v.recommended && v.playerActive && v.seekable && v.duration>8);
    report.observations.initial = ready;
    check('recommendedPlayerReady', ready?.clean && ready.recommended && ready.playerActive && ready.seekable
        && ready.available && ready.duration>8 && ready.mediaId>0);
    check('desktopControlsHidden', !ready.desktopControlsVisible);
    const initialNodes = nodes(), seek = rect(control('视频进度', initialNodes));
    const play = control(ready.playing ? '暂停视频' : '播放视频', initialNodes);
    check('nativePlaybackControlPresent', !!play);
    check('nativeSeekControlPresent', !!seek);
    const densityText = shell('wm', 'density');
    const densities = [...densityText.matchAll(/(?:Physical|Override) density:\s*(\d+)/g)];
    const density = densities.length ? Number(densities.at(-1)[1])/160 : 3;
    nativeDensity = density;
    check('seekTouchHeightAtLeast44Dp', seek && seek.height/density>=43.5);
    const stopped = await paused(); await sleep(600);
    const pauseSample = await sample();
    check('pauseStopsTime', pauseSample.mediaId===stopped.mediaId && !pauseSample.playing
        && !pauseSample.suspended && Math.abs(pauseSample.time-stopped.time)<0.15);
    tap('播放视频');
    const playing = await until(v => v.mediaId===stopped.mediaId && v.playing && v.time>stopped.time+0.1);
    check('nativePlayAdvancesTime', playing?.mediaId===stopped.mediaId && playing.playing && playing.time>stopped.time+0.1);
    await drag(0.55, 'forward');
    await drag(0.25, 'backward');

    // 拖动尚未松手就切后台，不能把预览进度提交给视频。
    const beforeCancel = await paused(), r = rect(control('视频进度'));
    const x = Math.round(r.left+r.width*0.75), y = Math.round(r.top+r.height/2);
    shell('input','touchscreen','motionevent','DOWN',x,y); touchDown=true; touchX=x; touchY=y;
    check('backgroundDragPreviewVisible', !!control('拖动进度时间'));
    shell('input','keyevent','KEYCODE_HOME'); sentHome=true;
    await sleep(500);
    shell('input','touchscreen','motionevent','UP',x,y); touchDown=false;
    const hidden = await until(v => v.suspended);
    check('backgroundSuspendsMedia', !!hidden?.suspended);
    check('backgroundDragDoesNotSeek', hidden.mediaId===beforeCancel.mediaId && Math.abs(hidden.time-beforeCancel.time)<0.5);
    shell('am','start','-f','0x10020000','-n','com.zhou.douyinlite/.MainActivity'); sentHome=false;
    const resumed = await until(v => !v.suspended && v.playerActive && v.mediaId===beforeCancel.mediaId);
    check('resumePreservesCancelledDrag', resumed?.mediaId===beforeCancel.mediaId && !resumed.suspended
        && resumed.playerActive && !resumed.playing && Math.abs(resumed.time-beforeCancel.time)<0.5);
    check('cancelledDragTimeLabelDismissed', !control('拖动进度时间'));

    // 在新的推荐视频上再拖一次，确认原生进度绑定新 mediaId 和新 duration。
    tap('播放视频');
    const beforeSwipe = await until(v=>v.playing && v.mediaId===beforeCancel.mediaId);
    check('playbackBeforeSwitch', !!beforeSwipe?.playing && beforeSwipe.mediaId===beforeCancel.mediaId);
    const swipeWeb = rect(nodes().find(n=>n.class==='android.webkit.WebView'&&n.package==='com.zhou.douyinlite'&&rect(n)));
    check('videoViewportForSwipeAvailable', !!swipeWeb);
    shell('input','touchscreen','swipe',Math.round(swipeWeb.left+swipeWeb.width*.5),Math.round(swipeWeb.top+swipeWeb.height*.75),
        Math.round(swipeWeb.left+swipeWeb.width*.5),Math.round(swipeWeb.top+swipeWeb.height*.3),400);
    const switched = await until(v=>v.recommended && v.playerActive && v.available && v.playing && v.seekable
        && v.duration>0 && v.mediaId>0 && v.mediaId!==beforeSwipe.mediaId);
    report.observations.afterVideoSwitch = switched;
    check('swipeBindsNewVideo', switched?.recommended && switched.playerActive && switched.available
        && switched.playing && switched.seekable && switched.duration>0 && switched.mediaId>0 && switched.mediaId!==beforeSwipe.mediaId);
    check('newVideoHasNativeProgress', !!control('视频进度') && !switched.desktopControlsVisible);
    await drag(0.35,'newVideo');

    tap('精选');
    const grid = await until(v => v.featured && !v.modalVisible && v.cardsVisible>0);
    check('featuredGridHasNoPlayerBar', grid?.featured && !grid.modalVisible && grid.cardsVisible>0
        && !grid.playerActive && !control('视频进度'));
    const card = await client.evaluate(`(() => {
        for(const e of document.querySelectorAll('.jingxuanVideoCard')){const r=e.getBoundingClientRect();
            let hidden=false;for(let p=e;p?.nodeType===1;p=p.parentElement){const s=getComputedStyle(p);
                if(p.hidden||s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||+s.opacity===0){hidden=true;break;}}
            if(!hidden&&r.width>100&&r.height>80&&r.top>=0&&r.top<innerHeight/2){
                window.__lightSwipeMobileGrid={address:location.href};
                return {x:r.x+r.width/2,y:r.y+r.height*.25,width:innerWidth,height:innerHeight};}}
        return null;
    })()`);
    check('featuredCardAvailable', !!card);
    const web = rect(nodes().find(n=>n.class==='android.webkit.WebView'&&n.package==='com.zhou.douyinlite'));
    check('featuredVideoViewportAvailable', !!web && card.width>0 && card.height>0);
    shell('input','touchscreen','tap',Math.round(web.left+card.x*web.width/card.width),
        Math.round(web.top+card.y*web.height/card.height));
    const modal = await until(v => v.featured && v.leftGridAddress && v.modalVisible && v.playerActive && v.available && v.seekable && v.duration>0);
    check('featuredModalHasMobileControls', modal?.featured && modal.gridReferencePresent && modal.leftGridAddress
        && modal.modalVisible && modal.playerActive && modal.available && modal.seekable && modal.duration>0 && !!control('视频进度'));
    check('featuredDesktopControlsHidden', !modal.desktopControlsVisible);
    await drag(0.4, 'featured');
    shell('input','keyevent','KEYCODE_BACK');
    const returned = await until(v=>v.featured&&v.sameGridAddress&&!v.modalVisible&&v.cardsVisible>0&&!v.playerActive);
    check('returnHidesMobileBar', returned?.featured && returned.sameGridAddress && !returned.modalVisible
        && returned.cardsVisible>0 && !returned.playerActive && !control('视频进度'));
    report.passed = Object.values(report.checks).every(Boolean);
}
(async()=>{
    try { await main(); }
    catch(e) { report.unexpectedError=!e.assertion; process.exitCode=1; }
    finally {
        try {
            if(touchDown){
                if(!sentHome){shell('input','keyevent','KEYCODE_HOME');sentHome=true;}
                shell('input','touchscreen','motionevent','UP',touchX,touchY);touchDown=false;
            }
            if(sentHome){shell('am','start','-f','0x10020000','-n','com.zhou.douyinlite/.MainActivity');sentHome=false;}
            if(client){
                tap('推荐');
                const restored=await until(v=>v.recommended&&v.playerActive&&v.available&&!v.suspended);
                report.cleanupSucceeded=!!restored?.recommended&&restored.playerActive&&restored.available&&!restored.suspended;
                await client.evaluate('delete window.__lightSwipeMobileGrid; true');
            }
        } catch(_) {report.cleanupSucceeded=false;}
        if(client)client.close();
        report.finishedAtEpochMs=Date.now(); report.passed=report.passed&&report.cleanupSucceeded;
        fs.mkdirSync(out,{recursive:true});
        fs.writeFileSync(path.join(out,'mobile-player-check.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
        console.log(JSON.stringify({passed:report.passed,checks:Object.keys(report.checks).length,
            unexpectedError:report.unexpectedError,cleanupSucceeded:report.cleanupSucceeded}));
        if(!report.passed)process.exitCode=1;
    }
})();
