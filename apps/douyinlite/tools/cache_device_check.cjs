'use strict';
// 已授权的设备验收：只清理可再生缓存，不选择“重置网站数据”。
const fs=require('node:fs'),path=require('node:path');
const {execFileSync}=require('node:child_process');
const {connect}=require('./cdp.cjs');
const adb=path.join(process.env.ANDROID_HOME,'platform-tools','adb.exe');
const out=process.argv[3];
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const shell=(...args)=>execFileSync(adb,['shell',...args],{encoding:'utf8',windowsHide:true,timeout:15000});
const report={checks:{},cacheMiBBefore:null,cacheMiBAfter:null,passed:false};
function dump(){shell('uiautomator','dump','/sdcard/lightswipe-cache.xml');return shell('cat','/sdcard/lightswipe-cache.xml');}
function tapText(text){
 const xml=dump(),node=[...xml.matchAll(/<node\b[^>]*>/g)].map(m=>m[0]).find(n=>n.includes(`text="${text}"`));
 if(!node)throw new Error('Native control unavailable');
 const b=node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
 shell('input','tap',String(Math.round((+b[1]+ +b[3])/2)),String(Math.round((+b[2]+ +b[4])/2)));
}
const loginAbsent=`![...document.querySelectorAll('button,a,div,span')].some(e=>e.textContent.trim()==='登录'&&e.getBoundingClientRect().width>0&&getComputedStyle(e).visibility!=='hidden')`;
(async()=>{
 let c;
 try{
  fs.mkdirSync(out,{recursive:true});c=await connect(Number(process.argv[2]),'douyin.com');
  report.checks.loginPresentBefore=await c.evaluate(loginAbsent);
  await c.evaluate('window.LightSwipe?.pause()');
  shell('input','tap','1320','170');await sleep(300);tapText('缓存与网站数据');await sleep(700);
  const xml=dump();report.checks.oneGiBThresholdVisible=xml.includes('1 GiB');
  report.checks.loginPreservationExplained=xml.includes('清理缓存保留登录');
  const before=xml.match(/可清理缓存：([\d.]+) MiB/);report.cacheMiBBefore=before?+before[1]:null;
  tapText('清理缓存');await sleep(1300);
  report.checks.loginPresentAfterClear=await c.evaluate(loginAbsent);
  shell('input','tap','1320','170');await sleep(250);tapText('缓存与网站数据');await sleep(700);
  const after=dump().match(/可清理缓存：([\d.]+) MiB/);report.cacheMiBAfter=after?+after[1]:null;
  report.checks.cacheBytesObservedLower=report.cacheMiBAfter!==null&&report.cacheMiBBefore!==null&&report.cacheMiBAfter<report.cacheMiBBefore;
  tapText('返回');
  await c.call('Page.reload');await sleep(4000);
  report.checks.loginPresentAfterReload=await c.evaluate(loginAbsent);
  report.checks.officialTagsAfterReload=await c.evaluate(`['精选','推荐'].every(t=>document.body.innerText.includes(t))`);
  report.passed=Object.values(report.checks).every(Boolean);
 }catch(e){report.unexpectedError=true;process.exitCode=1;}
 finally{if(c)c.close();report.finishedAtEpochMs=Date.now();fs.writeFileSync(path.join(out,'cache-check.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));if(!report.passed)process.exitCode=1;}
})();
