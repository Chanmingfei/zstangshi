/**
 * gradeText.js —— 文字成绩归一（五级制 / 两级制）
 *
 * 教务的 `cj` 不一定是数字：考查课、实践课常给「优秀 / 良好 / 中等 / 及格 / 不及格」，
 * 还有「缓考 / 缺考」这种**还没有结果**的状态。原先两者都被当成「未录入」，
 * 于是「微格教学 · 良好」在页面上显示成「未录入」——把已经出分的课说成没出分，
 * 比不显示更糟（学生会以为教务没录，跑去教务处问）。
 *
 * 这个模块只做一件事：把一条成绩判成 **数字分 / 文字成绩 / 还没出结果** 三类，
 * 并给出「是否不及格」「是否算已获学分」。显示（gradeRow、gradeDetail）与统计
 * （grades.summarize）都只认这一份判定，避免三处各判一套、越修越歪。
 *
 * 严守 R11：**不做任何折算**。五级制怎么折绩点由学校定，App 只显示教务给的原文与 `jd`。
 */

/** 「还没有结果」的文字：不是成绩，既不算学分也不算不及格 */
const PENDING_TEXTS = new Set(['缓考', '缺考', '未录入', '待录入', '暂未录入']);

/** 「没通过」的文字：其余非空文字（优秀/良好/中等/及格/合格/通过…）一律视为通过 */
const FAIL_TEXTS = new Set(['不及格', '不合格', '不通过', '未通过', '差']);

/** 判定结果的三种 kind（外部只比较字符串，不 import 常量也不会写错） */
export const GRADE_KIND = Object.freeze({
  SCORE: 'score', // 百分制数字
  TEXT: 'text', // 五级制 / 两级制文字成绩
  PENDING: 'pending', // 缓考 / 缺考 / 未录入
});

/**
 * 把一条成绩判成展示与统计都能直接用的形状。
 *
 * 「有数字就用数字」——即使同时带了文字（95 · 优秀），也以数字为准，文字只作标注。
 *
 * @param {{score?: number|null, scoreText?: string, remark?: string}} grade
 * @returns {{kind: 'score'|'text'|'pending', value: string, failed: boolean, earnsCredit: boolean}}
 */
export function gradeResultOf(grade) {
  const hasNumber = Boolean(grade) && grade.score !== null && grade.score !== undefined;
  if (hasNumber) {
    const failed = Number(grade.score) < 60;
    return {
      kind: GRADE_KIND.SCORE,
      value: String(grade.score),
      failed,
      earnsCredit: !failed,
    };
  }

  const text = String((grade && grade.scoreText) || '').trim();
  if (text && !PENDING_TEXTS.has(text)) {
    const failed = FAIL_TEXTS.has(text);
    return {
      kind: GRADE_KIND.TEXT,
      value: text,
      failed,
      earnsCredit: !failed,
    };
  }

  // 缓考 / 缺考优先显示备注原文：这两个状态措辞必须区分（见 gradeDetail 的说明）
  const remark = String((grade && grade.remark) || '').trim();
  return {
    kind: GRADE_KIND.PENDING,
    value: text || remark || '未录入',
    failed: false,
    earnsCredit: false,
  };
}
