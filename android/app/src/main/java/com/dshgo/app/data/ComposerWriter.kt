package com.dshgo.app.data

/**
 * 往 DSH 的输入框里写东西。
 *
 * ## 为什么不用 SpeechRecognizer，而用系统的识别界面
 *
 * `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` 拉起的是系统/输入法的识别 Activity：
 * 麦克风归它管，所以我们**不需要申请 RECORD_AUDIO**，也不用处理录音生命周期、
 * 音频焦点、识别引擎缺失这些事。中文识别质量取决于用户装的那个引擎 ——
 * 而那是他自己选的，通常比我们内置的好。
 *
 * ## 为什么用 execCommand 而不是改 innerText / textContent
 *
 * DSH 的输入框是 `contenteditable` 的富文本区域，上面挂着编辑器自己的事件监听。
 * 直接改 `textContent` 的话，DOM 变了但编辑器**不知道**，发送按钮仍然是灰的、
 * 回车也没反应 —— 表现为"字进去了但发不出去"。
 *
 * `document.execCommand('insertText')` 走的是浏览器原生的输入路径，会正常派发
 * 事件。它虽然被标记为废弃，但在 WebView 里是**唯一可靠**的这条路。
 */
object ComposerWriter {

    /**
     * 生成把 [text] 写进输入框的 JS。
     *
     * 选择器用 `[contenteditable="true"]` 而不是类名 —— DSH 的类名带哈希前缀
     * （实测是 `uV2eYG_input`），构建之间会变，钉死它等于给自己埋雷。
     *
     * 页面上可能不止一个 contenteditable（比如某些弹层），所以取**可见的最后一个**
     * —— 输入框永远在页面底部。
     */
    fun writeJs(text: String): String {
        val escaped = escapeForJs(text)
        return """
        (function(){
          try {
            var all = Array.prototype.slice.call(
              document.querySelectorAll('[contenteditable="true"]')
            ).filter(function(el){
              var r = el.getBoundingClientRect();
              return r.width > 8 && r.height > 8;
            });
            if (all.length === 0) return 'no-input';
            var el = all[all.length - 1];
            el.focus();

            var sel = window.getSelection();
            sel.removeAllRanges();
            var range = document.createRange();
            range.selectNodeContents(el);
            sel.addRange(range);

            // 先清空，否则会在原文后面追加 —— 用户要的是"我说的话替换掉草稿"
            document.execCommand('delete', false, null);
            document.execCommand('insertText', false, $escaped);
            return 'ok';
          } catch (e) { return 'error:' + (e && e.message ? e.message : e); }
        })();
        """.trimIndent()
    }

    /** 把文本安全地塞进 JS 字符串字面量（转义引号、反斜杠、换行、U+2028/9）。 */
    private fun escapeForJs(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                '<' -> sb.append("\\u003c")   // 防止意外闭合脚本
                else -> sb.append(c)
            }
        }
        sb.append("'")
        return sb.toString()
    }
}
