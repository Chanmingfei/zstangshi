// probe-chelaile.mjs -- verify the Chelaile bus protocol end to end
// Usage: node scripts/probe-chelaile.mjs

import crypto from 'node:crypto';

const SALT = 'qwihrnbtmj';
const KEY_WX = 'FF32AE65FBFD19414EAAFF6291A54B42';
const KEY_H5 = '422556651C7F7B2B5C266EED06068230';

const BASE = [
  ['s', 'h5'], ['wxs', 'wx_app'], ['sign', '1'], ['h5RealData', '1'],
  ['v', '3.11.28'], ['src', 'weixinapp_cx'], ['ctm_mp', 'mp_wx'], ['vc', '2'],
  ['favoriteGray', '1'], ['gpstype', 'wgs'], ['geo_type', 'wgs'], ['scene', '1256']
];

const UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148';
const REFERER = 'https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html';

function sign(params) {
  const s = params.map(([k, v]) => `"${k}"="${v}"`).join('&') + SALT;
  return crypto.createHash('md5').update(s, 'utf8').digest('hex');
}

function decodeBase64(s) {
  // Android Base64.DEFAULT accepts both alphabets
  let t = s.replace(/\s/g, '');
  if (/[-_]/.test(t)) t = t.replace(/-/g, '+').replace(/_/g, '/');
  const pad = t.length % 4;
  if (pad === 2) t += '==';
  else if (pad === 3) t += '=';
  return Buffer.from(t, 'base64');
}

function aesDecrypt(buf, keyStr, mode = 'ecb') {
  try {
    const key = Buffer.from(keyStr, 'ascii');
    const d = crypto.createDecipheriv(`aes-${key.length * 8}-${mode}`, key, mode === 'cbc' ? Buffer.alloc(16) : null);
    d.setAutoPadding(true);
    return Buffer.concat([d.update(buf), d.final()]).toString('utf8');
  } catch (e) {
    return null;
  }
}

function aesDecryptNoPad(buf, keyStr) {
  try {
    const key = Buffer.from(keyStr, 'ascii');
    const d = crypto.createDecipheriv(`aes-${key.length * 8}-ecb`, key, null);
    d.setAutoPadding(false);
    return Buffer.concat([d.update(buf), d.final()]);
  } catch (e) {
    return null;
  }
}

async function main() {
  const params = [...BASE, ['cityId', '024'], ['localCityId', 'undefined'],
                  ['lat', '39.630'], ['lng', '118.180']];
  const s = sign(params);
  const q = params.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  const url = `https://web.chelaile.net.cn/api/bus/stop!encryptedHomePage.action?${q}&cryptoSign=${s}`;

  console.log('sign =', s);
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, Referer: REFERER, xweb_xhr: '1', Accept: '*/*' }
  });
  const raw = await res.text();
  console.log('status =', res.status, 'len =', raw.length);
  console.log('raw head:', raw.slice(0, 160));

  const m = raw.match(/"encryptResult":"([^"]*)"/);
  if (!m) { console.log('NO encryptResult'); return; }
  const enc = m[1];
  console.log('\nencryptResult len =', enc.length, 'mod4 =', enc.length % 4);

  const buf = decodeBase64(enc);
  console.log('cipher bytes =', buf.length);

  for (const [name, key] of [['WX', KEY_WX], ['H5', KEY_H5]]) {
    for (const mode of ['ecb', 'cbc']) {
      const out = aesDecrypt(buf, key, mode);
      if (out) {
        console.log(`\n[${name}/${mode}] OK len=${out.length}`);
        console.log(out.slice(0, 400));
      } else {
        console.log(`[${name}/${mode}] padding fail`);
      }
    }
    const rawOut = aesDecryptNoPad(buf, key);
    if (rawOut) {
      console.log(`\n[${name}/ecb-nopad] len=${rawOut.length}`);
      console.log('  hex:', rawOut.toString('hex').match(/.{1,32}/g).join(' '));
      console.log('  utf8:', JSON.stringify(rawOut.toString('utf8')));
    }
  }
}

main().catch(e => { console.error('ERR', e); process.exit(1); });