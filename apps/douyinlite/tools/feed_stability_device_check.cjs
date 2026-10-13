'use strict';

// 连续原生上滑验收；保持当前推荐流或精选详情，不改变频道或网页播放状态。
// node tools/feed_stability_device_check.cjs PORT EVIDENCE_ROOT [COUNT=20]
// 页面只返回匿名数字、布尔及固定枚举；不保存网址、DOM id、文本、图片或视频内容。
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {connect} = require('./cdp.cjs');

const port = Number(process.argv[2]);
const evidenceRoot = process.argv[3] && path.resolve(process.argv[3]);
const count = process.argv[4] === undefined ? 20 : Number(process.argv[4]);
if (!Number.isInteger(port) || port < 1 || port > 65535 || !evidenceRoot
        || !Number.isInteger(count) || count < 2 || count > 100 || !process.env.ANDROID_HOME) {
    console.error('Usage: node feed_stability_device_check.cjs PORT EVIDENCE_ROOT [COUNT=20]; ANDROID_HOME required.');
    process.exit(2);
}

const started = Date.now();
const runDirectory = path.join(evidenceRoot, `feed-stability-${started}-${process.pid}`);
const dumpPath = `/sdcard/lightswipe-stability-${started}-${process.pid}.xml`;
const pageKey = `__lightSwipeStability${started}_${process.pid}`;
const adb = path.join(process.env.ANDROID_HOME, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const tolerance = 2;
const report = {
    schemaVersion: 1, startedAtEpochMs: started, requestedSwipeCount: count,
    cssPixelTolerance: tolerance, passed: false, checks: {}, observations: {steps: []},
    unexpectedError: false, cleanupSucceeded: false, failureKind: 'none'
};
let client;
let nativeInputStarted = false;
let dumpMayExist = false;

function shell(...args) {
    return execFileSync(adb, ['shell', ...args.map(String)], {
        encoding: 'utf8', windowsHide: true, timeout: args[0]==='uiautomator'?15000:4000,
        stdio: ['ignore', 'pipe', 'pipe']
    });
}

function nativeViewport() {
    let xml;
    try {
        dumpMayExist = true;
        shell('uiautomator', 'dump', dumpPath);
        xml = shell('cat', dumpPath);
    } finally {
        shell('rm', '-f', dumpPath);
        dumpMayExist = false;
    }
    for (const match of xml.matchAll(/<node\b[^>]*>/g)) {
        const node = Object.fromEntries([...match[0].matchAll(/([\w:-]+)="([^"]*)"/g)]
            .map(value => [value[1], value[2]]));
        if (node.package !== 'com.zhou.douyinlite' || node.class !== 'android.webkit.WebView') continue;
        const bounds = node.bounds?.match(/^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/);
        if (!bounds) continue;
        const [, left, top, right, bottom] = bounds.map(Number);
        if (left >= 0 && top >= 0 && right > left && bottom > top) {
            return {left, top, width: right-left, height: bottom-top};
        }
    }
    return null;
}

const expression = `(() => {
    const key = ${JSON.stringify(pageKey)};
    const state = window[key] || (window[key] = {nodes: new WeakMap(), next: 0});
    const token = node => {
        if (!node) return 0;
        if (!state.nodes.has(node)) state.nodes.set(node, ++state.next);
        return state.nodes.get(node);
    };
    const finite = value => Number.isFinite(value) ? value : 0;
    const viewport = {left: 0, top: 0, right: innerWidth, bottom: innerHeight};
    const intersect = (a, b) => {
        const left = Math.max(a.left, b.left), top = Math.max(a.top, b.top);
        const right = Math.min(a.right, b.right), bottom = Math.min(a.bottom, b.bottom);
        return {left, top, right, bottom, width: Math.max(0,right-left), height: Math.max(0,bottom-top)};
    };
    const area = rect => rect.width * rect.height;
    const hasLayout = node => {
        if (!node?.isConnected) return false;
        const rect=node.getBoundingClientRect();
        if (!(rect.width>0&&rect.height>0)) return false;
        for (let p=node; p?.nodeType===1; p=p.parentElement) {
            const style=getComputedStyle(p);
            if (p.hidden || style.display==='none' || style.visibility==='hidden'
                    || style.visibility==='collapse' || Number.parseFloat(style.opacity)===0) return false;
        }
        return true;
    };
    const visible = node => hasLayout(node)&&area(intersect(node.getBoundingClientRect(),viewport))>0;
    const modal = [...document.querySelectorAll('.modal-video-container')].filter(visible)
        .sort((a,b)=>area(intersect(b.getBoundingClientRect(),viewport))-area(intersect(a.getBoundingClientRect(),viewport)))[0];
    // 推荐以固定外容器为原点，不把正在 translate 的滑块当作列表原点。
    const recommend = [...document.querySelectorAll('#slidelist.recommend-slidelist')].find(visible);
    const modalList = modal && [...modal.querySelectorAll('#slidelist')].find(visible);
    const container = modal ? (modalList || modal) : recommend;
    const kind = modal ? 'featured-modal' : recommend ? 'recommend' : 'none';
    const cr = container?.getBoundingClientRect();
    const clip = cr ? intersect(cr, viewport) : {left:0,top:0,right:0,bottom:0,width:0,height:0};
    const slides = container ? [...container.querySelectorAll('.dySwiperSlide,.page-recommend-container')] : [];
    const candidates = slides.filter(visible).map(node => {
        const rect=node.getBoundingClientRect();
        return {node, rect, shown:intersect(rect,clip)};
    }).filter(item=>area(item.shown)>0).sort((a,b)=>area(b.shown)-area(a.shown));
    const active=candidates[0];
    const ar=active?.rect;
    const transformValues = value => {
        const match = /^(matrix|matrix3d)\\(([^)]+)\\)$/.exec(value);
        if (!match) return [];
        return match[2].split(',').map(Number).filter(Number.isFinite);
    };
    const transforms=[];
    let depth=0;
    for(let p=active?.node.parentElement;p&&p!==container&&depth<6;p=p.parentElement,depth++){
        const values=transformValues(getComputedStyle(p).transform);
        if(values.length===6||values.length===16)transforms.push({depth,values});
    }
    const wrapper=active?.node.parentElement;
    const siblings = wrapper ? [...wrapper.children].filter(node=>slides.includes(node)) : [];
    // 下一页可以在视口外，但必须已由官网挂载，且和当前页属于同一滑动容器。
    const next = ar ? siblings.filter(node=>node!==active.node&&hasLayout(node))
        .map(node=>({node,rect:node.getBoundingClientRect()}))
        .filter(item=>item.rect.top>ar.top+Math.max(2,ar.height/2))
        .sort((a,b)=>a.rect.top-b.rect.top)[0] : undefined;
    const heights = siblings.map(node=>node.getBoundingClientRect()).sort((a,b)=>a.top-b.top);
    const spacings=[];
    for(let i=1;i<heights.length;i++)spacings.push({pitch:heights[i].top-heights[i-1].top,gap:heights[i].top-heights[i-1].bottom});
    const media=window.LightSwipe?.status();
    const ui=window.LightSwipeUi?.status();
    return {
        kind,clean:!!ui?.clean,playerActive:!!ui?.playerActive,documentHidden:!!document.hidden,
        viewport:{width:finite(innerWidth),height:finite(innerHeight),visualTop:finite(visualViewport?.offsetTop),visualHeight:finite(visualViewport?.height)},
        container:{present:!!container,token:token(container),top:finite(cr?.top),left:finite(cr?.left),height:finite(cr?.height),width:finite(cr?.width),visibleTop:finite(clip.top),visibleHeight:finite(clip.height),visibleWidth:finite(clip.width)},
        activeSlide:{present:!!active,token:token(active?.node),top:finite(ar?.top),left:finite(ar?.left),height:finite(ar?.height),width:finite(ar?.width),intersectionArea:finite(active&&area(active.shown)),topError:finite(ar&&ar.top-clip.top),heightError:finite(ar&&ar.height-clip.height)},
        nextSlide:{present:!!next,token:token(next?.node),top:finite(next?.rect.top),height:finite(next?.rect.height),pitch:finite(next&&next.rect.top-ar.top),gap:finite(next&&next.rect.top-ar.bottom)},
        visibleSlideCount:candidates.length,slideCount:slides.length,transforms,spacings,
        scroll:{documentTop:finite(document.scrollingElement?.scrollTop),windowY:finite(scrollY),containerTop:finite(container?.scrollTop)},
        media:{available:!!media?.available,id:finite(media?.mediaId),time:finite(media?.currentTime),duration:finite(media?.duration),playing:!!media?.playing,readyState:finite(media?.readyState),buffering:!!media?.buffering,bufferedAhead:finite(media?.bufferedAhead),suspended:!!media?.paused}
    };
})()`;

async function boundedSample(deadline) {
    const budget = Math.min(1500, deadline-Date.now());
    if (budget <= 0) return null;
    let timer;
    try {
        return await Promise.race([
            client.evaluate(expression),
            new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Sample timed out')),budget);})
        ]);
    } finally { clearTimeout(timer); }
}

function ready(value) {
    return !!value && value.kind !== 'none' && value.clean && value.playerActive && !value.documentHidden
        && value.container.present && value.container.visibleHeight>0 && value.activeSlide.present
        && value.activeSlide.intersectionArea>0 && value.media.available && value.media.id>0 && !value.media.suspended;
}

function stable(a,b) {
    if (!ready(a) || !ready(b) || a.kind!==b.kind || a.container.token!==b.container.token
            || a.activeSlide.token!==b.activeSlide.token || a.media.id!==b.media.id) return false;
    const values = value => [value.viewport.width,value.viewport.height,value.container.top,value.container.height,
        value.activeSlide.top,value.activeSlide.height,value.scroll.documentTop,value.scroll.windowY,value.scroll.containerTop];
    return values(a).every((value,index)=>Math.abs(value-values(b)[index])<=0.25);
}

function changed(before,after) {
    return ready(after) && (after.media.id!==before.media.id || after.activeSlide.token!==before.activeSlide.token);
}

async function waitStable(before, deadline) {
    let last=null, lastValid=null, consecutive=0, samples=0, sampleErrors=0;
    while(Date.now()<deadline) {
        let value;
        try { value=await boundedSample(deadline); }
        catch(_) { sampleErrors++; value=null; }
        samples++;
        if(value)lastValid=value;
        if (ready(value) && (!before || changed(before,value))) {
            consecutive=stable(last,value) ? consecutive+1 : 0;
            last=value;
            // 三份相隔至少250ms的测量，才能证明连续两段时间没有滑动动画。
            if (consecutive>=2) return {settled:true,sample:value,samples,sampleErrors};
        } else { last=value; consecutive=0; }
        const remaining=deadline-Date.now();
        if(remaining>0)await sleep(Math.min(250,remaining));
    }
    // 超时始终失败；最后一次读取异常不应抹掉已有的有效几何诊断。
    return {settled:false,sample:lastValid,samples,sampleErrors};
}

async function waitNextItem() {
    const startedAt=Date.now(),deadline=startedAt+10000;
    let last=null,lastValid=null,samples=0,sampleErrors=0;
    while(Date.now()<deadline){
        let value;
        try{value=await boundedSample(deadline);}
        catch(_){sampleErrors++;value=null;}
        samples++;
        if(value)lastValid=value;
        if(ready(value)&&value.nextSlide.present&&stable(last,value)){
            return {ready:true,sample:value,waitMs:Date.now()-startedAt,samples,sampleErrors};
        }
        last=value;
        const remaining=deadline-Date.now();
        if(remaining>0)await sleep(Math.min(250,remaining));
    }
    return {ready:false,sample:lastValid,waitMs:Date.now()-startedAt,samples,sampleErrors};
}

function geometryChecks(value) {
    return {
        activeSlideVisible:ready(value),
        containerStartsAtViewportTop:ready(value)&&Math.abs(value.container.top)<=tolerance,
        activeSlideStartsAtContainerTop:ready(value)&&Math.abs(value.activeSlide.topError)<=tolerance,
        activeSlideMatchesVisibleContainerHeight:ready(value)&&Math.abs(value.activeSlide.heightError)<=tolerance,
        noDocumentScroll:ready(value)&&Math.abs(value.scroll.documentTop)<=tolerance&&Math.abs(value.scroll.windowY)<=tolerance
    };
}

function transitionMetrics(before,after) {
    const firstTranslation = value => {
        const matrix=value?.transforms[0]?.values;
        return matrix?.length===6?matrix[5]:matrix?.length===16?matrix[13]:undefined;
    };
    const start=firstTranslation(before),end=firstTranslation(after);
    return {
        translationAvailable:Number.isFinite(start)&&Number.isFinite(end),
        beforeTranslateY:Number.isFinite(start)?start:0,
        afterTranslateY:Number.isFinite(end)?end:0,
        translateDelta:Number.isFinite(start)&&Number.isFinite(end)?end-start:0,
        beforeAdjacentPitch:before?.spacings[0]?.pitch??0,
        afterAdjacentPitch:after?.spacings[0]?.pitch??0,
        beforeAdjacentGap:before?.spacings[0]?.gap??0,
        afterAdjacentGap:after?.spacings[0]?.gap??0
    };
}

function stop(kind) {
    report.failureKind=kind;
    const error=new Error('Feed stability check stopped'); error.expected=true; throw error;
}

async function main() {
    fs.mkdirSync(runDirectory,{recursive:true});
    client=await connect(port,'douyin.com');
    const native=nativeViewport();
    report.checks.nativeViewportAvailable=!!native;
    if(!native)stop('native-viewport');
    report.observations.nativeViewport=native;
    const initial=await waitStable(null,Date.now()+10000);
    report.observations.initial={settled:initial.settled,samples:initial.samples,sampleErrors:initial.sampleErrors};
    if(initial.sample)report.observations.initial.sample=initial.sample;
    report.checks.initialFeedReady=initial.settled&&ready(initial.sample);
    if(!report.checks.initialFeedReady)stop('initial-feed');
    const initialGeometry=geometryChecks(initial.sample);
    for(const [name,value] of Object.entries(initialGeometry))report.checks[`initial${name[0].toUpperCase()}${name.slice(1)}`]=value;
    let before=initial.sample;
    for(let index=1;index<=count;index++) {
        const nextItem=await waitNextItem();
        report.checks[`swipe${String(index).padStart(2,'0')}NextItemReady`]=nextItem.ready;
        if(!nextItem.ready){
            const step={index,passed:false,gestureSent:false,nextItemWaitMs:nextItem.waitMs,
                nextItemSamples:nextItem.samples,nextItemSampleErrors:nextItem.sampleErrors,
                checks:{nextItemReady:false,transitionSettled:false,realFeedTransition:false}};
            if(nextItem.sample)step.beforeGesture=nextItem.sample;
            report.observations.steps.push(step);
            report.checks[`swipe${String(index).padStart(2,'0')}Passed`]=false;
            report.observations.completedSwipeCount=index-1;
            stop('next-item-not-ready');
        }
        // 等待预加载期间可能有视频自身状态变化，用发送这次手势前的真实样本作比较。
        before=nextItem.sample;
        const stepStart=Date.now(),deadline=stepStart+10000;
        // 开始位置在视频主体中间，避开原生底栏、两侧长按区及系统返回边缘。
        const x=Math.round(native.left+native.width*0.5);
        const startY=Math.round(native.top+native.height*0.72),endY=Math.round(native.top+native.height*0.30);
        nativeInputStarted=true;
        shell('input','touchscreen','swipe',x,startY,x,endY,360);
        const result=await waitStable(before,deadline);
        const after=result.sample;
        const checks={nextItemReady:nextItem.ready,transitionSettled:result.settled,realFeedTransition:changed(before,after),...geometryChecks(after),
            viewportHeightStable:ready(after)&&Math.abs(after.viewport.height-initial.sample.viewport.height)<=tolerance,
            sameFeedContext:ready(after)&&after.kind===initial.sample.kind,
            withinTenSeconds:Date.now()<=deadline};
        const step={index,elapsedMs:Date.now()-stepStart,passed:Object.values(checks).every(Boolean),checks,gestureSent:true,
            nextItemWaitMs:nextItem.waitMs,nextItemSamples:nextItem.samples,nextItemSampleErrors:nextItem.sampleErrors,
            beforeGesture:before,samples:result.samples,sampleErrors:result.sampleErrors,transition:transitionMetrics(before,after)};
        if(after)step.geometry=after;
        report.observations.steps.push(step);
        report.observations.completedSwipeCount=report.observations.steps.filter(item=>
            item.gestureSent&&item.checks.transitionSettled&&item.checks.realFeedTransition).length;
        report.checks[`swipe${String(index).padStart(2,'0')}Passed`]=step.passed;
        console.log(JSON.stringify({step:index,passed:step.passed,settled:result.settled,
            topError:after?.activeSlide.topError??0,heightError:after?.activeSlide.heightError??0}));
        if(!result.settled||!changed(before,after))stop('transition-timeout');
        before=after;
    }
    const accepted=report.observations.steps.filter(step=>step.checks.transitionSettled&&step.checks.realFeedTransition);
    const offsets=accepted.map(step=>step.geometry.activeSlide.topError);
    report.observations.completedSwipeCount=accepted.length;
    report.observations.maximumTopError=offsets.length?Math.max(...offsets.map(Math.abs)):0;
    report.observations.maximumDriftFromInitial=offsets.length?Math.max(...offsets.map(value=>Math.abs(value-initial.sample.activeSlide.topError))):0;
    report.checks.requestedRealTransitionsCompleted=accepted.length===count;
    report.checks.everyStepPassed=report.observations.steps.length===count&&report.observations.steps.every(step=>step.passed);
    report.checks.noCumulativeDrift=offsets.length===count&&report.observations.maximumDriftFromInitial<=tolerance;
    if(!Object.values(report.checks).every(Boolean))report.failureKind='geometry';
}

(async()=>{
    try { await main(); }
    catch(error) { report.unexpectedError=!error.expected; if(report.failureKind==='none')report.failureKind='runtime'; }
    finally {
        try {
            // input swipe 是完整的DOWN/MOVE/UP；异常时发送CANCEL，避免留下触摸状态。
            if(nativeInputStarted&&report.unexpectedError)shell('input','touchscreen','motionevent','CANCEL',0,0);
            if(dumpMayExist)shell('rm','-f',dumpPath);
            report.cleanupSucceeded=!!client&&await client.evaluate(`(() => {delete window[${JSON.stringify(pageKey)}];return !Object.prototype.hasOwnProperty.call(window,${JSON.stringify(pageKey)});})()`)===true;
        } catch(_) { report.cleanupSucceeded=false; }
        if(client)client.close();
        report.checks.cleanupSucceeded=report.cleanupSucceeded;
        report.finishedAtEpochMs=Date.now();
        report.passed=!report.unexpectedError&&report.failureKind==='none'&&Object.values(report.checks).every(Boolean);
        fs.mkdirSync(runDirectory,{recursive:true});
        fs.writeFileSync(path.join(runDirectory,'feed-stability-check.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
        console.log(JSON.stringify({passed:report.passed,completedSwipes:report.observations.completedSwipeCount||0,
            attemptedSwipes:report.observations.steps.filter(step=>step.gestureSent).length,
            checks:Object.keys(report.checks).length,cleanupSucceeded:report.cleanupSucceeded}));
        if(!report.passed)process.exitCode=1;
    }
})();
