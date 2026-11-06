/**
 * mine.js —— 「我的」页（P12）
 *
 * 这一页的价值不是堆设置项，而是把「数据到底从哪来」讲清楚：
 *   · 正式登录 → 教务系统直连抓取；演示会话 / 抓取失败 → 明确标注降级
 *   · 尚未实测的教务模块如实列出来，不把演示数据装成实时数据
 *
 * 主题三态（跟随系统 / 亮色 / 深色）直接读写 index.html 内联脚本认的那个
 * localStorage 键 `zsts.theme`，并同步 data-theme 与 meta[theme-color]，
 * 保证刷新不白闪、状态栏颜色不脱节。
 *
 * 生命周期：render 同步给骨架，mount 订阅 store 并只改文本/类名（不整页重建，
 * 避免订阅回调把正在点的按钮换掉），返回 cleanup 统一解绑。
 */

import { h } from '../core/dom.js';
import { readThemeChoice, applyTheme } from '../core/theme.js';
import { createSheet } from '../components/sheet.js';
import { toast, confirm } from '../core/toast.js';
import { sizeBytes } from '../core/storage.js';
import { APP_VERSION } from '../appState.js';
import { getSemesters } from '../data/calendar.js';
import {
  readCustom, saveCustom, clearCustom, parseSemesterId, displayYearOf,
} from '../data/customTerm.js';

/** 学工系统与教务处地址：只作为文本展示 + 点击打开，不写成 src/href 远程资源 */
const LINKS = Object.freeze({
  jwc: 'https://jwc.tstc.edu.cn/',
  calendar: 'https://jwc.tstc.edu.cn/xlcx.htm',
  affairs: 'http://sws.tstc.edu.cn/XGTSTC/Phone/#/login',
});

const THEME_OPTIONS = [
  { key: 'system', label: '跟随系统' },
  { key: 'light', label: '亮色' },
  { key: 'dark', label: '深色' },
];

const TERM_LABELS = Object.freeze(['第一学期', '第二学期', '第三学期']);

/* 主题选择/应用与系统跟随监听在 core/theme.js（全生命周期注册，懒加载页面不再漏监听） */

/** 字节数 → 人话 */
function bytesText(bytes) {
  if (!bytes) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

/**
 * 一行「标签 + 值」。
 * @param {string} label
 * @param {HTMLElement} valueEl
 */
function row(label, valueEl) {
  return h('div', { className: 'p-mine__row' }, [
    h('span', { className: 'p-mine__row-label', text: label }),
    valueEl,
  ]);
}

/**
 * 同步建骨架，并把要局部更新的节点挂到 el.__view。
 * @returns {HTMLElement}
 */
function build() {
  const studentNo = h('p', { className: 'p-mine__name', text: '未登录' });
  // 学号弱化为姓名下的第二行（姓名是主体）
  const userNo = h('p', { className: 'p-mine__no', text: '' });
  const sessionTag = h('span', { className: 'c-chip p-mine__chip', text: '—' });
  const heroSub = h('p', { className: 'p-mine__sub', text: '数据源状态读取中' });

  const sourceValue = h('span', { className: 'p-mine__row-value', text: '正方教务系统' });

  const termValue = h('span', { className: 'p-mine__row-value', text: '读取中' });
  const termBtn = h('button', {
    className: 'c-btn c-btn--ghost p-mine__action', type: 'button', text: '学期与教学周设置',
  });

  const themeButtons = THEME_OPTIONS.map((option) => h('button', {
    className: 'p-mine__segment-btn',
    type: 'button',
    text: option.label,
    dataset: { theme: option.key },
    'aria-pressed': 'false',
  }));

  const cacheValue = h('span', { className: 'p-mine__row-value', text: bytesText(sizeBytes()) });
  const clearBtn = h('button', {
    className: 'c-btn c-btn--ghost p-mine__action',
    type: 'button',
    text: '清除本地缓存',
  });
  const logoutBtn = h('button', {
    className: 'c-btn c-btn--danger p-mine__action',
    type: 'button',
    text: '退出登录',
  });

  const shortcuts = [
    { path: '/gpa', label: '学分绩点', hint: '教务权威 GPA' },
    { path: '/calendar', label: '校历', hint: '教学周与考试周' },
    { path: '/recommend', label: '推荐课表', hint: '教务推荐周课表' },
  ].map((item) => h('button', {
    className: 'p-mine__shortcut',
    type: 'button',
    dataset: { path: item.path },
  }, [
    h('span', { className: 'p-mine__shortcut-label', text: item.label }),
    h('span', { className: 'p-mine__shortcut-hint', text: item.hint }),
  ]));

  /** 版本/来源/开发者/外链收进「关于我们」弹窗（样式与登录页校园网提示弹窗同款） */
  const linkRow = h('div', { className: 'p-mine__links p-mine__links--sheet' }, [
    h('button', { className: 'p-mine__link', type: 'button', dataset: { link: 'jwc' }, text: '教务处主页' }),
    h('button', { className: 'p-mine__link', type: 'button', dataset: { link: 'calendar' }, text: '校历原始页面' }),
    h('button', { className: 'p-mine__link', type: 'button', dataset: { link: 'affairs' }, text: '学工系统网页' }),
  ]);
  const aboutBtn = h('button', {
    className: 'c-btn p-mine__about-btn', type: 'button', text: '关于我们',
  });

  const avatar = h('span', { className: 'p-mine__avatar', text: '唐', 'aria-hidden': 'true' });
  const el = h('div', { className: 'page p-mine' }, [
    h('section', { className: 'p-mine__hero' }, [
      h('h1', { className: 'page__title', text: '我的' }),
      h('div', { className: 'p-mine__user' }, [
        avatar,
        h('div', { className: 'p-mine__user-text' }, [studentNo, userNo, heroSub]),
        sessionTag,
      ]),
    ]),

    h('section', { className: 'p-mine__card' }, [
      h('h2', { className: 'p-mine__card-title', text: '数据来源' }),
      row('主数据源', sourceValue),
    ]),

    h('section', { className: 'p-mine__card' }, [
      h('h2', { className: 'p-mine__card-title', text: '外观' }),
      h('div', { className: 'p-mine__segment', role: 'group', 'aria-label': '主题模式' }, themeButtons),
      h('p', { className: 'p-mine__hint', text: '切换后立即生效，刷新不会闪回亮色' }),
    ]),

    h('section', { className: 'p-mine__card' }, [
      h('h2', { className: 'p-mine__card-title', text: '学期与教学周' }),
      row('当前学期', termValue),
      h('div', { className: 'p-mine__actions' }, [termBtn]),
      h('p', { className: 'p-mine__hint', text: '换学期后自选学期与第一周日期，设置保存在 App 内' }),
    ]),

    h('section', { className: 'p-mine__card' }, [
      h('h2', { className: 'p-mine__card-title', text: '常用入口' }),
      h('div', { className: 'p-mine__shortcuts' }, shortcuts),
    ]),

    h('section', { className: 'p-mine__card' }, [
      h('h2', { className: 'p-mine__card-title', text: '本机数据' }),
      row('缓存占用', cacheValue),
      h('div', { className: 'p-mine__actions' }, [clearBtn, logoutBtn]),
      h('p', { className: 'p-mine__hint', text: '账号密码不落盘，缓存只保存查询结果' }),
    ]),

    aboutBtn,
  ]);

  el.__view = {
    studentNo, userNo, avatar, sessionTag, heroSub, sourceValue, termValue, termBtn,
    themeButtons, cacheValue, clearBtn, logoutBtn, aboutBtn, linkRow,
  };
  return el;
}

export default {
  name: 'mine',
  title: '我的',
  tab: 'mine',
  requiresAuth: true,

  render() {
    return build();
  },

  mount(el, ctx) {
    const view = el.__view;
    const { store, actions, provider } = ctx;

    /** 只改文本与类名，不重建 DOM */
    function paint() {
      const state = store.getState();
      const status = state.session.status;
      const isDemo = status === 'demo';
      const degraded = isDemo || state.source.effective === 'demo';

      const no = String(state.session.studentNo || '');
      // 姓名是主体，学号弱化为第二行；抓不到姓名时回退展示学号
      const name = isDemo ? '演示用户' : String(state.session.name || '');
      view.studentNo.textContent = isDemo ? name : (name || (no ? `${no.slice(0, 4)}****${no.slice(-4)}` : '未登录'));
      view.userNo.textContent = (isDemo || !name) ? '' : (no ? `${no.slice(0, 4)}****${no.slice(-4)}` : '');
      // 头像字 = 姓名首字；抓不到姓名（或未登录）回落「唐」
      view.avatar.textContent = isDemo ? '演' : (name ? name.slice(0, 1) : '唐');
      view.sessionTag.textContent = isDemo ? '演示' : status === 'authed' ? '已登录' : '访客';
      view.sessionTag.classList.toggle('is-demo', isDemo);
      view.heroSub.textContent = isDemo
        ? '当前使用演示数据，登录后改为教务实时抓取'
        : '登录状态由教务会话维持';

      // 数据来源固定口径：正方教务系统（用户要求只写这一句，不再展示调试行）
      view.sourceValue.textContent = '正方教务系统';
      view.sourceValue.classList.toggle('is-warn', degraded);

      // 学期摘要：自定义设置优先展示（它才是真正驱动周数/课表的那份数据）
      const term = store.getState().term;
      const custom = readCustom();
      const sid = String((term.context && term.context.semesterId) || '');
      const parsedSid = parseSemesterId(sid);
      const label = parsedSid
        ? `${displayYearOf(parsedSid.academicYear)}学年${TERM_LABELS[parsedSid.term - 1]}`
        : (sid || '—');
      view.termValue.textContent = custom
        ? `${label} · 第1周 ${custom.startDate}（自定义）`
        : `${label}（校历）`;

      const choice = readThemeChoice();
      for (const btn of view.themeButtons) {
        const active = btn.dataset.theme === choice;
        btn.classList.toggle('is-active', active);
        btn.setAttribute('aria-pressed', String(active));
      }
      view.cacheValue.textContent = bytesText(sizeBytes());
    }

    const onTheme = (event) => {
      // 只认主题按钮本身。裸 '[data-theme]' 会一路 closest 到 <html data-theme>，
      // 点卡片任何地方都被当成切主题 —— 用户实测「点哪都提示已切换到亮色」即此因。
      const btn = event.target.closest('button[data-theme]');
      if (!btn || !view.themeButtons.includes(btn)) return;
      const choice = btn.dataset.theme;
      applyTheme(choice);
      store.patch({ app: { ...store.getState().app, theme: document.documentElement.getAttribute('data-theme') } });
      paint();
      toast(choice === 'system' ? '已跟随系统主题' : `已切换到${choice === 'dark' ? '深色' : '亮色'}`, 'success');
    };

    const onShortcut = (event) => {
      const btn = event.target.closest('[data-path]');
      if (btn && btn.dataset.path) window.location.hash = `#${btn.dataset.path}`;
    };

    /** 所有外链统一在应用内 WebView 承载（2026-11-06 口径），无原生壳才退系统浏览器 */
    const openInApp = (url) => {
      const bridge = window.NativeBridge;
      if (bridge && typeof bridge.openWebView === 'function') bridge.openWebView(url);
      else window.open(url, '_blank', 'noopener,noreferrer');
    };

    const onLink = (event) => {
      const btn = event.target.closest('[data-link]');
      if (!btn) return;
      const url = LINKS[btn.dataset.link];
      if (url) openInApp(url);
    };

    const onClear = async () => {
      const yes = await confirm('清除本地缓存？', '只清除查询结果缓存，不影响账号与主题设置。', {
        confirmText: '清除', danger: true,
      });
      if (!yes) return;
      const result = await provider.clearCache();
      const freed = result && result.data ? Number(result.data.freedBytes) || 0 : 0;
      view.cacheValue.textContent = bytesText(sizeBytes());
      toast(freed > 0 ? `已释放 ${bytesText(freed)}` : '缓存已清除', 'success');
    };

    const onLogout = async () => {
      const yes = await confirm('退出登录？', '会清空本次会话与本机缓存，下次需要重新登录教务。', {
        confirmText: '退出', danger: true,
      });
      if (!yes) return;
      await actions.logout();
    };

    /* ── 关于我们弹窗：版本 / 来源 / 开发者 / 外链（样式同登录页校园网提示）── */
    const aboutSheet = createSheet({ title: '关于我们' });
    const onAbout = () => {
      aboutSheet.setContent(h('div', { className: 'p-mine__about' }, [
        h('p', { className: 'p-mine__about-version', text: `掌上唐师 v${APP_VERSION}` }),
        h('p', { className: 'p-mine__about-line', text: '业务数据来自正方教务系统，校历来自教务处' }),
        h('p', { className: 'p-mine__about-line', text: '开发者：陈铭霏' }),
        view.linkRow,
        h('p', { className: 'p-mine__about-foot', text: '百度唐山师范学院贴吧吧务组荣誉出品' }),
      ]));
      aboutSheet.open();
    };
    // 弹窗节点挂在 body（不在页面卡内），data-link 委托要单独绑一份
    view.linkRow.addEventListener('click', onLink);
    view.aboutBtn.addEventListener('click', onAbout);

    /* ── 学期与教学周设置弹窗：学年/学期/第一周周一，保存后全局生效并持久化 ── */
    const termSheet = createSheet({ title: '学期与教学周' });
    const onTermSettings = () => {
      const term = store.getState().term;
      // 预填：优先用户上次的自定义；否则当前生效学期（含其校历开学日）
      const def = readCustom() || (() => {
        const list = getSemesters(term.calendar);
        const id = String((term.context && term.context.semesterId) || '');
        const sem = list.find((s) => s.id === id) || list[0] || null;
        return sem ? { semesterId: sem.id, startDate: sem.semesterStart } : null;
      })() || { semesterId: '2026-2027-1', startDate: '' };
      const parsedDef = parseSemesterId(def.semesterId);

      const nowYear = new Date().getFullYear();
      const base = new Date().getMonth() >= 7 ? nowYear : nowYear - 1; // 8 月起算新学年
      const years = [];
      for (let y = base - 2; y <= base + 1; y += 1) years.push(`${y}-${y + 1}`);

      const yearSel = h('select', { className: 'p-mine__form-field', 'aria-label': '学年' },
        years.map((y) => h('option', { value: y, text: `${y} 学年` })));
      const termSel = h('select', { className: 'p-mine__form-field', 'aria-label': '学期' },
        TERM_LABELS.map((label, at) => h('option', { value: String(at + 1), text: label })));
      const dateInput = h('input', {
        className: 'p-mine__form-field', type: 'date', 'aria-label': '第一周周一的日期',
      });
      if (parsedDef) {
        if (!years.includes(parsedDef.academicYear)) {
          yearSel.insertBefore(h('option', { value: parsedDef.academicYear, text: `${parsedDef.academicYear} 学年` }), yearSel.firstChild);
        }
        yearSel.value = parsedDef.academicYear;
        termSel.value = String(parsedDef.term);
      }
      if (def.startDate) dateInput.value = def.startDate;

      const saveBtn = h('button', { className: 'c-btn p-mine__action', type: 'button', text: '保存并生效' });
      const resetBtn = h('button', { className: 'c-btn c-btn--ghost p-mine__action', type: 'button', text: '跟随校历' });
      const onSave = () => {
        const year = yearSel.value;
        const termNo = Number(termSel.value);
        if (!/^\d{4}-\d{4}$/.test(year) || !(termNo >= 1 && termNo <= 3)) {
          toast('请选择学年与学期', 'info'); return;
        }
        if (!dateInput.value) { toast('请选择第一周周一的日期', 'info'); return; }
        const saved = saveCustom({ semesterId: `${year}-${termNo}`, startDate: dateInput.value });
        if (!saved) { toast('保存失败，请检查日期格式', 'error'); return; }
        actions.applyTermSettings(); // 重算周数 + 学期码下发 + 课表槽重取
        termSheet.close('api');
        paint(); // 摘要行立即反映「自定义」，不等下次订阅触发
        toast(`已按第 1 周 ${saved.startDate} 生效，课表与周数已刷新`, 'success');
      };
      const onReset = () => {
        clearCustom();
        actions.applyTermSettings();
        termSheet.close('api');
        paint();
        toast('已恢复跟随校历', 'success');
      };
      saveBtn.addEventListener('click', onSave);
      resetBtn.addEventListener('click', onReset);

      termSheet.setContent(h('div', { className: 'p-mine__form' }, [
        h('span', { className: 'p-mine__form-label', text: '学年' }), yearSel,
        h('span', { className: 'p-mine__form-label', text: '学期' }), termSel,
        h('span', { className: 'p-mine__form-label', text: '第一周周一' }), dateInput,
        h('p', { className: 'p-mine__hint', text: '保存后 App 按此学期与周数显示课表，重启后仍生效；非周一的日期会自动归到那一周的周一。' }),
        h('div', { className: 'p-mine__actions' }, [resetBtn, saveBtn]),
      ]));
      termSheet.open();
    };
    view.termBtn.addEventListener('click', onTermSettings);

    const card = el;
    card.addEventListener('click', onTheme);
    card.addEventListener('click', onShortcut);
    card.addEventListener('click', onLink);
    view.clearBtn.addEventListener('click', onClear);
    view.logoutBtn.addEventListener('click', onLogout);

    const unsubSession = store.subscribe((s) => s.session.status, paint);
    const unsubSource = store.subscribe((s) => s.source.effective, paint);

    paint();

    return () => {
      card.removeEventListener('click', onTheme);
      card.removeEventListener('click', onShortcut);
      card.removeEventListener('click', onLink);
      view.clearBtn.removeEventListener('click', onClear);
      view.logoutBtn.removeEventListener('click', onLogout);
      view.aboutBtn.removeEventListener('click', onAbout);
      view.linkRow.removeEventListener('click', onLink);
      view.termBtn.removeEventListener('click', onTermSettings);
      termSheet.destroy();
      unsubSession();
      unsubSource();
    };
  },

  unmount() { /* cleanup 在 mount 返回值里统一处理 */ },
};
