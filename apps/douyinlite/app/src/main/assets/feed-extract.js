(function () {
    'use strict';

    // 只读精选卡片的封面与文字，镜像到原生列表；不读取视频地址、账号信息或 Cookie。
    // 点击仍由官网真实卡片节点完成，详情与播放保持官网流程。
    if (window.LightSwipeFeed && typeof window.LightSwipeFeed.destroy === 'function') {
        window.LightSwipeFeed.destroy();
    }

    var destroyed = false;
    var CARD_SELECTOR = '#douyin-right-container .jingxuan-scroll-element .discover-video-card-item';
    var MAX_CARDS = 60;

    function official() {
        return window.location.protocol === 'https:' &&
            (window.location.hostname === 'www.douyin.com' || window.location.hostname === 'douyin.com');
    }

    function text(element) {
        return element ? (element.textContent || '').replace(/\s+/g, ' ').trim() : '';
    }

    function cardId(card) {
        var anchor = card.querySelector('a[href*="/video/"]');
        if (anchor && anchor.href) return anchor.href;
        var image = card.querySelector('img');
        return image ? (image.currentSrc || image.src || '') : '';
    }

    function cards() {
        if (destroyed || !official()) return [];
        var items = document.querySelectorAll(CARD_SELECTOR);
        var out = [];
        for (var i = 0; i < items.length && out.length < MAX_CARDS; i++) {
            var card = items[i];
            if (!card.isConnected) continue;
            var id = cardId(card);
            if (!id) continue;
            var image = card.querySelector('img');
            var titleNode = card.querySelector('[class*="title"],[class*="Title"]');
            var title = text(titleNode);
            if (!title && image) title = (image.getAttribute('alt') || '').trim();
            var authorNode = card.querySelector('[class*="name" i],[class*="author" i],[class*="nickname" i]');
            var likes = '';
            var leaves = card.querySelectorAll('span,div,p');
            for (var j = 0; j < leaves.length; j++) {
                var candidate = text(leaves[j]);
                // 点赞数是独立的短数字文本；避免误取标题或描述中的长句。
                if (/^\d+(\.\d+)?(万|w)?$/.test(candidate)) { likes = candidate; break; }
            }
            out.push({
                id: id,
                title: title,
                author: text(authorNode),
                likes: likes,
                cover: image ? (image.currentSrc || image.src || '') : ''
            });
        }
        return out;
    }

    window.LightSwipeFeed = {
        list: function () {
            var items = cards();
            return { ready: items.length > 0, cards: items };
        },
        open: function (id) {
            if (destroyed || !official() || typeof id !== 'string') return false;
            var items = document.querySelectorAll(CARD_SELECTOR);
            for (var i = 0; i < items.length; i++) {
                if (cardId(items[i]) !== id) continue;
                var anchor = items[i].querySelector('a[href*="/video/"]');
                (anchor || items[i]).dispatchEvent(new MouseEvent('click', {
                    bubbles: true, cancelable: true, view: window
                }));
                return true;
            }
            return false;
        },
        destroy: function () { destroyed = true; }
    };
})();
