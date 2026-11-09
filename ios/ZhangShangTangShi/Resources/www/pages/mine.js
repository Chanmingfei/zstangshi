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
import { readCustom, parseSemesterId, displayYearOf } from '../data/customTerm.js';
import { createTermSheet, TERM_LABELS } from './mineTermSheet.js';

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

  /**
   * 桌面小组件入口。
   * 放在这里是因为「长按桌面 → 小部件」这条路大部分国产桌面藏得很深，
   * 用户压根不知道有这个功能；点一下让系统弹添加确认框，比写一段说明有用。
   * 没有原生壳（浏览器预览）直接整块不渲染 —— 网页版没有桌面可言。
   */
  const addWidgetBtn = h('button', {
    className: 'c-btn c-btn--ghost p-mine__action', type: 'button', text: '添加到桌面',
  });
  const widgetCard = h('section', { className: 'p-mine__card' }, [
    h('h2', { className: 'p-mine__card-title', text: '桌面小组件' }),
    h('p', { className: 'p-mine__hint', text: '把今天要上的课放上桌面：第几周、几点、在哪上，不用打开 App' }),
    h('div', { className: 'p-mine__actions' }, [addWidgetBtn]),
  ]);
  // 只认「这个壳真的实现了 addWidget」：浏览器预览没有桌面，iOS 壳也没做 WidgetKit，
  // 老版本 APK 同样没有这个方法 —— 三种情况都该整块不渲染，而不是点了报错
  const bridge = typeof window === 'undefined' ? null : window.NativeBridge;
  if (!bridge || typeof bridge.addWidget !== 'function') {
    widgetCard.hidden = true;
  }

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

    widgetCard,

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
    themeButtons, cacheValue, clearBtn, logoutBtn, aboutBtn, linkRow, addWidgetBtn,
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
      const yes = await confirm('清除本地缓存？', '只清除查询结果缓存，不影响账号、主题与本地课表。', {
        confirmText: '清除', danger: true,
      });
      if (!yes) return;
      const result = await provider.clearCache();
      const freed = result && result.data ? Number(result.data.freedBytes) || 0 : 0;
      view.cacheValue.textContent = bytesText(sizeBytes());
      toast(freed > 0 ? `已释放 ${bytesText(freed)}` : '缓存已清除', 'success');
    };

    const onLogout = async () => {
      const yes = await confirm('退出登录？', '会退出教务并清除查询缓存；本地课表保留，不登录也能查看。', {
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

    /* ── 学期与教学周设置弹窗（表单本体在 mineTermSheet.js）── */
    const termSheet = createTermSheet({
      onChange: () => {
        actions.applyTermSettings(); // 重算周数 + 学期码下发 + 课表槽重取
        paint(); // 摘要行立即反映「自定义」，不等下次订阅触发
      },
    });
    const onTermSettings = () => termSheet.open(store.getState().term);
    view.termBtn.addEventListener('click', onTermSettings);

    /**
     * 请系统弹「添加到桌面」确认框。
     * addWidget() 返回的是「请求已发出」，不是「已添加」—— 用户还要在系统弹窗里点确认，
     * 所以提示语写「去桌面确认」，不能写「已添加」。
     */
    const onAddWidget = () => {
      const bridge = window.NativeBridge;
      const result = bridge && typeof bridge.addWidget === 'function' ? String(bridge.addWidget()) : 'unsupported';
      if (result === 'requested') {
        toast('请在桌面弹窗里点「添加」', 'success');
        return;
      }
      toast('这台桌面不支持一键添加：长按桌面空白处 → 小部件 → 掌上唐师', 'info');
    };
    view.addWidgetBtn.addEventListener('click', onAddWidget);

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
      view.addWidgetBtn.removeEventListener('click', onAddWidget);
      termSheet.destroy();
      unsubSession();
      unsubSource();
    };
  },

  unmount() { /* cleanup 在 mount 返回值里统一处理 */ },
};
