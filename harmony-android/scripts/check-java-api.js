#!/usr/bin/env node
/**
 * 扫描 dex 里的方法引用，找出「鸿蒙 core-oj.jar 可能没有」的 Java 9+ API。
 *
 * 为什么需要这个：我们用 JDK 17 编译，编译期会匹配到 Java 17 的方法重载
 * （比如 URLEncoder.encode(String, Charset) 是 Java 10 加的），
 * 但真机（尤其是鸿蒙的 core-oj.jar）只有 Java 8 那批。
 * 这种问题编译不报错，一运行就 NoSuchMethodError。
 *
 * 这里做的事：
 *   1. 解析 dex 的 string_ids / type_ids / proto_ids / method_ids
 *   2. 到处所有 method 引用
 *   3. 按「已知的高危 Java 9+ 签名」比对，命中就报出来
 */
const fs = require('fs')

const dexPath = process.argv[2]
const buf = fs.readFileSync(dexPath)

// ── 读 header ──
function u4(o) { return buf.readUInt32LE(o) }
function u2(o) { return buf.readUInt16LE(o) }

if (buf.toString('ascii', 0, 4) !== 'dex\n') {
  console.error('不是 dex 文件')
  process.exit(2)
}

const stringIdsSize = u4(0x38)
const stringIdsOff = u4(0x3C)
const typeIdsSize = u4(0x40)
const typeIdsOff = u4(0x44)
const protoIdsSize = u4(0x48)
const protoIdsOff = u4(0x4C)
const methodIdsSize = u4(0x58)
const methodIdsOff = u4(0x5C)

/** 读 MUTF-8 字符串（dex string_data） */
function readString(idx) {
  const off = u4(stringIdsOff + idx * 4)
  let p = off
  // 跳过 uleb128 长度
  while (buf[p] & 0x80) p++
  p++
  const bytes = []
  while (p < buf.length) {
    const b = buf[p]
    if (b === 0) break
    bytes.push(b)
    p++
  }
  return Buffer.from(bytes).toString('utf8')
}

function readType(idx) {
  if (idx === 0xFFFFFFFF) return null
  const stringIdx = u4(typeIdsOff + idx * 4)
  return readString(stringIdx)
}

function readProto(idx) {
  const base = protoIdsOff + idx * 12
  const retTypeIdx = u4(base + 4)
  const paramsOff = u4(base + 8)
  const ret = readType(retTypeIdx)
  const params = []
  if (paramsOff !== 0) {
    let p = paramsOff
    // uleb128 个数
    let n = 0, shift = 0
    while (true) {
      const b = buf[p++]
      n |= (b & 0x7F) << shift
      if (!(b & 0x80)) break
      shift += 7
    }
    for (let i = 0; i < n; i++) {
      let t = 0; shift = 0
      while (true) {
        const b = buf[p++]
        t |= (b & 0x7F) << shift
        if (!(b & 0x80)) break
        shift += 7
      }
      params.push(readType(t))
    }
  }
  return { ret, params }
}

// ── 收集所有方法引用 ──
const methods = new Set()
for (let i = 0; i < methodIdsSize; i++) {
  const base = methodIdsOff + i * 8
  const classIdx = u2(base)
  const protoIdx = u2(base + 2)
  const nameIdx = u4(base + 4)
  const cls = readType(classIdx)
  const name = readString(nameIdx)
  const p = readProto(protoIdx)
  methods.add(`${cls}->${name}(${p.params.join('')})${p.ret}`)
}

console.log(`dex 方法引用总数：${methods.size}`)

// ── 高危清单：Java 9+ 才有的重载 / 方法 ──
// 这些在 Android 的 android.jar 里查不到（Android 只到 Java 8 那批），
// 在鸿蒙的 core-oj.jar 里同样没有。
const RISKY = [
  // Java 10：URLEncoder/URLDecoder 的 Charset 重载
  { re: /^Ljava\/net\/URLEncoder;->encode\(Ljava\/lang\/String;Ljava\/nio\/charset\/Charset;\)/,
    why: 'URLEncoder.encode(String, Charset) 是 Java 10 加的；只用 String 版' },
  { re: /^Ljava\/net\/URLDecoder;->decode\(Ljava\/lang\/String;Ljava\/nio\/charset\/Charset;\)/,
    why: 'URLDecoder.decode(String, Charset) 是 Java 10 加的；只用 String 版' },
  // Java 11：String 新方法
  { re: /->isBlank\(\)Z$/, why: 'String.isBlank() 是 Java 11' },
  { re: /->strip\(\)Ljava\/lang\/String;$/, why: 'String.strip() 是 Java 11' },
  { re: /->stripLeading\(\)/, why: 'String.stripLeading() 是 Java 11' },
  { re: /->stripTrailing\(\)/, why: 'String.stripTrailing() 是 Java 11' },
  { re: /->repeat\(I\)Ljava\/lang\/String;$/, why: 'String.repeat(int) 是 Java 11' },
  { re: /->lines\(\)Ljava\/util\/stream\/Stream;$/, why: 'String.lines() 是 Java 11' },
  // Java 11：Files / InputStream 读写字符串
  { re: /->readString\(/, why: 'InputStream.readString/readAllBytes 是 Java 9/11' },
  { re: /->writeString\(/, why: 'Files.writeString 是 Java 11' },
  // Java 9：List/Set/Map 工厂
  { re: /^Ljava\/util\/List;->of\(/, why: 'List.of() 是 Java 9；用 Arrays.asList 或 listOf' },
  { re: /^Ljava\/util\/Set;->of\(/, why: 'Set.of() 是 Java 9' },
  { re: /^Ljava\/util\/Map;->of\(/, why: 'Map.of() 是 Java 9' },
  { re: /^Ljava\/util\/Map;->ofEntries\(/, why: 'Map.ofEntries() 是 Java 9' },
  // Java 9：流的增强（限定在 java.util.stream 包里，别误伤 kotlin 的同名扩展函数）
  { re: /^Ljava\/util\/stream\/Stream;->takeWhile\(/, why: 'Stream.takeWhile() 是 Java 9' },
  { re: /^Ljava\/util\/stream\/Stream;->dropWhile\(/, why: 'Stream.dropWhile() 是 Java 9' },
  // 其他
  { re: /^Ljava\/util\/Objects;->requireNonNullElse/, why: 'Objects.requireNonNullElse 是 Java 9' },
  { re: /^Ljava\/util\/Optional;->ifPresentOrElse/, why: 'Optional.ifPresentOrElse 是 Java 9' },
]

const hits = []
for (const m of methods) {
  for (const r of RISKY) {
    if (r.re.test(m)) {
      hits.push({ m, why: r.why })
      break
    }
  }
}

if (hits.length === 0) {
  console.log('\n\x1b[32m✓ 没有发现 Java 9+ 的 API 引用\x1b[0m')
} else {
  console.log(`\n\x1b[31m发现 ${hits.length} 处高危引用：\x1b[0m`)
  for (const h of hits) {
    console.log(`  ${h.m}\n      → ${h.why}`)
  }
  process.exit(1)
}

// 附带：把 URLEncoder / URLDecoder 相关全列出来，方便人肉确认
const enc = [...methods].filter((m) => m.includes('URLEncoder') || m.includes('URLDecoder'))
if (enc.length) {
  console.log('\nURLEncoder / URLDecoder 引用：')
  enc.forEach((m) => console.log('  ' + m))
}