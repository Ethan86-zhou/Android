"""Small device probe for native playback acceptance; never exports audio or cookies.

Use from an interactive acceptance script. UI actions stay in this app. Status,
app-owned AudioTrack rows and source/APK hashes are the recorded evidence.
"""
import json
import re
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from device_acceptance import Device, PACKAGE, digest, source_manifest, write_json


class NativeProbe:
    def __init__(self, output, serial=None):
        self.device = Device('adb', serial)
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=False)
        self.events = []
        self.checks = []

    def shell(self, *args):
        return self.device.text('shell', *map(str, args))

    def status(self, name):
        data = json.loads(self.device.text('exec-out', 'run-as', PACKAGE, 'cat', 'files/player-status.json'))
        self.events.append({'event': name, 'at': time.time(), **data})
        self.flush()
        return data

    def wait(self, name, predicate, timeout=45):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            data = json.loads(self.device.text('exec-out', 'run-as', PACKAGE, 'cat', 'files/player-status.json'))
            if predicate(data):
                return self.status(name)
            time.sleep(0.7)
        self.status(name + '_timeout')
        raise RuntimeError('Native playback condition timed out: ' + name)

    def ui(self):
        self.shell('uiautomator', 'dump', '/sdcard/light-player-ui.xml')
        root = ET.fromstring(self.device.text('exec-out', 'cat', '/sdcard/light-player-ui.xml'))
        return [n for n in root.iter('node') if n.get('package') == PACKAGE]

    def tap(self, label, scrolls=0):
        for i in range(scrolls + 1):
            nodes = self.ui()
            for n in nodes:
                if n.get('text') == label:
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
                    if x2 > x1 and y2 > y1:
                        self.shell('input', 'tap', (x1+x2)//2, (y1+y2)//2)
                        return
            if i < scrolls:
                lists = [n for n in nodes if n.get('class') == 'android.widget.ListView']
                if not lists:
                    raise RuntimeError('No song list to scroll')
                x1,y1,x2,y2=map(int,re.findall(r'\d+',lists[0].get('bounds')))
                self.shell('input','swipe',(x1+x2)//2,y2-80,(x1+x2)//2,y1+80,180)
        raise RuntimeError('Expected app control is not visible: ' + label)

    def seek(self, fraction):
        for n in self.ui():
            if n.get('class') == 'android.widget.SeekBar':
                x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
                # Android default SeekBar includes visual padding at both ends.
                inset=(y2-y1)*0.12
                self.shell('input','tap',round(x1+inset+(x2-x1-2*inset)*fraction),(y1+y2)//2)
                return
        raise RuntimeError('No native seek bar')

    def screenshot(self, name):
        if not self.ui():
            raise RuntimeError('App not visible; refusing to capture another app')
        data=self.device.run('exec-out','screencap','-p',binary=True).stdout
        (self.output/(name+'.png')).write_bytes(data)

    def audio(self, name):
        pid=self.shell('pidof', PACKAGE).strip()
        dump=self.shell('dumpsys','media.audio_flinger')
        rows=[line.strip() for line in dump.splitlines()
              if re.search(r'\syes\s+'+re.escape(pid)+r'/\s*\d+\s',line)]
        self.events.append({'event':name,'at':time.time(),'activeAudioTracks':rows})
        self.flush()
        return rows

    def check(self, name, passed, detail=None):
        self.checks.append({'name':name,'passed':bool(passed),'detail':detail})
        self.flush()
        if not passed:
            raise AssertionError(name)

    def flush(self):
        write_json(self.output/'events.json', self.events)
        write_json(self.output/'checks.json', self.checks)

    def finish(self, apk):
        app=Path(__file__).resolve().parents[1]
        result={'apkSha256':digest(Path(apk)),**source_manifest(app,self.output),
                'checks':self.checks,'passed':all(c['passed'] for c in self.checks),
                'limitations':['Selected real songs and positions tested; not all songs or a full uninterrupted playlist.',
                               'Physical headset disconnection not tested.']}
        write_json(self.output/'verification.json',result)
        return result
