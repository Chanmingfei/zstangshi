// check-yaml.mjs -- lightweight structural validation of project.yml (no deps)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');

function check(file, label) {
  const p = path.join(root, file);
  if (!fs.existsSync(p)) { console.log(`MISSING ${file}`); return false; }
  const t = fs.readFileSync(p, 'utf8');
  const lines = t.split(/\r?\n/);
  const tabs = lines.map((l, i) => (/\t/.test(l) ? i + 1 : null)).filter(Boolean);
  console.log(`\n=== ${label} (${file}) ===`);
  console.log('lines        :', lines.length);
  console.log('tabs         :', tabs.length ? 'FOUND at ' + tabs.join(',') : 'none');
  console.log('utf8 no BOM  :', !t.startsWith('\uFEFF'));
  return { text: t, lines };
}

// ---- project.yml ----
const proj = check('project.yml', 'XcodeGen project');
if (proj) {
  const t = proj.text;
  const checks = [
    ['top-level name',      /^name:\s+\S+/m],
    ['top-level options',   /^options:/m],
    ['top-level settings',  /^settings:/m],
    ['top-level targets',   /^targets:/m],
    ['app target',          /^  ZhangShangTangShi:/m],
    ['widget target',       /^  TodayWidgetExtension:/m],
    ['app PRODUCT_NAME ascii', /PRODUCT_NAME:\s*ZhangShangTangShi/],
    ['widget PRODUCT_NAME ascii', /PRODUCT_NAME:\s*TodayWidget/],
    ['app bundle id',       /PRODUCT_BUNDLE_IDENTIFIER:\s*com\.tstc\.zstangshi\b/],
    ['widget bundle id',    /PRODUCT_BUNDLE_IDENTIFIER:\s*com\.tstc\.zstangshi\.TodayWidget/],
    ['app info plist',      /INFOPLIST_FILE:\s*Resources\/Info\.plist/],
    ['widget info plist',   /INFOPLIST_FILE:\s*Widget\/Info\.plist/],
    ['app sources',         /path:\s*Sources/],
    ['widget sources',      /path:\s*Widget/],
    ['widget embedded',     /embed:\s*true/],
    ['deployment 17.0',     /IPHONEOS_DEPLOYMENT_TARGET:\s*"17\.0"/],
  ];
  let bad = 0;
  for (const [name, re] of checks) {
    const ok = re.test(t);
    if (!ok) bad++;
    console.log(`  ${ok ? 'OK  ' : 'FAIL'} ${name}`);
  }
  // no stray tab-indented content
  if (proj.lines.some(l => /\t/.test(l))) bad++;
  console.log(bad === 0 ? '  => project.yml structure OK' : `  => ${bad} problem(s)`);
}

// ---- workflow ----
const wf = check('.github/workflows/ios-ipa.yml', 'GitHub Actions workflow');
if (wf) {
  const t = wf.text;
  const checks = [
    ['name',            /^name:\s+\S+/m],
    ['workflow_dispatch', /^on:/m],
    ['jobs',            /^jobs:/m],
    ['runs-on macos-26', /runs-on:\s*macos-26/],
    ['xcode-select',    /xcode-select -s/],
    ['SDK 26 guard',    /26\.\*/],
    ['xcodegen',        /xcodegen generate/],
    ['xcodebuild',      /xcodebuild/],
    ['CODE_SIGNING_ALLOWED=NO', /CODE_SIGNING_ALLOWED=NO/],
    ['zip ipa',         /zip -qry/],
    ['upload-artifact', /actions\/upload-artifact/],
    ['appex check',     /PlugIns\/TodayWidget\.appex/],
  ];
  let bad = 0;
  for (const [name, re] of checks) {
    const ok = re.test(t);
    if (!ok) bad++;
    console.log(`  ${ok ? 'OK  ' : 'FAIL'} ${name}`);
  }
  if (wf.lines.some(l => /\t/.test(l))) bad++;
  console.log(bad === 0 ? '  => workflow structure OK' : `  => ${bad} problem(s)`);
}

// ---- Info.plist keys ----
const plist = path.join(root, 'Resources/Info.plist');
if (fs.existsSync(plist)) {
  const t = fs.readFileSync(plist, 'utf8');
  console.log('\n=== Info.plist ===');
  const keys = [
    'CFBundleDisplayName', 'NSLocationWhenInUseUsageDescription',
    'LSApplicationQueriesSchemes', 'NSAppTransportSecurity',
    'NSAllowsArbitraryLoads', 'UISupportedInterfaceOrientations',
    'UILaunchScreen', 'ITSAppUsesNonExemptEncryption',
  ];
  let bad = 0;
  for (const k of keys) {
    const ok = t.includes(`<key>${k}</key>`);
    if (!ok) bad++;
    console.log(`  ${ok ? 'OK  ' : 'FAIL'} ${k}`);
  }
  // schemes
  for (const s of ['alipays', 'upwallet']) {
    const ok = t.includes(`<string>${s}</string>`);
    if (!ok) bad++;
    console.log(`  ${ok ? 'OK  ' : 'FAIL'} scheme ${s}`);
  }
  console.log(bad === 0 ? '  => Info.plist OK' : `  => ${bad} problem(s)`);
}