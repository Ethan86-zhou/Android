(function () {
  'use strict';
  var q=function(s){return document.querySelector(s)}, all=function(s){return document.querySelectorAll(s)};
  var sensorValues=all('.sensor .value'), mode=q('#screen > .green'), aircraft=q('.aircraft');
  var readout=all('.readout p'), ticks=all('#altMarks p');
  var lastPacket=0, lastAlign=0;
  function signed(n,d){return (n>=0?'+':'−')+Math.abs(n).toFixed(d)}
  function vector(a,d){return a?a.map(function(v){return signed(v,d)}).join('  '):'—  —  —'}
  function wrap(v){return (v%360+360)%360}
  function update(d){
    lastPacket=Date.now();
    sensorValues[0].textContent=vector(d.acc,2);
    sensorValues[1].textContent=vector(d.gyro,1);
    sensorValues[2].textContent=d.mag?((d.magHeading!=null?Math.round(d.magHeading)%360+'°  ':'')+Math.hypot.apply(Math,d.mag).toFixed(1)):'—';
    sensorValues[3].textContent=d.pressure==null?'—':d.pressure.toFixed(1);
    mode.textContent=d.gyroAttitude?'LIVE · GYRO DEMO':'WAITING · GYRO';
    function pose(el,valid,pitch,roll){
      el.style.visibility=valid?'visible':'hidden';
      if(valid){el.style.top=(121+Math.max(-35,Math.min(35,pitch))*3.2)+'px';el.style.transform='rotate('+roll+'deg)'}
    }
    pose(aircraft,d.gyroAttitude,d.gyroPitch,d.gyroRoll);
    pose(q('.fusion-aircraft'),d.attitude,d.pitch,d.roll);
    if(d.attitude)q('.fusion-aircraft').style.transform+=' scale(1.1)';
    if(d.gyroAttitude){
      aircraft.alt='Gyroscope-only integrated attitude';
      readout[0].textContent='ROLL '+signed(d.gyroRoll,1)+'°';readout[1].textContent='PITCH '+signed(d.gyroPitch,1)+'°';
    }else{readout[0].textContent='ROLL —';readout[1].textContent='PITCH —'}
    q('#fusionReadout').textContent=d.attitude?'FUSION  R '+signed(d.roll,1)+'°  P '+signed(d.pitch,1)+'°':'FUSION UNAVAILABLE';
    q('#zeroGyro').disabled=!d.gyroAttitude;
    q('#zeroGyro').style.opacity=d.gyroAttitude?'1':'.4';
    function bearing(id,pointer,label,value){
      q(id).textContent=label+' '+(value==null?'—':wrap(value).toFixed(1))+'°';
      q(pointer).style.visibility=value==null?'hidden':'visible';
      if(value!=null)q(pointer).style.left=(wrap(value)/360*230)+'px';
    }
    bearing('#magHeading','#magPointer','MAG',d.magHeading);
    bearing('#gyroHeading','#gyroPointer','GYRO',d.gyroHeading);
    q('#headingDelta').textContent=d.headingDelta==null?'—°':signed(d.headingDelta,1)+'°';
    q('#alignHeading').disabled=!d.canAlign;
    q('#alignHeading').style.opacity=d.canAlign?'1':'.4';
    if(d.alignCount>lastAlign){
      lastAlign=d.alignCount;q('#alignHeading').textContent='ALIGNED';
      setTimeout(function(){q('#alignHeading').textContent='ALIGN HEADING'},1200);
    }
    q('#alt').textContent=d.alt==null?'—':signed(d.alt,1);
    q('#alt').style.fontSize=d.alt!=null&&Math.abs(d.alt)>=10?'22px':'30px';
    q('#delta').textContent=d.alt==null?'BARO UNAVAILABLE':'ΔH '+signed(d.alt,1)+' m';
    q('.altitude > p:nth-last-child(2)').textContent=d.pressure==null?'— hPa':d.pressure.toFixed(1)+' hPa';
    var base=d.alt==null?0:Math.round(d.alt/4)*4;
    for(var t=0;t<ticks.length;t++)ticks[t].textContent=String(base+8-t*4);
    q('#zero').disabled=d.alt==null;q('#zero').style.opacity=d.alt==null?'.4':'1';
    var rates=d.hz||[0,0,0,0], states=d.states||[];
    q('#status').textContent='ACC '+Math.round(rates[0])+'  GYRO '+Math.round(rates[1])+'  MAG '+Math.round(rates[2])+'  BARO '+Math.round(rates[3])+' Hz · '+(d.cal>=2?'MAG OK':d.cal>=0?'MAG: FIGURE-8':'MAG N/A');
    for(var j=0;j<4;j++)all('.sensor .label')[j].style.opacity=states[j]==='OK'?'1':'.4';
  }
  window.updateSensors=update;
  q('#zero').addEventListener('click',function(){if(window.Sensors){window.Sensors.zeroGround();q('#zero').textContent='GROUND SET';setTimeout(function(){q('#zero').textContent='SET GROUND 0'},1000)}});
  q('#alignHeading').addEventListener('click',function(){if(window.Sensors&&!this.disabled)window.Sensors.alignHeading()});
  q('#zeroGyro').addEventListener('click',function(){if(window.Sensors&&!this.disabled)window.Sensors.zeroGyro()});
  function empty(){update({attitude:false,cal:-1,states:['WAIT','WAIT','WAIT','WAIT'],hz:[0,0,0,0]})}
  empty();setInterval(function(){if(Date.now()-lastPacket>2500)empty()},1000);
})();
