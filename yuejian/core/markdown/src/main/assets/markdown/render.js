/*
 * FoliKeep AI 回答渲染脚本（本地资源，不联网）。
 * 约定：
 *   - 宿主通过 window.YuejianRender(htmlBase64, cssBase64) 注入内容，返回值是内容实际高度（CSS px）。
 *   - 内容以 UTF-8 字节的 Base64 传入，避免任何 JSON / 反斜杠转义问题，中文与 LaTeX 反斜杠原样保留。
 *   - Markdown 解析在 Kotlin 侧完成：公式被输出为 <span class="yj-math" data-display="0|1">TeX</span>，
 *     本脚本只负责把这些占位节点替换成 KaTeX 渲染结果。
 */
(function () {
    'use strict';

    function byId(id) {
        return document.getElementById(id);
    }

    function decodeUtf8(b64) {
        try {
            var raw = window.atob(b64);
            var len = raw.length;
            var bytes = new Uint8Array(len);
            for (var i = 0; i < len; i++) {
                bytes[i] = raw.charCodeAt(i) & 0xff;
            }
            if (typeof TextDecoder === 'function') {
                return new TextDecoder('utf-8').decode(bytes);
            }
            var s = '';
            for (var j = 0; j < len; j++) {
                s += String.fromCharCode(bytes[j]);
            }
            return decodeURIComponent(window.escape(s));
        } catch (e) {
            return '';
        }
    }

    function sanitize(node) {
        if (!node || !node.querySelectorAll) {
            return;
        }
        var bad = node.querySelectorAll('script,iframe,object,embed,link,meta,base,style,form,img,svg,math');
        for (var i = 0; i < bad.length; i++) {
            var n = bad[i];
            if (n.parentNode) {
                n.parentNode.removeChild(n);
            }
        }
    }

    function mathOptions(display) {
        return {
            displayMode: !!display,
            throwOnError: false,
            errorColor: '#c62828',
            strict: 'ignore',
            trust: false,
            output: 'html',
            maxSize: 60,
            maxExpand: 1000
        };
    }

    function paintMath() {
        var nodes = document.querySelectorAll('.yj-math');
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (node.getAttribute('data-yj-done') === '1') {
                continue;
            }
            node.setAttribute('data-yj-done', '1');
            var tex = node.textContent || '';
            // 保留原始 LaTeX 供选区引用；KaTeX 渲染后节点内文本不再是可靠来源
            node.setAttribute('data-tex', tex);
            var display = node.getAttribute('data-display') === '1';
            try {
                node.textContent = '';
                window.katex.render(tex, node, mathOptions(display));
            } catch (e) {
                // 单个公式失败只影响这一处，不影响整条回答
                node.textContent = tex;
            }
        }
    }

    function measure() {
        var root = byId('yj-root');
        if (!root) {
            return 0;
        }
        var rect = root.getBoundingClientRect();
        var h = rect.height;
        if (root.scrollHeight > h) {
            h = root.scrollHeight;
        }
        return Math.ceil(h);
    }

    window.YuejianRender = function (htmlB64, cssB64) {
        try {
            var theme = byId('yj-theme');
            var root = byId('yj-root');
            if (!root) {
                return 0;
            }
            if (cssB64 && theme) {
                theme.textContent = decodeUtf8(cssB64);
            }
            root.innerHTML = decodeUtf8(htmlB64);
            sanitize(root);
            paintMath();
            return measure();
        } catch (e) {
            try {
                var r = byId('yj-root');
                if (r) {
                    r.textContent = '排版失败，请使用下方的“复制原文”查看回答。';
                }
            } catch (ignore) {
            }
            return 0;
        }
    };

    window.YuejianMeasure = function () {
        try {
            return measure();
        } catch (e) {
            return 0;
        }
    };

    /*
     * 选区提取（只服务于宿主“追问这段”）：
     *   - 公式节点整体取 data-tex 原始 LaTeX，跳过 KaTeX 内部节点，避免重复公式与无意义符号；
     *   - 块级元素补换行，让多段选文可读；
     *   - 结果按 UTF-8 的 Base64 返回，规避转义问题；空选区返回空串。
     */
    var BLOCK_TAGS = {
        P: 1, DIV: 1, PRE: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1,
        LI: 1, UL: 1, OL: 1, BLOCKQUOTE: 1, TR: 1, HR: 1, TABLE: 1
    };

    function collectSelection(range, node, out) {
        if (node.nodeType === 1 && node.classList && node.classList.contains('yj-math')) {
            if (range.intersectsNode(node)) {
                var tex = node.getAttribute('data-tex');
                if (tex) {
                    out.push('\\(' + tex + '\\)');
                }
            }
            return;
        }
        if (node.nodeType === 3) {
            if (range.intersectsNode(node)) {
                var text = node.nodeValue || '';
                if (node === range.startContainer && node === range.endContainer) {
                    text = text.substring(range.startOffset, range.endOffset);
                } else if (node === range.startContainer) {
                    text = text.substring(range.startOffset);
                } else if (node === range.endContainer) {
                    text = text.substring(0, range.endOffset);
                }
                if (text) {
                    out.push(text);
                }
            }
            return;
        }
        if (node.nodeType === 1 && node.nodeName === 'BR') {
            if (range.intersectsNode(node)) {
                out.push('\n');
            }
            return;
        }
        if (node.nodeType !== 1 && node.nodeType !== 11) {
            return;
        }
        if (!range.intersectsNode(node)) {
            return;
        }
        var isBlock = node.nodeType === 1 && BLOCK_TAGS[node.nodeName] === 1;
        if (isBlock) {
            out.push('\n');
        }
        var child = node.firstChild;
        while (child) {
            collectSelection(range, child, out);
            child = child.nextSibling;
        }
        if (isBlock) {
            out.push('\n');
        }
    }

    function encodeUtf8Base64(str) {
        var utf8 = unescape(encodeURIComponent(str));
        return window.btoa(utf8);
    }

    window.YuejianSelection = function () {
        try {
            var sel = window.getSelection();
            if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
                return '';
            }
            var range = sel.getRangeAt(0);
            var root = byId('yj-root');
            if (!root) {
                return '';
            }
            var node = range.commonAncestorContainer;
            if (!node) {
                return '';
            }
            var inside = node.nodeType === 1 ? root.contains(node) : root.contains(node.parentNode);
            if (!inside) {
                return '';
            }
            var parts = [];
            collectSelection(range, root, parts);
            var text = parts.join('').replace(/\r/g, '').replace(/\n{3,}/g, '\n\n').trim();
            return encodeUtf8Base64(text);
        } catch (e) {
            return '';
        }
    };

    window.YuejianReady = true;
})();
