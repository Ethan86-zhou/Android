'use strict';

// 无第三方依赖：检查精选卡片抽取、身份回退、点击分发与官网边界。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/feed-extract.js'), 'utf8');
let checks = 0;

class Element {
    constructor(tag, options = {}) {
        this.tag = tag;
        this.className = options.className || '';
        this.textContent = options.text || '';
        this.href = options.href || '';
        this.src = options.src || '';
        this.currentSrc = options.currentSrc || '';
        this.alt = options.alt || '';
        this.isConnected = options.isConnected !== false;
        this.children = [];
        this.clicks = [];
    }
    querySelector(selector) { return this.all(selector)[0] || null; }
    querySelectorAll(selector) { return this.all(selector); }
    all(selector) {
        const matcher = match(selector);
        const out = [];
        const walk = node => {
            for (const child of node.children) {
                if (matcher(child)) out.push(child);
                walk(child);
            }
        };
        walk(this);
        return out;
    }
    getAttribute(name) { return name === 'alt' ? this.alt : null; }
    dispatchEvent(event) { this.clicks.push(event); return true; }
}

function match(selector) {
    if (selector === 'a[href*="/video/"]') {
        return node => node.tag === 'a' && node.href.includes('/video/');
    }
    if (selector === 'img') return node => node.tag === 'img';
    if (selector === '[class*="title"],[class*="Title"]') {
        return node => /[tT]itle/.test(node.className);
    }
    if (selector === '[class*="name" i],[class*="author" i],[class*="nickname" i]') {
        return node => /name|author|nickname/i.test(node.className);
    }
    if (selector === 'span,div,p') return node => ['span', 'div', 'p'].includes(node.tag);
    throw new Error(`未模拟的选择器: ${selector}`);
}

function card(options = {}) {
    const root = new Element('div', { className: 'discover-video-card-item', isConnected: options.isConnected });
    if (options.href) root.children.push(new Element('a', { href: options.href }));
    if (options.cover || options.alt) {
        root.children.push(new Element('img', { src: options.cover || '', currentSrc: options.currentSrc || '', alt: options.alt || '' }));
    }
    if (options.title) root.children.push(new Element('div', { className: 'video-title', text: options.title }));
    if (options.author) root.children.push(new Element('span', { className: 'author-name', text: options.author }));
    if (options.likes) root.children.push(new Element('span', { className: 'count', text: options.likes }));
    return root;
}

function harness(cards, url = 'https://www.douyin.com/jingxuan?from_nav=1') {
    const location = new URL(url);
    const window = {
        location: { protocol: location.protocol, hostname: location.hostname },
        MouseEvent: class {
            constructor(type, options) { this.type = type; this.options = options; }
        },
    };
    const document = {
        querySelectorAll: selector => {
            assert.equal(selector, '#douyin-right-container .jingxuan-scroll-element .discover-video-card-item');
            return cards;
        },
    };
    const context = vm.createContext({ window, document, MouseEvent: window.MouseEvent });
    vm.runInContext(source, context);
    return window.LightSwipeFeed;
}

// 完整字段抽取
{
    const feed = harness([card({
        href: 'https://www.douyin.com/video/7001', cover: 'https://p3.douyinpic.com/a.jpeg',
        title: '示例视频', author: '作者甲', likes: '1.2万',
    })]);
    const list = feed.list();
    assert.equal(list.ready, true);
    assert.equal(list.cards.length, 1);
    assert.deepEqual(JSON.parse(JSON.stringify(list.cards[0])), {
        id: 'https://www.douyin.com/video/7001', title: '示例视频',
        author: '作者甲', likes: '1.2万', cover: 'https://p3.douyinpic.com/a.jpeg',
    });
    checks += 5;
}

// 无标题节点时回退图片 alt；无锚点时以封面地址作身份
{
    const feed = harness([card({ cover: 'https://p3.douyinpic.com/b.webp', alt: '备用标题' })]);
    const item = feed.list().cards[0];
    assert.equal(item.id, 'https://p3.douyinpic.com/b.webp');
    assert.equal(item.title, '备用标题');
    assert.equal(item.author, '');
    checks += 3;
}

// currentSrc 优先于 src
{
    const feed = harness([card({
        href: 'https://www.douyin.com/video/7002', alt: 'x',
        cover: 'https://p3.douyinpic.com/old.jpeg', currentSrc: 'https://p3.douyinpic.com/new.jpeg',
    })]);
    assert.equal(feed.list().cards[0].cover, 'https://p3.douyinpic.com/new.jpeg');
    checks += 1;
}

// 点赞数只取独立短数字，长句与标题不误判
{
    const feed = harness([card({
        href: 'https://www.douyin.com/video/7003', title: '2024 年回顾很长很长的标题',
        likes: '8.5万',
    })]);
    assert.equal(feed.list().cards[0].likes, '8.5万');
    const noLikes = harness([card({ href: 'https://www.douyin.com/video/7004', title: '只有标题' })]);
    assert.equal(noLikes.list().cards[0].likes, '');
    checks += 2;
}

// 无身份字段的卡片被丢弃；断开连接的卡片被丢弃
{
    const feed = harness([card({}), card({ href: 'https://www.douyin.com/video/7005', isConnected: false })]);
    assert.equal(feed.list().ready, false);
    assert.equal(feed.list().cards.length, 0);
    checks += 2;
}

// open 点击匹配卡片的真实锚点，事件冒泡且可取消
{
    const target = card({ href: 'https://www.douyin.com/video/7006' });
    const other = card({ href: 'https://www.douyin.com/video/7007' });
    const feed = harness([other, target]);
    assert.equal(feed.open('https://www.douyin.com/video/7006'), true);
    assert.equal(target.querySelector('a[href*="/video/"]').clicks.length, 1);
    const event = target.querySelector('a[href*="/video/"]').clicks[0];
    assert.equal(event.type, 'click');
    assert.equal(event.options.bubbles, true);
    assert.equal(event.options.cancelable, true);
    assert.equal(other.querySelector('a[href*="/video/"]').clicks.length, 0);
    assert.equal(feed.open('https://www.douyin.com/video/absent'), false);
    checks += 7;
}

// 非官网页面不抽取也不响应
{
    const feed = harness([card({ href: 'https://www.douyin.com/video/7008' })], 'https://example.com/fake');
    assert.equal(feed.list().ready, false);
    assert.equal(feed.open('https://www.douyin.com/video/7008'), false);
    checks += 2;
}

// destroy 后只读停用
{
    const feed = harness([card({ href: 'https://www.douyin.com/video/7009' })]);
    feed.destroy();
    assert.equal(feed.list().ready, false);
    assert.equal(feed.open('https://www.douyin.com/video/7009'), false);
    checks += 2;
}

// 重复注入替换旧实例，不残留状态
{
    const cards = [card({ href: 'https://www.douyin.com/video/7010' })];
    const location = new URL('https://www.douyin.com/jingxuan');
    const window = { location: { protocol: 'https:', hostname: 'www.douyin.com' }, MouseEvent: class {} };
    const document = { querySelectorAll: () => cards };
    const context = vm.createContext({ window, document, MouseEvent: window.MouseEvent });
    vm.runInContext(source, context);
    const first = window.LightSwipeFeed;
    vm.runInContext(source, context);
    assert.notEqual(window.LightSwipeFeed, first);
    assert.equal(first.list().ready, false);
    assert.equal(window.LightSwipeFeed.list().ready, true);
    checks += 3;
}

console.log(`FeedExtractCheck: ${checks} checks passed`);
