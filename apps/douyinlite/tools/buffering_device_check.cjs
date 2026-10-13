'use strict';

// 仅对当前 WebView 做短时离线或弱网模拟，不改手机全局网络、不清缓存、不刷新页面。
// node tools/buffering_device_check.cjs PORT EVIDENCE_ROOT [SERIAL] [offline|slow]
// 当前需停在推荐视频，时长 >= 30 秒且尾部存在可拖到的未缓冲片段。
// 官网自行预取的分片可能立即补齐 buffered；无法制造真实等待时明确 blocked。
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {connect} = require('./cdp.cjs');
const port = Number(process.argv[2]);
const evidenceRoot = process.argv[3] && path.resolve(process.argv[3]);
const serial = process.argv[4] || process.env.ANDROID_SERIAL;
const mode = process.argv[5] || 'offline';
const slowNetwork = mode === 'slow';
if (!Number.isInteger(port) || port < 1 || port > 65535 || !evidenceRoot || !process.env.ANDROID_HOME
        || !['offline','slow'].includes(mode) || process.argv.length > 6) {
    console.error('Usage: node buffering_device_check.cjs PORT EVIDENCE_ROOT [SERIAL] [offline|slow]; ANDROID_HOME is required.');
    process.exit(2);
}
const started = Date.now();
const out = path.join(evidenceRoot, `buffering-${started}-${process.pid}`);
const key = `__lightSwipeBufferAcceptance_${started}_${process.pid}`;
const dumpPath = `/sdcard/lightswipe-buffering-${started}-${process.pid}.xml`;
const adb = path.join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe');
const report = {schemaVersion:1, startedAtEpochMs:started, mode, passed:false, blocked:false,
    blockedReasons:{}, checks:{}, observations:{}, unexpectedError:false, cleanupSucceeded:true};
let client, networkTouched = false, mediaTouched = false, cancelled = false;
process.on('SIGINT', () => { cancelled = true; });
process.on('SIGTERM', () => { cancelled = true; });
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const shell = (...args) => execFileSync(adb, [...(serial ? ['-s', serial] : []), 'shell', ...args.map(String)],
    {encoding:'utf8', windowsHide:true, timeout:15000, stdio:['ignore','pipe','pipe']});
function nodes() {
    let xml;
    try { shell('uiautomator','dump',dumpPath); xml = shell('cat',dumpPath); }
    finally { shell('rm','-f',dumpPath); }
    return [...xml.matchAll(/<node\b[^>]*>/g)].map(m => Object.fromEntries(
        [...m[0].matchAll(/([\w:-]+)="([^"]*)"/g)].map(a => [a[1],a[2]])));
}
function rect(node) {
    const m = node?.bounds?.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/);
    if (!m) return null;
    const [,left,top,right,bottom] = m.map(Number);
    return right>left && bottom>top ? {left,top,width:right-left,height:bottom-top} : null;
}
function control(description) {
    return nodes().find(n => n.package==='com.zhou.douyinlite' && n['content-desc']===description && rect(n));
}
function tap(description) {
    const r = rect(control(description));
    check('native'+(description==='暂停视频'?'Pause':'Play')+'ControlAvailable', !!r);
    shell('input','touchscreen','tap',Math.round(r.left+r.width/2),Math.round(r.top+r.height/2));
}
function check(name, passed) {
    report.checks[name] = !!passed;
    if (!passed) { const e = new Error('Device assertion failed'); e.assertion=true; throw e; }
}
function blocked(reason) {
    report.blocked=true; report.blockedReasons[reason]=true;
    const e = new Error('Device prerequisites unavailable'); e.blocked=true; throw e;
}
function interrupted() { if(cancelled) blocked('interrupted'); }

// 源地址、DOM 节点和原始账号界面始终留在网页进程；只导出状态布尔、时间和数字编号。
const initialize = `(() => {
    const m=window.LightSwipe?.status?.(), u=window.LightSwipeUi?.status?.();
    if(!m||!u||typeof m.buffering!=='boolean'||typeof m.playbackRequested!=='boolean')return {apiReady:false};
    const prerequisites={apiReady:true,recommended:u.channel==='recommend',clean:!!u.clean,
        playerActive:!!u.playerActive,available:!!m.available,seekable:!!m.seekable,
        foreground:!document.hidden&&!m.paused,online:!!navigator.onLine,
        duration:m.duration,mediaId:m.mediaId,boosted:!!m.boostRequested};
    if(!prerequisites.recommended||!prerequisites.clean||!prerequisites.playerActive||!prerequisites.available||
        !prerequisites.seekable||!prerequisites.foreground||!prerequisites.online||prerequisites.boosted||m.duration<30)return prerequisites;
    const area=video=>{
        if(!video.isConnected)return 0;const r=video.getBoundingClientRect();
        let left=Math.max(0,r.left),top=Math.max(0,r.top),right=Math.min(innerWidth,r.right),bottom=Math.min(innerHeight,r.bottom);
        for(let e=video;e?.nodeType===1;e=e.parentElement){const s=getComputedStyle(e);
            if(e.hidden||s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||+s.opacity===0||s.contentVisibility==='hidden')return 0;
            if(e!==video){const p=e.getBoundingClientRect();if(s.overflowX!=='visible'){left=Math.max(left,p.left);right=Math.min(right,p.right);}
                if(s.overflowY!=='visible'){top=Math.max(top,p.top);bottom=Math.min(bottom,p.bottom);}}}
        return Math.max(0,right-left)*Math.max(0,bottom-top);
    };
    const playing=v=>!!v&&!v.paused&&!v.ended&&v.readyState>=2;
    let video=null,bestArea=0;for(const v of document.querySelectorAll('video')){const a=area(v);
        if(a>bestArea||(a>0&&a===bestArea&&playing(v)&&!playing(video))){video=v;bestArea=a;}}
    if(!video||video.error||Math.abs(video.currentTime-m.currentTime)>0.2||Math.abs(video.duration-m.duration)>0.01)return prerequisites;
    const ranges=name=>{const r=video[name],out=[];for(let i=0;i<r.length;i++)out.push({start:r.start(i),end:r.end(i)});return out;};
    const buffered=ranges('buffered'),seekable=ranges('seekable');
    let target=null,gapSize=0;
    for(const seek of seekable){let gaps=[{start:Math.max(m.duration*.55,seek.start),end:Math.min(m.duration-8,seek.end)}];
        for(const cached of buffered){const next=[];for(const gap of gaps){
            if(cached.end<=gap.start||cached.start>=gap.end)next.push(gap);
            else{if(cached.start>gap.start)next.push({start:gap.start,end:cached.start});
                if(cached.end<gap.end)next.push({start:cached.end,end:gap.end});}}gaps=next;}
        for(const gap of gaps){const size=gap.end-gap.start;if(size>=4&&size>gapSize){gapSize=size;target=(gap.start+gap.end)/2;}}}
    if(target===null)return {...prerequisites,eligibleTail:false};
    window[${JSON.stringify(key)}]={video,currentSrc:video.currentSrc,src:video.src,
        time:video.currentTime,paused:video.paused,rate:video.playbackRate,mediaId:m.mediaId,target};
    return {...prerequisites,eligibleTail:true,target,targetFraction:target/m.duration,
        originalPaused:video.paused,originalTime:video.currentTime,rate:video.playbackRate,gapSize};
})()`;
const expression = `(() => {
    const m=window.LightSwipe?.status?.(), saved=window[${JSON.stringify(key)}], v=saved?.video;
    const number=x=>Number.isFinite(x)?x:0;
    return {apiReady:!!m,available:!!m?.available,mediaId:number(m?.mediaId),time:number(m?.currentTime),
        duration:number(m?.duration),rate:number(m?.rate),seekable:!!m?.seekable,
        buffering:!!m?.buffering,playbackRequested:!!m?.playbackRequested,mediaPaused:!!m?.mediaPaused,
        mediaError:!!m?.mediaError,readyState:number(m?.readyState),bufferedAhead:number(m?.bufferedAhead),
        bufferedFraction:number(m?.bufferedFraction),suspended:!!m?.paused,online:!!navigator.onLine,
        sameMedia:!!saved&&!!v?.isConnected&&m?.mediaId===saved.mediaId&&v.currentSrc===saved.currentSrc&&v.src===saved.src,
        sourceStillPresent:!!saved&&!!v?.isConnected&&v.currentSrc===saved.currentSrc&&v.src===saved.src,
        atOriginalPosition:!!saved&&Math.abs(v.currentTime-saved.time)<0.8,
        originalPauseRestored:!!saved&&v.paused===saved.paused,
        originalRateRestored:!!saved&&Math.abs(v.playbackRate-saved.rate)<0.001};
})()`;
const sample = () => client.evaluate(expression);
async function until(predicate, timeout=20000, cleanup=false) {
    const deadline=Date.now()+timeout;let state;
    do {
        if(!cleanup)interrupted();
        state=await sample();if(predicate(state))return state;
        await sleep(250);
    }while(Date.now()<deadline);
    return state;
}
async function network(impaired) {
    const conditions = impaired
        ? (slowNetwork ? {offline:false,latency:1500,downloadThroughput:8192,uploadThroughput:65536}
            : {offline:true,latency:0,downloadThroughput:0,uploadThroughput:0})
        : {offline:false,latency:0,downloadThroughput:-1,uploadThroughput:-1};
    await client.call('Network.emulateNetworkConditions', conditions);
    if(impaired)report.observations.appliedNetworkConditions=conditions;
}

async function main() {
    client=await connect(port,'douyin.com');
    const initial=await client.evaluate(initialize);report.observations.initial=initial;
    if(!initial.apiReady)blocked('bufferApiUnavailable');
    if(!initial.recommended||!initial.clean||!initial.playerActive||!initial.foreground||!initial.online||initial.boosted)
        blocked('currentPlayerNotEligible');
    if(!initial.available||!initial.seekable||initial.duration<30)blocked('needSeekableVideoAtLeast30Seconds');
    if(!initial.eligibleTail)blocked('noUnbufferedSeekableTail');
    check('eligibleCurrentVideo',true);

    // 先保存状态再暂停，给网络模拟和 seek 一个稳定起点；到清理结束都绑定同一媒体。
    if(!initial.originalPaused){mediaTouched=true;tap('暂停视频');}
    const stopped=await until(s=>s.sameMedia&&s.mediaPaused&&!s.suspended);
    check('sameVideoPausedBeforeNetworkTest',stopped.sameMedia&&stopped.mediaPaused&&!stopped.suspended);
    await client.call('Network.enable');networkTouched=true;
    try{await network(true);}catch(_){blocked('webViewNetworkEmulationUnavailable');}
    const impaired=await until(s=>s.online===slowNetwork,5000);
    if(impaired.online!==slowNetwork)blocked(slowNetwork?'webViewUnexpectedlyOfflineDuringSlowNetwork':'webViewOfflineStateNotApplied');
    check(slowNetwork?'webViewSlowNetworkAppliedWhileOnline':'webViewOfflineApplied',true);
    interrupted();
    mediaTouched=true;
    const seek=await client.evaluate(`(() => {
        const saved=window[${JSON.stringify(key)}],m=window.LightSwipe.status();
        if(!saved||m.mediaId!==saved.mediaId)return {sameMedia:false};
        const v=saved.video;for(let i=0;i<v.buffered.length;i++){
            if(v.buffered.start(i)<=saved.target&&v.buffered.end(i)>saved.target)return {sameMedia:true,targetAlreadyBuffered:true};}
        const result=window.LightSwipe.seek(saved.target/v.duration,saved.mediaId);
        return {sameMedia:result.mediaId===saved.mediaId,accepted:!!result.accepted,
            targetAlreadyBuffered:false,reachedTarget:Math.abs(result.currentTime-saved.target)<0.25};
    })()`);
    report.observations.seek=seek;
    if(seek.targetAlreadyBuffered)blocked(slowNetwork?'targetBufferedBeforeSlowNetworkSeek':'targetBufferedBeforeOfflineSeek');
    check(slowNetwork?'slowNetworkSeekReachesUnbufferedTarget':'offlineSeekReachesUnbufferedTarget',seek.sameMedia&&seek.accepted&&seek.reachedTarget);
    tap('播放视频');
    const waiting=await until(s=>!s.sameMedia||s.mediaError||(s.buffering&&s.playbackRequested),12000);
    report.observations.waiting=waiting;
    if(!waiting.sameMedia)blocked(slowNetwork?'websiteChangedVideoDuringSlowNetworkTest':'websiteChangedVideoDuringOfflineTest');
    if(waiting.mediaError)blocked('websiteMediaErrorInsteadOfWaiting');
    if(!waiting.buffering||!waiting.playbackRequested)blocked('websitePrefetchPreventedRealBuffering');
    check(slowNetwork?'realMediaWaitsForDataInSlowNetwork':'realMediaWaitsForData',
        waiting.readyState<3&&!waiting.mediaPaused&&waiting.online===slowNetwork);
    await sleep(1300);interrupted();
    const beforeHint=await sample();
    if(!beforeHint.sameMedia||!beforeHint.buffering||beforeHint.mediaError)blocked('bufferingDidNotPersistLongEnough');
    check('nativeBufferingHintVisible',!!control('视频缓冲状态'));
    const held=await sample();report.observations.waitingWithHint=held;
    check(slowNetwork?'hintReflectsActualWaitingInSlowNetwork':'hintReflectsActualWaiting',
        held.sameMedia&&held.buffering&&held.playbackRequested&&!held.mediaError&&held.online===slowNetwork);

    await network(false);
    const recovered=await until(s=>s.sameMedia&&s.online&&!s.buffering&&!s.mediaError&&s.playbackRequested&&s.time>held.time+0.4,30000);
    report.observations.recovered=recovered;
    check('sameVideoRecoversAndTimeAdvances',recovered.sameMedia&&recovered.online&&!recovered.buffering&&!recovered.mediaError
        &&recovered.playbackRequested&&recovered.time>held.time+0.4);
    await sleep(900);
    check('bufferingHintDismissesAfterRecovery',!control('视频缓冲状态'));
    tap('暂停视频');
    const paused=await until(s=>s.sameMedia&&s.mediaPaused&&!s.playbackRequested&&!s.buffering);
    check('manualPauseIsNotBuffering',paused.sameMedia&&paused.mediaPaused&&!paused.playbackRequested&&!paused.buffering);
    await sleep(900);
    check('manualPauseDoesNotShowBufferingHint',!control('视频缓冲状态'));
    const stable=await sample();
    check('manualPauseStopsTime',stable.sameMedia&&stable.mediaPaused&&Math.abs(stable.time-paused.time)<0.2);
    report.passed=Object.values(report.checks).every(Boolean);
}

async function cleanup() {
    if(!client)return;
    // 优先恢复调试目标的在线、无延时及无限速；即使模拟命令超时，也必须尝试恢复。
    if(networkTouched){
        let restored=false;
        for(let attempt=0;attempt<2&&!restored;attempt++){
            try{await network(false);restored=true;}catch(_){await sleep(250);}
        }
        report.cleanupSucceeded=restored&&report.cleanupSucceeded;
        report.observations.networkCleanupConfirmed=restored;
    }
    if(mediaTouched){
        try{
            const restored=await client.evaluate(`(() => {
                const saved=window[${JSON.stringify(key)}];if(!saved)return false;
                const v=saved.video;if(!v.isConnected||v.currentSrc!==saved.currentSrc||v.src!==saved.src)return false;
                v.pause();v.playbackRate=saved.rate;v.currentTime=saved.time;
                if(!saved.paused){const promise=v.play();if(promise?.catch)promise.catch(()=>{});}
                return true;
            })()`);
            const state=await until(s=>s.online&&s.sourceStillPresent&&s.atOriginalPosition
                &&s.originalPauseRestored&&s.originalRateRestored,15000,true);
            report.observations.restored=state;
            report.cleanupSucceeded=report.cleanupSucceeded&&restored&&state.online&&state.sourceStillPresent
                &&state.atOriginalPosition&&state.originalPauseRestored&&state.originalRateRestored;
        }catch(_){report.cleanupSucceeded=false;}
    }
    try{await client.evaluate(`delete window[${JSON.stringify(key)}]; true`);}catch(_){report.cleanupSucceeded=false;}
    if(networkTouched){try{await client.call('Network.disable');}catch(_){report.cleanupSucceeded=false;}}
}
(async()=>{
    try{await main();}
    catch(e){report.unexpectedError=!e.assertion&&!e.blocked;}
    finally{
        try{await cleanup();}catch(_){report.cleanupSucceeded=false;}
        if(client)client.close();
        report.finishedAtEpochMs=Date.now();report.passed=report.passed&&report.cleanupSucceeded&&!report.blocked&&!report.unexpectedError;
        fs.mkdirSync(out,{recursive:true});
        fs.writeFileSync(path.join(out,'buffering-check.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
        console.log(JSON.stringify({mode,passed:report.passed,blocked:report.blocked,blockedReasons:report.blockedReasons,
            checks:Object.keys(report.checks).length,unexpectedError:report.unexpectedError,cleanupSucceeded:report.cleanupSucceeded}));
        if(!report.passed)process.exitCode=report.blocked&&report.cleanupSucceeded?3:1;
    }
})();
