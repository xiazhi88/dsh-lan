// dsh-lan 的浏览器半：在 DSH 设置里加一个「局域网访问」页签。
//
// 这个文件**是手写的源码，没有构建步骤** —— 页面本身很简单（地址列表 + 状态 + 引导），
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

    const TAILSCALE_DOWNLOAD = 'https://tailscale.com/download';

    const ZH = (typeof navigator !== 'undefined' && String(navigator.language || '').toLowerCase().startsWith('zh'));
    const L = (zh, en) => (ZH ? zh : en);

    /**
     * 端点 404 时给用户看的话。
     *
     * `/__dsh_lan__/info` 是**服务端半**提供的，所以 404 不是「读取出错」，而是
     * 服务端半压根没在跑。裸的 `HTTP 404` 让人完全无从下手 —— 下面两种原因都常见，
     * 而且用户自己能查：
     *   ① 只装了包但没进 profile 的 bundle 层（前端照样加载，服务端没有）
     *   ② 转发端口被占用，服务端放弃了监听
     */
    const NOT_RUNNING = L(
      '服务端半没有运行 —— 这个地址由它提供，只加载前端是不会有它的。'
      + '常见原因：① 插件没进入 profile 的 bundle 层（看 dsh 启动日志里有没有 dsh-lan 那两行）；'
      + '② 转发端口被占用。先重启一次 dsh web 看看。',
      'The host half is not running — this address is served by it, and the client half alone '
      + 'will not provide it. Usual causes: (1) the plugin never entered the profile bundle layer '
      + '(check the boot log for the dsh-lan lines); (2) the forward port was taken. '
      + 'Try restarting dsh web first.',
    );

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
      head: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
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
      link: {
        display: 'inline-block',
        marginTop: 8,
        color: C.brand,
        fontSize: 12,
        textDecoration: 'underline',
      },
      dot: { display: 'inline-block', width: 7, height: 7, borderRadius: '50%', marginRight: 8 },
      badge: {
        flex: '0 0 auto',
        fontSize: 11,
        lineHeight: 1.6,
        padding: '1px 8px',
        borderRadius: 999,
        color: C.ok,
        border: '1px solid ' + C.ok,
        background: 'transparent',
      },
      step: { display: 'flex', gap: 8, marginTop: 8, alignItems: 'flex-start' },
      stepNo: {
        flex: '0 0 auto',
        width: 18,
        height: 18,
        borderRadius: '50%',
        background: C.fill,
        color: C.secondary,
        fontSize: 11,
        lineHeight: '18px',
        textAlign: 'center',
      },
      stepText: { fontSize: 12, color: C.secondary, lineHeight: 1.6 },
      note: {
        marginTop: 10,
        paddingTop: 8,
        borderTop: '1px solid ' + C.border,
        fontSize: 12,
        color: C.tertiary,
        lineHeight: 1.6,
      },
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

    function AddressRow(props) {
      const url = props.url;
      return h('div', { style: styles.row },
        h('span', { style: styles.addr }, url),
        h('button', { style: styles.btn, onClick: () => props.onCopy(url) },
          props.copied === url ? L('已复制', 'Copied') : L('复制', 'Copy')),
      );
    }

    /**
     * 「从外面访问」卡片 —— 两种状态。
     *
     * 检测到 Tailscale 地址（100.64.0.0/10）就报喜并直接给出可填的地址；
     * 没有才给安装步骤。比甩一句「请自行配置内网穿透」有用得多。
     */
    function RemoteCard(props) {
      const tailscale = props.tailscale || [];
      const ready = tailscale.length > 0;

      return h('div', { style: styles.card },
        h('div', { style: styles.head },
          h('div', { style: styles.title }, L('从外面访问', 'Access from anywhere')),
          ready ? h('span', { style: styles.badge }, L('已就绪', 'Ready')) : null,
        ),

        ready
          ? h('div', null,
              h('div', { style: styles.muted },
                L('检测到 Tailscale 地址。手机连上同一个 Tailscale 账号后，在任何网络下都能用这个地址。',
                  'Tailscale detected. Once your phone joins the same tailnet, this address works on any network.')),
              tailscale.map((url) => h(AddressRow, { key: url, url, copied: props.copied, onCopy: props.onCopy })),
            )

          : h('div', null,
              h('div', { style: styles.muted },
                L('本插件只把 DSH 暴露到局域网。要在外面也能用，推荐 Tailscale：它给每台设备一个私有地址（100.x.y.z），设备之间直连，不经过第三方中转，也不用在路由器上开端口。',
                  'This plugin only exposes DSH to your LAN. To reach it from anywhere, use Tailscale: it gives every device a private 100.x.y.z address with direct device-to-device links — no port forwarding, no third-party relay.')),

              h('div', { style: styles.step },
                h('div', { style: styles.stepNo }, '1'),
                h('div', { style: styles.stepText },
                  L('在电脑上安装 Tailscale 并登录', 'Install Tailscale on this computer and sign in')),
              ),
              h('div', { style: styles.step },
                h('div', { style: styles.stepNo }, '2'),
                h('div', { style: styles.stepText },
                  L('手机上装一个，登录同一个账号', 'Install it on your phone and sign in with the same account')),
              ),
              h('div', { style: styles.step },
                h('div', { style: styles.stepNo }, '3'),
                h('div', { style: styles.stepText },
                  L('回到这一页，会多出一个 100.x 地址，填进手机 App 即可',
                    'Come back here — a 100.x address will appear. Use it in your phone app')),
              ),

              h('a', { href: TAILSCALE_DOWNLOAD, target: '_blank', rel: 'noreferrer', style: styles.link },
                L('打开 tailscale.com/download →', 'Open tailscale.com/download →')),

              h('div', { style: styles.note },
                L('为什么不做端口转发：dsh web 没有登录口令，把它直接暴露到公网，等于把这台电脑的任意命令执行权交给扫描器。Tailscale 是设备级的私有网络，比开端口安全得多。',
                  'Why not port-forward: dsh web has no password, so exposing it publicly hands arbitrary command execution on this machine to port scanners. Tailscale is a device-level private network — far safer than opening a port.')),
            ),
      );
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
          .catch((err) => {
            if (!alive) return;
            const msg = String((err && err.message) || err);
            // 404 = 服务端半不在，不是「读取出错」。给一句能行动的话，别甩状态码。
            setError(msg.includes('404') ? NOT_RUNNING : msg);
          });
        return () => { alive = false; };
      }, []);

      const onCopy = (addr) => {
        const ok = copyText(addr);
        setCopied(ok ? addr : '');
        setTimeout(() => setCopied(''), 1600);
      };

      const all = (info && Array.isArray(info.addresses)) ? info.addresses : [];
      const tailscale = (info && Array.isArray(info.tailscale)) ? info.tailscale : [];
      const tsSet = {};
      tailscale.forEach((u) => { tsSet[u] = true; });
      const lan = all.filter((u) => !tsSet[u]);

      return h('div', { style: styles.root },

        h('div', { style: styles.card },
          h('div', { style: styles.title }, L('局域网访问', 'LAN access')),
          h('div', { style: styles.muted },
            L('手机连上下面任意一个地址，就能访问这台电脑上的 DSH。',
              'Open any address below on your phone to reach DSH on this machine.')),
        ),

        // 局域网地址
        h('div', { style: styles.card },
          h('div', { style: styles.title }, L('地址', 'Addresses')),

          error
            ? h('div', { style: { ...styles.muted, color: C.err } },
                L('读取失败：', 'Failed to load: ') + error)
            : null,

          !error && !info
            ? h('div', { style: styles.muted }, L('读取中…', 'Loading…'))
            : null,

          !error && info && all.length === 0
            ? h('div', { style: styles.muted },
                L('没有找到非回环网卡 —— 检查电脑的网络连接。',
                  'No non-loopback interface found — check the network connection.'))
            : null,

          lan.map((url) => h(AddressRow, { key: url, url, copied, onCopy })),
        ),

        // 公网访问（有 Tailscale 就报喜，没有就给安装步骤）
        error ? null : h(RemoteCard, { tailscale, copied, onCopy }),

        // 状态
        h('div', { style: styles.card },
          h('div', { style: styles.title }, L('状态', 'Status')),
          h('div', { style: { ...styles.muted, display: 'flex', alignItems: 'center' } },
            h('span', {
              style: { ...styles.dot, background: (info && !error) ? C.ok : C.err },
            }),
            (info && !error)
              ? L('正在监听 :', 'Listening on :') + String(info.port)
              : (error ? L('服务端半未运行', 'Host half not running') : L('读取中…', 'Loading…')),
          ),
          h('div', { style: styles.muted },
            L('本插件只做转发：把只监听 127.0.0.1 的 dsh web 暴露到局域网，并补上移动端适配。',
              'This plugin only forwards: it exposes the loopback-only dsh web to your LAN, plus mobile adaptation.')),
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
