(function () {
    'use strict';

    // 页面适配只改变布局；官网仍负责账号、推荐、路由及播放器。
    if (window.LightSwipeUi && typeof window.LightSwipeUi.destroy === 'function') {
        window.LightSwipeUi.destroy();
    }

    var STYLE_ID = 'light-swipe-clean-style';
    var CLEAN_ATTRIBUTE = 'data-light-swipe-clean';
    var CHANNEL_ATTRIBUTE = 'data-light-swipe-channel';
    var clean = true;
    var destroyed = false;
    var pending = null;
    var observer = null;
    var historyPatches = [];
    var cardGrids = new Set();
    var pendingTarget = null;
    var navigationFallback = null;
    var css = [
        'html[data-light-swipe-clean],html[data-light-swipe-clean] body{min-width:0!important;width:100%!important;margin:0!important;}',
        'html[data-light-swipe-clean] #douyin-navigation,html[data-light-swipe-clean] #douyin-header{display:none!important;}',
        'html[data-light-swipe-clean] #douyin-right-container{width:100%!important;max-width:none!important;min-width:0!important;margin-left:0!important;padding-top:0!important;left:0!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="recommend"] body{overflow:hidden!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="recommend"] #douyin-right-container{height:100vh!important;height:100dvh!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="recommend"] #slidelist.recommend-slidelist{width:100%!important;height:100vh!important;height:100dvh!important;max-height:none!important;min-height:0!important;margin:0!important;padding:0!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="recommend"] #slidelist .page-recommend-container{height:100vh!important;height:100dvh!important;min-height:0!important;margin-bottom:0!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="recommend"] #slidelist .fullscreen_capture_feedback{left:0!important;top:0!important;width:100%!important;height:100%!important;min-height:0!important;padding-left:0!important;padding-right:0!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="recommend"] #slidelist #sliderVideo{width:100%!important;height:100%!important;min-height:0!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="recommend"] #slidelist .playerContainer,html[data-light-swipe-clean][data-light-swipe-channel="recommend"] #slidelist .slider-video,html[data-light-swipe-clean][data-light-swipe-channel="recommend"] #slidelist .xgplayer{width:100%!important;height:100%!important;max-width:none!important;min-height:0!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="recommend"] #slidelist video{object-fit:contain!important;}',
        // 原生播放条位于 WebView 外；只收起完整播放器的桌面控件，不影响精选卡片预览及官网弹窗。
        'html[data-light-swipe-clean] #slidelist.recommend-slidelist .xgplayer xg-controls.xgplayer-controls,html[data-light-swipe-clean] .modal-video-container .xgplayer xg-controls.xgplayer-controls{display:none!important;}',
        'html[data-light-swipe-clean] #slidelist.recommend-slidelist .xgplayer xg-video-container,html[data-light-swipe-clean] .modal-video-container .xgplayer xg-video-container{height:100%!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container .parent-route-container.route-scroll-container{width:100%!important;max-width:none!important;min-width:0!important;height:100vh!important;height:100dvh!important;padding:0!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container .parent-route-container .discover-tab-container{width:100%!important;min-width:0!important;}',
        // 只收起已确认的精选分类条，登录浮层不属于该容器。
        'html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container .parent-route-container .discover-tab-container>.discover-tab-bar,html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container .parent-route-container .discover-tab-container>div:not(.semi-tabs-content){display:none!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container .parent-route-container .discover-tab-container>.semi-tabs-content{width:100%!important;height:100vh!important;height:100dvh!important;padding-top:0!important;min-width:0!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container .jingxuanFeedList,html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container .jingxuan-scroll-element{width:100%!important;min-width:0!important;max-width:none!important;margin:0!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container [data-light-swipe-card-grid]{box-sizing:border-box!important;display:grid!important;grid-template-columns:minmax(0,1fr)!important;width:100%!important;min-width:0!important;max-width:none!important;gap:18px!important;padding:12px 10px!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container [data-light-swipe-card-grid]>.discover-video-card-item{box-sizing:border-box!important;width:100%!important;min-width:0!important;max-width:none!important;}',
        'html[data-light-swipe-clean][data-light-swipe-channel="featured"] #douyin-right-container .jingxuanVideoCard{width:100%!important;min-width:0!important;max-width:none!important;}'
    ].join('\n');

    function official() {
        return window.location.protocol === 'https:' &&
            (window.location.hostname === 'www.douyin.com' || window.location.hostname === 'douyin.com');
    }

    function channel() {
        if (!official()) return null;
        if (/^\/jingxuan(?:\/|$)/.test(window.location.pathname)) return 'featured';
        var recommendTab = document.querySelector('[data-e2e="tab-recommend"],.tab-recommend,#tab-recommend');
        if (recommendTab && (recommendTab.classList.contains('active') ||
                recommendTab.getAttribute('aria-current') === 'page' ||
                recommendTab.getAttribute('aria-selected') === 'true')) return 'recommend';
        if (new URLSearchParams(window.location.search).get('recommend') === '1') return 'recommend';
        return null;
    }

    function removeLayout() {
        var root = document.documentElement;
        if (root) {
            root.removeAttribute(CLEAN_ATTRIBUTE);
            root.removeAttribute(CHANNEL_ATTRIBUTE);
        }
        var style = document.getElementById(STYLE_ID);
        if (style) style.remove();
        cardGrids.forEach(function (grid) { grid.removeAttribute('data-light-swipe-card-grid'); });
        cardGrids.clear();
    }

    function synchronize() {
        if (destroyed) return;
        var root = document.documentElement;
        if (!clean || !official() || !root) {
            removeLayout();
            return;
        }
        fulfillPending();
        var style = document.getElementById(STYLE_ID);
        if (!style) {
            style = document.createElement('style');
            style.id = STYLE_ID;
            style.textContent = css;
            (document.head || root).appendChild(style);
        }
        if (!root.hasAttribute(CLEAN_ATTRIBUTE)) root.setAttribute(CLEAN_ATTRIBUTE, '');
        var current = channel();
        if (current) {
            if (root.getAttribute(CHANNEL_ATTRIBUTE) !== current) root.setAttribute(CHANNEL_ATTRIBUTE, current);
        } else if (root.hasAttribute(CHANNEL_ATTRIBUTE)) {
            root.removeAttribute(CHANNEL_ATTRIBUTE);
        }
        if (current === 'featured') {
            // 使用稳定的语义类识别卡片网格，不依赖官网会变更的散列 class。
            var cards = document.querySelectorAll('#douyin-right-container .jingxuan-scroll-element .discover-video-card-item');
            for (var i = 0; i < cards.length; i++) {
                var grid = cards[i].parentElement;
                if (!grid || !grid.parentElement || !grid.parentElement.classList.contains('jingxuan-scroll-element')) continue;
                if (!grid.hasAttribute('data-light-swipe-card-grid')) grid.setAttribute('data-light-swipe-card-grid', '');
                cardGrids.add(grid);
            }
            cardGrids.forEach(function (grid) {
                if (!grid.isConnected) {
                    grid.removeAttribute('data-light-swipe-card-grid');
                    cardGrids.delete(grid);
                }
            });
        }
    }

    function schedule() {
        // 每批 DOM 更新只安排一次；不轮询、不因自己的样式写入重复循环。
        if (destroyed || pending !== null || document.hidden) return;
        pending = window.setTimeout(function () {
            pending = null;
            synchronize();
        }, 100);
    }

    function status() {
        synchronize();
        var current = destroyed ? null : channel();
        var root = document.documentElement;
        var layout = current === 'recommend' ? document.querySelector('#slidelist.recommend-slidelist') :
            current === 'featured' ? document.querySelector('#douyin-right-container .parent-route-container .discover-tab-container>.semi-tabs-content') : null;
        return {
            channel: current,
            applied: !destroyed && official() && clean && !!root && root.hasAttribute(CLEAN_ATTRIBUTE) && !!document.getElementById(STYLE_ID),
            clean: clean,
            layoutReady: !destroyed && official() && !!layout && !!document.getElementById('douyin-right-container'),
            playerActive: !destroyed && official() && clean && !document.hidden &&
                ((current === 'recommend' && anyVisible('#slidelist.recommend-slidelist')) || anyVisible('.modal-video-container'))
        };
    }

    function findAnchor(target) {
        var label = target === 'featured' ? '精选' : '推荐';
        var anchors = document.querySelectorAll('#douyin-navigation a');
        for (var i = 0; i < anchors.length; i++) {
            var anchor = anchors[i];
            if ((anchor.textContent || '').replace(/\s+/g, '') !== label) continue;
            var destination;
            try { destination = new URL(anchor.href, window.location.href); } catch (error) { continue; }
            if (destination.protocol !== 'https:' ||
                    (destination.hostname !== 'www.douyin.com' && destination.hostname !== 'douyin.com')) continue;
            // 点击保留的官网路由节点，允许 React 自己完成 SPA 切换。
            return anchor;
        }
        return null;
    }

    function clearPending() {
        pendingTarget = null;
        if (navigationFallback !== null) window.clearTimeout(navigationFallback);
        navigationFallback = null;
    }

    function visible(element) {
        if (!element || element.isConnected === false) return false;
        var rect = element.getBoundingClientRect();
        if (!(rect.width > 0 && rect.height > 0)) return false;
        for (var parent = element; parent && parent.nodeType === 1; parent = parent.parentElement) {
            if (parent.hidden) return false;
            var style = window.getComputedStyle(parent);
            if (style.display === 'none' || style.visibility === 'hidden' ||
                    style.visibility === 'collapse' || Number.parseFloat(style.opacity) === 0) return false;
        }
        return true;
    }

    function anyVisible(selector) {
        var elements = document.querySelectorAll(selector);
        for (var i = 0; i < elements.length; i++) {
            if (visible(elements[i])) return true;
        }
        return false;
    }

    function dismissVideo() {
        if (destroyed || !official() || typeof KeyboardEvent !== 'function') return false;
        var modals = document.querySelectorAll('.modal-video-container');
        for (var i = 0; i < modals.length; i++) {
            if (!visible(modals[i])) continue;
            // 精选视频弹层会 replaceState，不能靠 WebView 历史返回；交给官网自己的 Esc 关闭逻辑。
            document.dispatchEvent(new KeyboardEvent('keydown', {
                key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true
            }));
            return true;
        }
        return false;
    }

    function fulfillPending() {
        if (!pendingTarget || !official()) return;
        if (channel() === pendingTarget) { clearPending(); return; }
        var anchor = findAnchor(pendingTarget);
        if (!anchor) return;
        clearPending();
        anchor.click();
        schedule();
    }

    function select(target) {
        if (destroyed || !official() || (target !== 'featured' && target !== 'recommend')) return false;
        dismissVideo();
        clearPending();
        if (channel() === target) return true;
        pendingTarget = target;
        fulfillPending();
        if (pendingTarget) {
            // 首屏 React 节点可能晚于 onPageFinished：等一次挂载机会后才回退 URL。
            navigationFallback = window.setTimeout(function () {
                navigationFallback = null;
                fulfillPending();
                if (!pendingTarget) return;
                var destination = pendingTarget;
                clearPending();
                window.location.assign(destination === 'featured' ?
                    'https://www.douyin.com/jingxuan?from_nav=1' :
                    'https://www.douyin.com/?is_from_mobile_home=1&recommend=1&from_nav=1');
            }, 1200);
        }
        return true;
    }

    function setClean(enabled) {
        if (destroyed) return status();
        clean = !!enabled;
        // 打开账号界面时，原生端已取消频道意图；同时取消尚未挂载的网页意图。
        if (!clean) clearPending();
        synchronize();
        return status();
    }

    function onVisible() {
        if (!document.hidden) schedule();
    }

    function destroy() {
        if (destroyed) return;
        destroyed = true;
        if (pending !== null) window.clearTimeout(pending);
        pending = null;
        clearPending();
        if (observer) observer.disconnect();
        window.removeEventListener('popstate', schedule);
        window.removeEventListener('hashchange', schedule);
        window.removeEventListener('pageshow', schedule);
        document.removeEventListener('visibilitychange', onVisible);
        historyPatches.forEach(function (patch) {
            if (window.history[patch.name] === patch.wrapper) window.history[patch.name] = patch.original;
        });
        removeLayout();
    }

    window.LightSwipeUi = { select: select, setClean: setClean, status: status, dismissVideo: dismissVideo, destroy: destroy };
    // DOM 属性只监听官网会更新的 class/id；本脚本自己的 data 属性不会唤醒观察器。
    if (official()) {
        observer = new MutationObserver(schedule);
        observer.observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'id', 'aria-selected', 'aria-current'] });
        ['pushState', 'replaceState'].forEach(function (name) {
            var original = window.history[name];
            if (typeof original !== 'function') return;
            var wrapper = function () {
                var result = original.apply(this, arguments);
                schedule();
                return result;
            };
            historyPatches.push({ name: name, original: original, wrapper: wrapper });
            window.history[name] = wrapper;
        });
        window.addEventListener('popstate', schedule);
        window.addEventListener('hashchange', schedule);
        window.addEventListener('pageshow', schedule);
        document.addEventListener('visibilitychange', onVisible);
    }
    synchronize();
})();
