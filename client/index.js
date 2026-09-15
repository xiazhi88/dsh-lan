// dshgo 的浏览器半：在 DSH 设置里加一个「局域网访问」页签。
//
// 这个文件**是手写的源码，没有构建步骤** —— 页面本身很简单（地址列表 + 状态 + 引导），
// 为它引入 esbuild 和一套打包配置不划算。DSH 的客户端模块系统用 __ModuleLoader__
// 加载插件，包装只有几行；React 由宿主以**模块**形式提供（不是全局变量）。
//
// 数据来源就是本插件自己的自描述端点 /__dshgo__/info —— 与页面同源，
// 不需要额外开一条 host RPC 通道。

window.__ModuleLoader__.load({
  id: 'dshgo',
  // eslint-disable-next-line no-unused-vars
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var React = require('react');
    var h = React.createElement;
    var useEffect = React.useEffect;
    var useState = React.useState;

    const name = 'dshgo';

    /** 只要 slots：用它把页签挂进设置。 */
    const inject = ['slots'];

    const TAILSCALE_DOWNLOAD = 'https://tailscale.com/download';

    /**
     * App 下载地址。
     *
     * 资产名**不带版本号**：GitHub 的 `latest/download/<name>` 只在请求时解析
     * latest，文件名是照字面取的 —— 名字里带版本的话，发下一版当天就 404，
     * 而且不会有人察觉。详见收录指南里那条警告。
     */
    const APP_APK_URL = 'https://github.com/xiazhi88/dshgo/releases/latest/download/dshgo-app.apk';
    const APP_RELEASES_URL = 'https://github.com/xiazhi88/dshgo/releases/latest';
    const APP_VERSION = '3.6.5';

    /**
     * 够宽才显示二维码。
     *
     * 二维码的用处是「把手机带到这一页」—— 站在手机上时它毫无意义，只占地方。
     * 宽度够（电脑 / 平板 / 横屏）才给。
     */
    const SHOW_QR = (typeof window !== 'undefined') && window.innerWidth >= 640;

    /** 已经站在一台 Android 上？决定这一区是「直接下载」还是「先去手机上打开」。 */
    const IS_ANDROID = (typeof navigator !== 'undefined')
      && /android/i.test(String(navigator.userAgent || ''));

    const ZH = (typeof navigator !== 'undefined' && String(navigator.language || '').toLowerCase().startsWith('zh'));
    const L = (zh, en) => (ZH ? zh : en);

    /**
     * 端点 404 时给用户看的话。
     *
     * `/__dshgo__/info` 是**服务端半**提供的，所以 404 不是「读取出错」，而是
     * 服务端半压根没在跑。裸的 `HTTP 404` 让人完全无从下手 —— 下面两种原因都常见，
     * 而且用户自己能查：
     *   ① 只装了包但没进 profile 的 bundle 层（前端照样加载，服务端没有）
     *   ② 转发端口被占用，服务端放弃了监听
     */
    const NOT_RUNNING = L(
      '服务端半没有运行 —— 这个地址由它提供，只加载前端是不会有它的。'
      + '常见原因：① 插件没进入 profile 的 bundle 层（看 dsh 启动日志里有没有 dshgo 那两行）；'
      + '② 转发端口被占用。先重启一次 dsh web 看看。',
      'The host half is not running — this address is served by it, and the client half alone '
      + 'will not provide it. Usual causes: (1) the plugin never entered the profile bundle layer '
      + '(check the boot log for the dshgo lines); (2) the forward port was taken. '
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

    /**
     * 把后端生成的 SVG 缩放成指定边长再塞进 DOM。
     *
     * qrcode 库产出的是固定 240×240 的 `<svg>`，直接放进版面会过大；
     * 这里只改它的 width/height 属性，viewBox 不动 —— 图形本身照旧清晰。
     * SVG 是我们自己生成的，内容可控，不存在注入问题。
     */
    function qrNode(svg, size) {
      const scaled = String(svg)
        .replace(/width="[^"]*"/, 'width="' + size + '"')
        .replace(/height="[^"]*"/, 'height="' + size + '"');
      return h('div', {
        style: {
          flex: '0 0 auto',
          width: size,
          height: size,
          padding: 4,
          background: '#fff',
          borderRadius: 6,
          border: '1px solid ' + C.border,
          lineHeight: 0,
        },
        dangerouslySetInnerHTML: { __html: scaled },
      });
    }

    function AddressRow(props) {
      const url = props.url;
      const svg = props.qr ? props.qr[url] : null;
      return h('div', { style: styles.row },
        h('div', { style: { display: 'flex', alignItems: 'center', gap: 12, minWidth: 0 } },
          SHOW_QR && svg ? qrNode(svg, 92) : null,
          h('span', { style: styles.addr }, url),
        ),
        h('button', { style: styles.btn, onClick: () => props.onCopy(url) },
          props.copied === url ? L('已复制', 'Copied') : L('复制', 'Copy')),
      );
    }

    /**
     * 「手机 App」卡片。
     *
     * 分两种情形，因为用户在哪儿看这一页决定了下一步完全不一样：
     *   · 已经在手机上 → 直接下载装，最顺
     *   · 在电脑上     → 先把局域网地址给他，让他去手机浏览器打开同一页
     */
    function AppCard(props) {
      const lanUrl = (props.lan && props.lan.length > 0) ? props.lan[0] : null;

      return h('div', { style: styles.card },
        h('div', { style: styles.head },
          h('div', { style: styles.title }, L('手机 App', 'Mobile app')),
          h('span', { style: { ...styles.badge, color: C.brand, borderColor: C.brand } },
            'v' + APP_VERSION),
        ),

        h('div', { style: styles.muted },
          L('装这个 App 比用浏览器顺手：输入法不会挡住输入框、能上传附件、会话回答完会推送通知、随时可切换入口地址。',
            'The app is smoother than a browser: the keyboard never covers the input, attachments work, finished turns push a notification, and you can switch the entry address anytime.')),

        // 直接下载 —— 手机上才给按钮，电脑上点了也装不了
        IS_ANDROID
          ? h('a', {
              href: APP_APK_URL,
              style: { ...styles.btn, display: 'inline-block', marginTop: 10, textDecoration: 'none' },
            }, L('下载 APK（约 1.4 MB）', 'Download APK (about 1.4 MB)'))
          : h('div', null,
              h('div', { style: styles.muted },
                L('在手机浏览器里打开下面这个地址，回到同一页就能下载：',
                  'Open this address in your phone browser and come back to this page to download:')),
              lanUrl
                ? h(AddressRow, { url: lanUrl, copied: props.copied, onCopy: props.onCopy })
                : h('div', { style: styles.muted },
                    L('（先把上面的地址填进手机）', '(Use the address above on your phone)')),
            ),

        h('div', { style: styles.muted },
          L('Android 10 及以上。安装时系统会提示「未知来源」—— 允许即可，这个 App 没有上架应用商店。',
            'Android 10 or newer. Android will warn about an unknown source — allow it; the app is not on any store.')),

        h('a', {
          href: APP_RELEASES_URL,
          target: '_blank',
          rel: 'noreferrer',
          style: styles.link,
        }, L('查看全部版本 →', 'All releases →')),
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
              tailscale.map((url) => h(AddressRow, { key: url, url, qr: props.qr, copied: props.copied, onCopy: props.onCopy })),
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
        fetch('/__dshgo__/info', { headers: { accept: 'application/json' } })
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
      const qr = (info && info.qrcodes) ? info.qrcodes : {};
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
          SHOW_QR
            ? h('div', { style: styles.muted },
                L('手机相机扫一下就能打开，不用手敲 IP。', 'Scan with your phone camera — no need to type the IP.'))
            : null,

          error
            ? h('div', { style: { ...styles.muted, color: C.err } },
                L('读取失败：', 'Failed to load: ') + error)
            : null,

          !error && !info
            ? h('div', { style: styles.muted }, L('读取中…', 'Loading…'))
            : null,

          !error && info && all.length === 0 && info.listening === false
            ? h('div', { style: { ...styles.muted, color: C.err } },
                L('转发端口没起来 —— 服务端半在跑，但没能监听任何端口（启动日志里会有 dshgo: 监听失败 那一行）。',
                  'The forward port is not up — the host half runs but could not bind any port (look for the dshgo: line in the boot log).'))
            : null,

          !error && info && all.length === 0 && info.listening !== false
            ? h('div', { style: styles.muted },
                L('没有找到非回环网卡 —— 检查电脑的网络连接。',
                  'No non-loopback interface found — check the network connection.'))
            : null,

          lan.map((url) => h(AddressRow, { key: url, url, qr, copied, onCopy })),
        ),

        // 手机 App 下载
        error ? null : h(AppCard, { lan, qr, copied, onCopy }),

        // 公网访问（有 Tailscale 就报喜，没有就给安装步骤）
        error ? null : h(RemoteCard, { tailscale, copied, onCopy, qr }),

        // 状态
        h('div', { style: styles.card },
          h('div', { style: styles.title }, L('状态', 'Status')),
          h('div', { style: { ...styles.muted, display: 'flex', alignItems: 'center' } },
            h('span', {
              style: { ...styles.dot, background: (info && !error) ? C.ok : C.err },
            }),
            (info && !error)
              ? (info.listening === false
                  ? L('转发端口未监听', 'Forward port not listening')
                  : L('正在监听 :', 'Listening on :') + String(info.port))
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
      // ── 移动端适配（内联自 dsh-web-mobile，MIT）──
      //
      // 用 ctx.plugin 而不是直接调它的 apply：这样它的 inject 会被 cordis 尊重，
      // 依赖没就绪时会等，而不是拿半个上下文去跑。
      //
      // 只挂这一次 —— 这正是内联的意义。之前靠补丁挂别人的插件行，市场热挂载时
      // 会挂第二遍，locale 冲突直接让 dsh web 起不来。
      // order 2：排在「通用设置」之后，紧挨同类的一级入口
      ctx.slots.inject('settings.section', () =>
        ctx.slots.register(
          {
            name: 'settings.section',
            id: 'dshgo',
            order: 2,
            label: () => L('局域网访问', 'LAN access'),
          },
          LanSettings,
        ),
      );

      // ── 移动端适配（内联自 dsh-web-mobile，MIT）──
      //
      // 放在最后挂：万一它出问题，我们自己的设置页签已经注册好了。
      //
      // 用 apply(ctx) 而**不是** ctx.plugin(mobile)：后者会等 inject 里的服务
      // 全部就绪，而这份适配是给新版 DSH 写的 —— 老版本上服务凑不齐就会一直等，
      // 整个客户端卡在「Loading plugins...」（实测在 0.1.1-rc.2 上踩过）。
      // 直接调 apply，缺东西当场抛错、被 catch 住，不会连累启动。
      try {
        const need = ['slots', 'locale'];
        const missing = need.filter((name) => {
          try { return typeof ctx.get !== 'function' || ctx.get(name) === undefined; }
          catch { return true; }
        });
        if (missing.length > 0) {
          console.warn('[dshgo] 跳过移动端适配，本机 DSH 缺少：', missing.join(', '));
        } else {
          const mobile = window.__dshgoVendorMobile(require);
          if (mobile && typeof mobile.apply === 'function') mobile.apply(ctx);
        }
      } catch (err) {
        // 移动端适配挂了不影响设置页签 —— 后者才是我们的主功能
        console.warn('[dshgo] 移动端适配加载失败：', err);
      }
    }

    module.exports = { name, inject, apply };
    return module.exports;
  },
});
