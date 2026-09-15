// dsh-lan 的浏览器半：在 DSH 设置里加一个「局域网访问」页签。
//
// 这个文件**是手写的源码，没有构建步骤** —— 页面本身很简单（地址列表 + 状态），
// 为它引入 esbuild 和一套打包配置不划算。DSH 的客户端模块系统用 __ModuleLoader__
// 加载插件，包装只有几行；React 由宿主以**模块**形式提供（不是全局变量）。
//
// 数据来源就是本插件自己的自描述端点 /__dsh_lan__/info —— 与页面同源，
// 不需要额外开一条 host RPC 通道。

window.__ModuleLoader__.load({
  id: 'dsh-lan',
  // eslint-disable-next-line no-unused-vars
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var React = require('react');
    var h = React.createElement;
    var useEffect = React.useEffect;
    var useState = React.useState;

    const name = 'dsh-lan';

    /** 只要 slots：用它把页签挂进设置。 */
    const inject = ['slots'];

    const ZH = (typeof navigator !== 'undefined' && String(navigator.language || '').toLowerCase().startsWith('zh'));
    const L = (zh, en) => (ZH ? zh : en);

    /** DSH 的主题 token，都带浅色回退 —— 深浅色下都能看。 */
    const C = {
      primary: 'var(--dsw-alias-label-primary,#111827)',
      secondary: 'var(--dsw-alias-label-secondary,#6b7280)',
      tertiary: 'var(--dsw-alias-label-tertiary,#8b93a1)',
      border: 'var(--dsw-alias-border-l2,#e5e7eb)',
      card: 'var(--dsw-alias-bg-layer-1,#ffffff)',
      fill: 'var(--dsw-alias-bg-layer-2,#f3f4f6)',
      brand: 'var(--dsw-alias-brand-primary,#4f6ef7)',
      ok: 'var(--dsw-alias-state-success-primary,#16a34a)',
      err: 'var(--dsw-alias-state-error-primary,#dc2626)',
    };

    const styles = {
      root: { display: 'flex', flexDirection: 'column', gap: 12 },
      card: {
        border: '1px solid ' + C.border,
        borderRadius: 10,
        background: C.card,
        padding: '12px 14px',
      },
      title: { fontSize: 14, fontWeight: 600, color: C.primary },
      muted: { fontSize: 12, color: C.secondary, lineHeight: 1.6, marginTop: 4 },
      row: {
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 10,
        padding: '8px 0',
        borderTop: '1px solid ' + C.border,
      },
      addr: {
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
        fontSize: 13,
        color: C.primary,
        wordBreak: 'break-all',
      },
      btn: {
        flex: '0 0 auto',
        border: '1px solid ' + C.border,
        background: C.fill,
        color: C.primary,
        borderRadius: 7,
        padding: '4px 10px',
        fontSize: 12,
        cursor: 'pointer',
        fontFamily: 'inherit',
      },
      dot: { display: 'inline-block', width: 7, height: 7, borderRadius: '50%', marginRight: 8 },
    };

    /**
     * 复制到剪贴板。
     *
     * 不能用 navigator.clipboard：局域网入口是 `http://<IP>`，属于**非安全上下文**，
     * 这个 API 在那里根本不存在。退回 textarea + execCommand —— 老但到处能用。
     */
    function copyText(text) {
      try {
        if (navigator.clipboard && window.isSecureContext) {
          navigator.clipboard.writeText(text);
          return true;
        }
      } catch {
        /* 落到下面的兜底 */
      }
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        return ok;
      } catch {
        return false;
      }
    }

    function LanSettings() {
      const [info, setInfo] = useState(null);
      const [error, setError] = useState(null);
      const [copied, setCopied] = useState('');

      useEffect(() => {
        let alive = true;
        fetch('/__dsh_lan__/info', { headers: { accept: 'application/json' } })
          .then((res) => (res.ok ? res.json() : Promise.reject(new Error('HTTP ' + res.status))))
          .then((data) => { if (alive) setInfo(data); })
          .catch((err) => { if (alive) setError(String((err && err.message) || err)); });
        return () => { alive = false; };
      }, []);

      const onCopy = (addr) => {
        const ok = copyText(addr);
        setCopied(ok ? addr : '');
        setTimeout(() => setCopied(''), 1600);
      };

      const addresses = (info && Array.isArray(info.addresses)) ? info.addresses : [];

      return h('div', { style: styles.root },

        h('div', { style: styles.card },
          h('div', { style: styles.title }, L('局域网访问', 'LAN access')),
          h('div', { style: styles.muted },
            L('手机连上下面任意一个地址，就能访问这台电脑上的 DSH。',
              'Open any address below on your phone to reach DSH on this machine.')),
        ),

        // 地址列表
        h('div', { style: styles.card },
          h('div', { style: styles.title }, L('地址', 'Addresses')),

          error
            ? h('div', { style: { ...styles.muted, color: C.err } },
                L('读取失败：', 'Failed to load: ') + error)
            : null,

          !error && !info
            ? h('div', { style: styles.muted }, L('读取中…', 'Loading…'))
            : null,

          !error && info && addresses.length === 0
            ? h('div', { style: styles.muted },
                L('没有找到非回环网卡 —— 检查电脑的网络连接。',
                  'No non-loopback interface found — check the network connection.'))
            : null,

          addresses.map((addr) => h('div', { key: addr, style: styles.row },
            h('span', { style: styles.addr }, addr),
            h('button', { style: styles.btn, onClick: () => onCopy(addr) },
              copied === addr ? L('已复制', 'Copied') : L('复制', 'Copy')),
          )),
        ),

        // 状态
        h('div', { style: styles.card },
          h('div', { style: styles.title }, L('状态', 'Status')),
          h('div', { style: { ...styles.muted, display: 'flex', alignItems: 'center' } },
            h('span', {
              style: {
                ...styles.dot,
                background: (info && !error) ? C.ok : C.err,
              },
            }),
            (info && !error)
              ? L('正在监听 :', 'Listening on :') + String(info.port)
              : L('未在监听', 'Not listening'),
          ),
          h('div', { style: styles.muted },
            L('本插件只做转发：把只监听 127.0.0.1 的 dsh web 暴露到局域网，并补上移动端适配。',
              'This plugin only forwards: it exposes the loopback-only dsh web to your LAN, plus mobile adaptation.')),
          h('div', { style: styles.muted },
            L('手机需要与电脑在同一 WiFi，或同一 Tailscale 网络。',
              'Your phone must share the Wi-Fi or Tailscale network with this machine.')),
        ),
      );
    }

    /**
     * 客户端插件入口。
     * @param {object} ctx 客户端上下文（cordis），本插件只用得到 slots。
     */
    function apply(ctx) {
      // order 2：排在「通用设置」之后，紧挨同类的一级入口
      ctx.slots.inject('settings.section', () =>
        ctx.slots.register(
          {
            name: 'settings.section',
            id: 'dsh-lan',
            order: 2,
            label: () => L('局域网访问', 'LAN access'),
          },
          LanSettings,
        ),
      );
    }

    module.exports = { name, inject, apply };
    return module.exports;
  },
});
