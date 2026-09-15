// 二维码生成。
//
// 放在**宿主半**（Node）而不是浏览器端：这样 client/client.js 能继续维持
// 「手写、零依赖、无构建」—— 那个文件的存在意义就是不需要打包器。
// 生成好的 SVG 随 /__dsh_lan__/info 一起下发，前端只管塞进 DOM。

import QR from 'qrcode';

/**
 * 生成一段 SVG 二维码。
 *
 * 失败一律返回 null：二维码是锦上添花，**不能因为它让整个设置页打不开**。
 * 前端拿到 null 就只显示地址文字，功能不受影响。
 *
 * @param {string} text 要编码的内容（这里是入口地址）。
 * @returns {Promise<string|null>} SVG 源码，或 null。
 */
export async function qrSvg(text) {
  try {
    return await QR.toString(String(text), {
      type: 'svg',
      margin: 1,
      width: 240,
      // M 级容错够用，且不会让码变密 —— 屏幕上扫，不是印在纸上。
      errorCorrectionLevel: 'M',
      // 让 SVG 用 currentColor，跟着 DSH 的深浅色主题走
      color: { dark: '#000000ff', light: '#ffffffff' },
    });
  } catch {
    return null;
  }
}

/**
 * 给一组地址批量生成二维码。
 *
 * 上限 [MAX_QRS] 条：每条约 1.3KB，地址多的时候没必要全给 —— 手机扫的是
 * 排在最前面的那个（第一个非回环网卡）。
 */
export async function qrMap(urls) {
  const out = {};
  const list = (urls ?? []).slice(0, MAX_QRS);
  for (const url of list) {
    // eslint-disable-next-line no-await-in-loop -- 条数很少，串行足够且更好读
    const svg = await qrSvg(url);
    if (svg !== null) out[url] = svg;
  }
  return out;
}

const MAX_QRS = 3;
