'use strict';

// 快速频道切换、刷新与前后台实机验收；输入全部来自 Android 原生 input。
// node tools/navigation_device_check.cjs PORT EVIDENCE_ROOT
// UI XML 只在内存里识别本 App 固定控件；报告只保存数字和布尔，不保存账号或网址。
const fs=require('node:fs');
const path=require('node:path');
const {execFileSync}=require('node:child_process');
const {connect}=require('./cdp.cjs');

const port=Number(process.argv[2]);
const root=process.argv[3]&&path.resolve(process.argv[3]);
if(!Number.isInteger(port)||port<1||port>65535||!root||!process.env.ANDROID_HOME){
    console.error('Usage: node navigation_device_check.cjs PORT EVIDENCE_ROOT; ANDROID_HOME required.');
    process.exit(2);
}

const started=Date.now();
const runDirectory=path.join(root,`navigation-${started}-${process.pid}`);
const dumpPath=`/sdcard/lightswipe-navigation-${started}-${process.pid}.xml`;
const marker=`__lightSwipeNavigation${started}_${process.pid}`;
const pauseMarker=marker+'TemporaryPause';
const adb=path.join(process.env.ANDROID_HOME,'platform-tools',process.platform==='win32'?'adb.exe':'adb');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const report={schemaVersion:1,startedAtEpochMs:started,passed:false,checks:{},observations:{bursts:[],refresh:[],lifecycle:[],states:{},nativeSnapshots:[]},
    unexpectedError:false,cleanupSucceeded:false};
let client,menuOpen=false,sentHome=false,dumpMayExist=false;
let toolbar,initialHeight=0;
let temporaryPauseMayExist=false;

function shell(...args){
    return execFileSync(adb,['shell',...args.map(String)],{encoding:'utf8',windowsHide:true,timeout:15000,
        stdio:['ignore','pipe','pipe']});
}

const pauseExpression=`(() => {
    const key=${JSON.stringify(pauseMarker)};
    if(Object.prototype.hasOwnProperty.call(window,key))return {created:false,temporaryPauseUsed:false,pausedCount:0};
    const entries=[];window[key]=entries;
    if(!document.hidden)for(const video of document.querySelectorAll('video')){
        if(video.paused||video.ended)continue;
        // 源字符串与节点引用只留在本网页，绝不返回给验收进程或报告。
        entries.push({video,currentSrc:video.currentSrc,src:video.src});
        video.pause();
    }
    return {created:true,temporaryPauseUsed:entries.length>0,pausedCount:entries.length};
})()`;

const resumeExpression=`(async () => {
    const key=${JSON.stringify(pauseMarker)},entries=window[key];
    if(!entries)return {completed:true,resumedCount:0,skippedCount:0,failedCount:0};
    let suspended=false,statusReadable=false;
    try{const status=window.LightSwipe?.status?.();statusReadable=!!status;suspended=!!status?.paused;}catch(_){}
    let resumedCount=0,skippedCount=0,failedCount=0;
    for(const entry of entries){
        const video=entry.video;
        if(document.hidden||suspended||!statusReadable||!video.isConnected||video.ended
                ||video.currentSrc!==entry.currentSrc||video.src!==entry.src){skippedCount++;continue;}
        if(!video.paused)continue;
        let timer;
        try{
            const resumed=await Promise.race([Promise.resolve(video.play()).then(()=>true,()=>false),
                new Promise(resolve=>{timer=setTimeout(()=>resolve(false),2000);})]);
            if(resumed)resumedCount++;else failedCount++;
        }catch(_){failedCount++;}finally{clearTimeout(timer);}
    }
    delete window[key];
    return {completed:failedCount===0,resumedCount,skippedCount,failedCount};
})()`;

async function restoreTemporaryPause(){
    const result=await client.evaluate(resumeExpression);
    temporaryPauseMayExist=false;
    return result;
}

async function nativeNodes(){
    let xml;
    const observation={temporaryPauseUsed:false,pausedCount:0,snapshotCompleted:false,restoreCompleted:false};
    report.observations.nativeSnapshots.push(observation);
    try{
        temporaryPauseMayExist=true;
        const paused=await client.evaluate(pauseExpression);
        observation.temporaryPauseUsed=!!paused?.temporaryPauseUsed;
        observation.pausedCount=paused?.pausedCount||0;
        if(!paused?.created)throw new Error('Previous temporary video pause remains active');
        // 允许原生700ms状态轮询看到已暂停的画面，再等待uiautomator空闲。
        if(paused.temporaryPauseUsed)await sleep(850);
        dumpMayExist=true;shell('uiautomator','dump',dumpPath);xml=shell('cat',dumpPath);
        observation.snapshotCompleted=true;
    }finally{
        try{shell('rm','-f',dumpPath);dumpMayExist=false;}
        finally{
            const resumed=await restoreTemporaryPause();
            observation.restoreCompleted=resumed?.completed===true;
            observation.resumedCount=resumed?.resumedCount||0;
            observation.skippedCount=resumed?.skippedCount||0;
            observation.resumeFailedCount=resumed?.failedCount||0;
            if(!observation.restoreCompleted)throw new Error('Temporary video pause restoration failed');
        }
    }
    return [...xml.matchAll(/<node\b[^>]*>/g)].map(match=>Object.fromEntries(
        [...match[0].matchAll(/([\w:-]+)="([^"]*)"/g)].map(attribute=>[attribute[1],attribute[2]])));
}

function center(node){
    const match=node?.bounds?.match(/^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/);
    if(!match)return null;
    const [,left,top,right,bottom]=match.map(Number);
    return left>=0&&top>=0&&right>left&&bottom>top?{x:Math.round((left+right)/2),y:Math.round((top+bottom)/2)}:null;
}

function tab(list,featured){
    return list.find(node=>node.package==='com.zhou.douyinlite'&&node.class==='android.widget.Button'
        &&node['content-desc']===(featured?'精选':'推荐')&&node.enabled==='true'&&center(node));
}

function check(name,passed){
    report.checks[name]=!!passed;
    if(!passed){const error=new Error('Navigation device assertion failed');error.assertion=true;throw error;}
}

function tap(point){
    if(!point)throw new Error('Native point unavailable');
    shell('input','touchscreen','tap',point.x,point.y);
}

const expression=`(() => {
    const ui=window.LightSwipeUi?.status?.(),media=window.LightSwipe?.status?.();
    const finite=value=>Number.isFinite(value)?value:0;
    return {apiReady:!!ui,clean:!!ui?.clean,applied:!!ui?.applied,
        featured:ui?.channel==='featured',recommended:ui?.channel==='recommend',
        layoutReady:!!ui?.layoutReady,navigationPending:!!ui?.navigationPending,detailOpen:!!ui?.detailOpen,
        hidden:!!document.hidden,suspended:!!media?.paused,viewportHeight:finite(innerHeight),viewportWidth:finite(innerWidth),
        refreshMarkerPresent:window[${JSON.stringify(marker)}]===true};
})()`;

// 后台不读取布局或触发synchronize；浏览器隐藏状态可独立于媒体API获取。
// 媒体状态本身会读取视频几何；onPause后不执行这类可能等待布局线程的查询。
const backgroundExpression=`({hidden:!!document.hidden})`;

function expected(value,featured){
    return !!value&&value.apiReady&&value.clean&&value.applied&&value.layoutReady&&!value.navigationPending
        &&!value.detailOpen&&!value.hidden&&(featured?value.featured&&!value.recommended:value.recommended&&!value.featured);
}

async function sample(deadline,readExpression=expression){
    let timer;
    try{return await Promise.race([client.evaluate(readExpression),new Promise((_,reject)=>{
        timer=setTimeout(()=>reject(new Error('Navigation sample timed out')),Math.max(1,Math.min(4000,deadline-Date.now())));
    })]);}finally{clearTimeout(timer);}
}

async function until(predicate,timeout=20000,stableCount=3,readExpression=expression){
    const deadline=Date.now()+timeout;
    let value,count=0,errors=0;
    while(Date.now()<deadline){
        try{value=await sample(deadline,readExpression);count=predicate(value)?count+1:0;}
        catch(_){errors++;count=0;}
        if(count>=stableCount)return {settled:true,value,sampleErrors:errors};
        await sleep(Math.min(250,Math.max(0,deadline-Date.now())));
    }
    const result={settled:false,sampleErrors:errors};
    if(value)result.value=value;
    return result;
}

async function confirm(featured,prefix,additional=()=>true){
    const ready=await until(value=>expected(value,featured)&&additional(value));
    const observation={expectedFeatured:featured,settled:ready.settled,sampleErrors:ready.sampleErrors};
    report.observations.states[prefix]=observation;
    if(ready.value)observation.web=ready.value;
    check(prefix+'WebRouteSettled',ready.settled&&expected(ready.value,featured)&&additional(ready.value));
    // 完成后重新取原生节点，不能用最初读到的 selected 作为快速切换成功证据。
    const list=await nativeNodes(),selected=tab(list,featured),other=tab(list,!featured);
    observation.nativeSelected=selected?.selected==='true';
    observation.nativeOtherUnselected=other?.selected==='false';
    observation.timeoutPromptAbsent=!list.some(node=>node.package==='com.zhou.douyinlite'
        &&node['content-desc']==='视频缓冲状态'&&node.text==='打开较慢，点此重试'&&center(node));
    check(prefix+'NativeLastTargetSelected',observation.nativeSelected&&observation.nativeOtherUnselected);
    check(prefix+'NoOpeningTimeoutPrompt',observation.timeoutPromptAbsent);
    const afterDump=await sample(Date.now()+5000);
    observation.afterNativeRead=afterDump;
    check(prefix+'RouteRemainsSettled',expected(afterDump,featured)&&additional(afterDump));
    check(prefix+'ViewportHeightStable',Math.abs(afterDump.viewportHeight-initialHeight)<=2);
    return observation;
}

function rapidTaps(featured){
    const sequence=[featured,!featured,featured];
    // 一次 adb 连接执行整组，消除电脑进程启动间隔；坐标来自本次真实原生 UI。
    // 设备侧记录每次原生输入的开始/结束时间，证据明确区分输入耗时与75ms间隔。
    const commands=[];
    sequence.forEach((isFeatured,index)=>{
        const point=isFeatured?toolbar.featured:toolbar.recommended;
        // 设备侧连续发送；另行测量整个序列，避免把shell启动开销当作App失败。
        if(index)commands.push('sleep 0.045');
        commands.push('date +%s%3N',`cmd input touchscreen tap ${point.x} ${point.y} || exit 31`,'date +%s%3N');
    });
    const output=shell(commands.join('; '));
    const lines=output.trim().split(/\r?\n/);
    const timestamps=lines.filter(line=>/^\d{13,}$/.test(line)).map(Number);
    report.observations.lastBurstTiming={lineCount:lines.length,timestamps};
    check(`burst${report.observations.bursts.length+1}DeviceTimingCaptured`,timestamps.length===6&&lines.length===6);
    const taps=sequence.map((target,index)=>({featured:target,startedAtEpochMs:timestamps[index*2],
        finishedAtEpochMs:timestamps[index*2+1],durationMs:timestamps[index*2+1]-timestamps[index*2]}));
    const gaps=taps.slice(1).map((touch,index)=>touch.startedAtEpochMs-taps[index].finishedAtEpochMs);
    const starts=taps.slice(1).map((touch,index)=>touch.startedAtEpochMs-taps[index].startedAtEpochMs);
    return {taps,gapsMs:gaps,startIntervalsMs:starts,allInputDurationsNonnegative:taps.every(touch=>touch.durationMs>=0),
        allInputsRapid:gaps.every(gap=>gap>=0&&gap<=200)
            &&taps.at(-1).finishedAtEpochMs-taps[0].startedAtEpochMs<600};
}

async function refresh(featured){
    const index=report.observations.refresh.length+1,prefix=`refresh${index}`;
    tap(featured?toolbar.featured:toolbar.recommended);
    await confirm(featured,prefix+'Before');
    await client.evaluate(`window[${JSON.stringify(marker)}]=true;true`);
    tap(toolbar.more);menuOpen=true;
    await sleep(200);
    const item=(await nativeNodes()).find(node=>node.package==='com.zhou.douyinlite'&&node.text==='刷新当前网页'
        &&node.enabled==='true'&&center(node));
    check(prefix+'NativeRefreshItemPresent',!!item);
    tap(center(item));menuOpen=false;
    const result=await confirm(featured,prefix+'After',value=>!value.refreshMarkerPresent);
    result.documentReallyReloaded=!result.web.refreshMarkerPresent;
    check(prefix+'NewDocumentObserved',result.documentReallyReloaded);
    report.observations.refresh.push(result);
}

async function lifecycle(featured){
    const index=report.observations.lifecycle.length+1,prefix=`lifecycle${index}`;
    tap(featured?toolbar.featured:toolbar.recommended);
    const before=await confirm(featured,prefix+'Before');
    shell('input','keyevent','KEYCODE_HOME');sentHome=true;
    await sleep(700);
    // WebView.onPause 后旧调试会话可能冻结执行队列；重新附着只读取后台状态，不唤起界面。
    client.close(); client=await connect(port,'douyin.com');
    const background=await until(value=>value.hidden||value.suspended,10000,1,backgroundExpression);
    report.observations.states[prefix+'Background']=background;
    check(prefix+'BackgroundObserved',background.settled&&(background.value.hidden||background.value.suspended));
    shell('am','start','-f','0x10020000','-n','com.zhou.douyinlite/.MainActivity');sentHome=false;
    const after=await confirm(featured,prefix+'After',value=>!value.suspended);
    const observation={expectedFeatured:featured,before,background:background.value,after};
    report.observations.lifecycle.push(observation);
    check(prefix+'SameViewportAfterResume',Math.abs(after.web.viewportHeight-before.web.viewportHeight)<=2);
}

async function main(){
    fs.mkdirSync(runDirectory,{recursive:true});
    client=await connect(port,'douyin.com');
    const initialNodes=await nativeNodes();
    toolbar={featured:center(tab(initialNodes,true)),recommended:center(tab(initialNodes,false)),
        more:center(initialNodes.find(node=>node.package==='com.zhou.douyinlite'&&node.class==='android.widget.Button'
            &&node['content-desc']==='设置与账号'&&node.enabled==='true'))};
    check('initialToolbarAvailable',!!toolbar.featured&&!!toolbar.recommended&&!!toolbar.more);
    report.observations.toolbar=toolbar;
    const initial=await until(value=>expected(value,true)||expected(value,false));
    check('initialCleanChannelReady',initial.settled&&(expected(initial.value,true)||expected(initial.value,false)));
    report.observations.initial=initial.value;initialHeight=initial.value.viewportHeight;
    for(let round=1;round<=2;round++){
        for(const featured of [true,false]){
            const index=report.observations.bursts.length+1,prefix=`burst${index}`;
            const timing=rapidTaps(featured);
            const observation={round,expectedFeatured:featured,timing};
            report.observations.bursts.push(observation);
            check(prefix+'TouchTimingValid',timing.allInputDurationsNonnegative&&timing.allInputsRapid);
            observation.result=await confirm(featured,prefix);
        }
    }
    for(const featured of [true,false])await refresh(featured);
    for(const featured of [true,false])await lifecycle(featured);
    check('fourRapidSwitchSequencesCompleted',report.observations.bursts.length===4);
    check('bothChannelsRefreshed',report.observations.refresh.length===2);
    check('bothChannelsResumed',report.observations.lifecycle.length===2);
}

(async()=>{
    try{await main();}
    catch(error){report.unexpectedError=!error.assertion;process.exitCode=1;}
    finally{
        try{
            if(sentHome){shell('am','start','-f','0x10020000','-n','com.zhou.douyinlite/.MainActivity');sentHome=false;}
            if(menuOpen){shell('input','keyevent','KEYCODE_BACK');menuOpen=false;}
            if(dumpMayExist){shell('rm','-f',dumpPath);dumpMayExist=false;}
            if(temporaryPauseMayExist){
                const resumed=await restoreTemporaryPause();
                if(resumed?.completed!==true)throw new Error('Cleanup temporary pause restoration failed');
            }
            const removed=!!client&&await client.evaluate(`(() => {delete window[${JSON.stringify(marker)}];return !Object.prototype.hasOwnProperty.call(window,${JSON.stringify(marker)});})()`)===true;
            const restored=client?await until(value=>!value.hidden,5000,1,backgroundExpression):{settled:false};
            report.cleanupSucceeded=removed&&restored.settled&&!sentHome&&!menuOpen&&!dumpMayExist&&!temporaryPauseMayExist;
        }catch(_){report.cleanupSucceeded=false;}
        if(client)client.close();
        report.checks.cleanupSucceeded=report.cleanupSucceeded;
        report.finishedAtEpochMs=Date.now();
        report.passed=process.exitCode!==1&&!report.unexpectedError&&Object.values(report.checks).every(Boolean);
        fs.mkdirSync(runDirectory,{recursive:true});
        fs.writeFileSync(path.join(runDirectory,'navigation-check.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
        console.log(JSON.stringify({passed:report.passed,checks:Object.keys(report.checks).length,
            rapidSequences:report.observations.bursts.length,refreshes:report.observations.refresh.length,
            resumes:report.observations.lifecycle.length,cleanupSucceeded:report.cleanupSucceeded}));
        if(!report.passed)process.exitCode=1;
    }
})();
