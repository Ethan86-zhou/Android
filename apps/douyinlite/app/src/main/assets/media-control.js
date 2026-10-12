(function () {
    'use strict';

    // 由原生端注入官方网页。只控制 video 播放状态，不读取账号、Cookie 或网页地址。
    if (window.LightSwipe && typeof window.LightSwipe.destroy === 'function') {
        window.LightSwipe.destroy();
    }

    var BOOST_RATE = 2;
    var MONITOR_MS = 350;
    var destroyed = false;
    var boostRequested = false;
    var manualPaused = false;
    var pageHidden = !!document.hidden;
    var resumeCandidate = null;
    var controlledVideo = null;
    var originals = new Map();
    var intervalId = null;
    var pendingSync = null;
    var observer = null;

    function suspended() {
        return manualPaused || pageHidden;
    }

    function videos() {
        // 不进入 iframe：跨源播放器仍由网站管理，避免越过同源边界。
        return Array.prototype.slice.call(document.querySelectorAll('video'));
    }

    function finiteNumber(value, fallback) {
        return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
    }

    function isPlaying(video) {
        return !!video && !video.paused && !video.ended && video.readyState >= 2;
    }

    function intersect(first, second) {
        return {
            left: Math.max(first.left, second.left),
            top: Math.max(first.top, second.top),
            right: Math.min(first.right, second.right),
            bottom: Math.min(first.bottom, second.bottom)
        };
    }

    function visibleArea(video) {
        if (video.isConnected === false) return 0;
        var rect = video.getBoundingClientRect();
        var width = window.innerWidth || document.documentElement.clientWidth;
        var height = window.innerHeight || document.documentElement.clientHeight;
        var visible = intersect(rect, { left: 0, top: 0, right: width, bottom: height });
        for (var element = video; element && element.nodeType === 1; element = element.parentElement) {
            if (element.hidden) return 0;
            var style = window.getComputedStyle(element);
            if (style.display === 'none' || style.visibility === 'hidden' ||
                style.visibility === 'collapse' || Number.parseFloat(style.opacity) === 0 ||
                style.contentVisibility === 'hidden') return 0;
            // 视频卡片滑出裁剪容器时，即使还落在屏幕范围内也不能成为控制对象。
            if (element !== video && (style.overflowX !== 'visible' || style.overflowY !== 'visible')) {
                var parentRect = element.getBoundingClientRect();
                if (style.overflowX !== 'visible') {
                    visible.left = Math.max(visible.left, parentRect.left);
                    visible.right = Math.min(visible.right, parentRect.right);
                }
                if (style.overflowY !== 'visible') {
                    visible.top = Math.max(visible.top, parentRect.top);
                    visible.bottom = Math.min(visible.bottom, parentRect.bottom);
                }
            }
        }
        return Math.max(0, visible.right - visible.left) * Math.max(0, visible.bottom - visible.top);
    }

    function selectedVideo(list) {
        var best = null;
        var bestArea = 0;
        list.forEach(function (video) {
            var area = visibleArea(video);
            if (area > bestArea || (area > 0 && area === bestArea && isPlaying(video) && !isPlaying(best))) {
                best = video;
                bestArea = area;
            }
        });
        return best;
    }

    function restoreVideo(video) {
        var original = originals.get(video);
        if (!original) return;
        originals.delete(video);
        try { video.playbackRate = original.rate; } catch (_) { /* 视频可能已由网页移除。 */ }
        if (original.pitch !== undefined) {
            try { video.preservesPitch = original.pitch; } catch (_) { /* 旧 WebView 可无此属性。 */ }
        }
        if (original.webkitPitch !== undefined) {
            try { video.webkitPreservesPitch = original.webkitPitch; } catch (_) { /* 兼容旧 WebView。 */ }
        }
    }

    function restoreAll() {
        Array.from(originals.keys()).forEach(restoreVideo);
        controlledVideo = null;
    }

    function applyBoost(video) {
        if (!originals.has(video)) {
            originals.set(video, {
                rate: finiteNumber(video.playbackRate, 1),
                pitch: typeof video.preservesPitch === 'boolean' ? video.preservesPitch : undefined,
                webkitPitch: typeof video.webkitPreservesPitch === 'boolean' ? video.webkitPreservesPitch : undefined
            });
        }
        try {
            if ('preservesPitch' in video) video.preservesPitch = true;
            if ('webkitPreservesPitch' in video) video.webkitPreservesPitch = true;
            if (video.playbackRate !== BOOST_RATE) video.playbackRate = BOOST_RATE;
        } catch (_) {
            restoreVideo(video);
        }
    }

    function sync() {
        if (destroyed || !boostRequested || suspended()) return;
        var video = selectedVideo(videos());
        if (controlledVideo !== video) {
            if (controlledVideo) restoreVideo(controlledVideo);
            controlledVideo = video;
        }
        if (video) applyBoost(video);
    }

    function queueSync() {
        if (destroyed || !boostRequested || suspended() || pendingSync !== null) return;
        pendingSync = window.setTimeout(function () {
            pendingSync = null;
            sync();
        }, 80);
    }

    function stopMonitoring() {
        if (intervalId !== null) window.clearInterval(intervalId);
        if (pendingSync !== null) window.clearTimeout(pendingSync);
        intervalId = null;
        pendingSync = null;
        if (observer) observer.disconnect();
        observer = null;
        document.removeEventListener('scroll', queueSync, true);
        document.removeEventListener('ratechange', queueSync, true);
        document.removeEventListener('loadedmetadata', queueSync, true);
        document.removeEventListener('emptied', queueSync, true);
        window.removeEventListener('resize', queueSync);
    }

    function startMonitoring() {
        if (intervalId !== null || destroyed || suspended()) return;
        // 网页滚动/替换节点时及时换目标；仅锁定或按住快进时才有低频兜底轮询。
        intervalId = window.setInterval(sync, MONITOR_MS);
        if (typeof MutationObserver === 'function') {
            observer = new MutationObserver(queueSync);
            observer.observe(document.documentElement, {
                subtree: true,
                childList: true,
                attributes: true,
                attributeFilter: ['style', 'class', 'hidden', 'src']
            });
        }
        document.addEventListener('scroll', queueSync, { capture: true, passive: true });
        document.addEventListener('ratechange', queueSync, true);
        document.addEventListener('loadedmetadata', queueSync, true);
        document.addEventListener('emptied', queueSync, true);
        window.addEventListener('resize', queueSync, { passive: true });
    }

    function snapshot() {
        var list = destroyed ? [] : videos();
        var video = selectedVideo(list);
        return {
            available: !!video,
            boosted: !!video && boostRequested && !suspended() && originals.has(video) && video.playbackRate === BOOST_RATE,
            playing: isPlaying(video),
            currentTime: video ? finiteNumber(video.currentTime, 0) : 0,
            duration: video ? finiteNumber(video.duration, 0) : 0,
            rate: video ? finiteNumber(video.playbackRate, 1) : null,
            videoCount: list.length,
            boostRequested: boostRequested,
            paused: suspended(),
            destroyed: destroyed
        };
    }

    function pausePlayback() {
        var list = videos();
        var video = selectedVideo(list);
        resumeCandidate = isPlaying(video) ? video : null;
        boostRequested = false;
        stopMonitoring();
        restoreAll();
        // 停止网页保留的离屏视频；返回前台只恢复之前可见且正在播放的那一个。
        list.forEach(function (item) {
            if (!item.paused) {
                try { item.pause(); } catch (_) { /* 网站可以自行卸载播放器。 */ }
            }
        });
    }

    function resumePlayback() {
        if (destroyed || suspended()) return;
        var candidate = resumeCandidate;
        resumeCandidate = null;
        if (!candidate || selectedVideo(videos()) !== candidate || candidate.ended || !candidate.paused) return;
        try {
            var promise = candidate.play();
            // WebView 可能因自动播放策略拒绝；交回网页的播放按钮，不产生未处理 Promise。
            if (promise && typeof promise.catch === 'function') promise.catch(function () {});
        } catch (_) { /* 用户可以在网页中继续播放。 */ }
    }

    function onVisibilityChange() {
        if (destroyed) return;
        var wasSuspended = suspended();
        pageHidden = !!document.hidden;
        if (!wasSuspended && suspended()) pausePlayback();
        else if (wasSuspended && !suspended()) resumePlayback();
    }

    function onPlay(event) {
        if (destroyed) return;
        if (suspended() && event.target && event.target.tagName === 'VIDEO') {
            try { event.target.pause(); } catch (_) { /* 后台不允许网站重新自动播放。 */ }
        } else {
            queueSync();
        }
    }

    function onPageHide() {
        api.pause();
    }

    var api = {
        setBoost: function (enabled) {
            if (destroyed) return snapshot();
            boostRequested = !!enabled && !suspended();
            if (boostRequested) {
                sync();
                startMonitoring();
            } else {
                stopMonitoring();
                restoreAll();
            }
            return snapshot();
        },
        status: function () {
            sync();
            return snapshot();
        },
        pause: function () {
            if (destroyed) return snapshot();
            var wasSuspended = suspended();
            manualPaused = true;
            if (!wasSuspended) pausePlayback();
            return snapshot();
        },
        resume: function () {
            if (destroyed) return snapshot();
            manualPaused = false;
            pageHidden = !!document.hidden;
            resumePlayback();
            return snapshot();
        },
        destroy: function () {
            if (!destroyed) {
                boostRequested = false;
                stopMonitoring();
                restoreAll();
                resumeCandidate = null;
                document.removeEventListener('visibilitychange', onVisibilityChange);
                document.removeEventListener('play', onPlay, true);
                window.removeEventListener('pagehide', onPageHide);
                destroyed = true;
            }
            return snapshot();
        }
    };

    document.addEventListener('visibilitychange', onVisibilityChange);
    document.addEventListener('play', onPlay, true);
    window.addEventListener('pagehide', onPageHide);
    window.LightSwipe = api;
    // 页面完成加载时 APP 可能已经退到后台，不能等待下一次 visibilitychange。
    if (pageHidden) pausePlayback();
}());
