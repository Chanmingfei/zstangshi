// verify-js-aes.mjs -- extract and test the pure-JS AES-256-ECB inside pages/bus.js
// against the real Chelaile ciphertext (expected plaintext known via Node crypto).

import crypto from 'node:crypto';
import fs from 'node:fs';

// Read bus.js and extract the AES section by regex (the functions SBOX through chelaileDecrypt)
const src = fs.readFileSync('Resources/www/pages/bus.js', 'utf8');

// Pull out the pure functions by evaluating them in a sandbox-ish way.
// We reconstruct: SBOX, INV_SBOX, xtime, mul, expandKey256, decryptBlock, chelaileDecrypt.
// Simplest: extract the block between markers.
const startMarker = 'const SBOX =';
const endMarker = '/* ═══════════════ 车来了接口封装';
const a = src.indexOf(startMarker);
const b = src.indexOf(endMarker);
if (a < 0 || b < 0) { console.error('无法定位 AES 代码块'); process.exit(1); }
const aesBlock = src.slice(a, b);

// The block references TextEncoder/TextDecoder/atob which exist in Node 18+.
// It also references KEY_WX/KEY_H5 and chelaileSign etc. — we only need the decrypt path.
// Evaluate by appending exports.
let chelaileDecrypt, expandKey256, decryptBlock;
const sandbox = new Function(`
  const KEY_WX = 'FF32AE65FBFD19414EAAFF6291A54B42';
  const KEY_H5 = '422556651C7F7B2B5C266EED06068230';
  ${aesBlock}
  return { chelaileDecrypt: chelaileDecrypt, expandKey256: expandKey256, decryptBlock: decryptBlock };
`);
const mod = sandbox();
chelaileDecrypt = mod.chelaileDecrypt;

// Known ciphertext + expected plaintext
const enc = '3qqvgHUhhqBAYHJEEYzzVIxqyoMW5scm4WBIJcETwc/Sf8lZvshw1t0XiPBg165G';
const expected = '{"toast":"","nearSts":[],"tag":"","lines":[]}';

const mine = chelaileDecrypt(enc);
console.log('纯 JS 解密结果 =', JSON.stringify(mine));
if (mine === expected) {
  console.log('✅ 纯 JS AES-256-ECB 解密正确');
  process.exit(0);
} else {
  console.log('❌ 纯 JS AES 解密结果与期望不符');
  process.exit(1);
}