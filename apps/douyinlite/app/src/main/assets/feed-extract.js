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
        // 官网卡片的 href 属性挂在容器 div 上；data-aweme-id 是稳定的视频编号。
        var link = card.querySelector('[href*="/video/"]');
        if (link) {
            var href = link.getAttribute('href') || '';
            if (href) return href.indexOf('//') === 0 ? 'https:' + href : href;
        }
        var aweme = card.getAttribute('data-aweme-id');
        if (aweme) return 'https://www.douyin.com/video/' + aweme;
        var image = card.querySelector('img');
        return image ? (image.currentSrc || image.src || '') : '';
    }

    function authorOf(card) {
        var node = card.querySelector('[class*="name" i],[class*="author" i],[class*="nickname" i]');
        var author = text(node);
        if (author) return author;
        // 官网作者行以 @ 开头，常带 “· 日期” 后缀。
        var leaves = card.querySelectorAll('span,div,p');
        for (var i = 0; i < leaves.length; i++) {
            var line = text(leaves[i]);
            if (line.indexOf('@') === 0) {
                var name = line.slice(1).split('·')[0].trim();
                if (name) return name;
            }
        }
        return '';
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
                author: authorOf(card),
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
                var target = items[i].querySelector('[href*="/video/"]') || items[i];
                target.dispatchEvent(new MouseEvent('click', {
                    bubbles: true, cancelable: true, view: window
                }));
                return true;
            }
            return false;
        },
        destroy: function () { destroyed = true; }
    };
})();
