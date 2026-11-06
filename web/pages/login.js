/**
 * login.js —— 登录页（P01）
 *
 * 交互决策：
 *   · **演示模式是逃生门**：任何网络/验证码问题都不该把用户锁在登录页外，一次点击直达首页，
 *     不校验任何输入，同时把 source.effective 置 demo（顶栏出现演示提示条）。
 *   · 登录失败「抖动 + 内联错误 + Toast」三重反馈：只弹 Toast 用户会漏看，只抖动读屏读不到。
 *   · 学号格式在前端先拦一道（10–12 位数字），省一次 120–320ms 的往返；密码不做任何本地校验
 *     以外的判断（长度/强弱是教务的规则，App 不自作主张）。
 *   · 「记住学号」只持久化学号，密码永远只在内存里活到提交那一刻。
 *   · render 必须同步返回完整骨架 —— 登录页没有异步数据，所以骨架即终态，不闪。
 *
 * 页面内的 form submit / 输入监听由本页自己绑，并在 cleanup 里全部解绑（不是 data-action
 * 能表达的事件类型；其余交互仍走全局委托）。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { setBusy, toast } from '../core/toast.js';
import * as storage from '../core/storage.js';
import { createSheet } from '../components/sheet.js';

/** 教务学号规则（与 demoProvider 的校验保持一致） */
const STUDENT_NO_RE = /^\d{10,12}$/;

/** 连接类失败码：提示校园网而不是让用户以为账号错了 */
const NET_CODES = new Set(['NETWORK', 'CORS', 'TIMEOUT', 'UNAVAILABLE']);

/**
 * 建页面 DOM（render 与 mount 共用的唯一入口）。
 * @param {object} ctx
 * @returns {{el: HTMLElement, refs: object}}
 */
function build(ctx) {
  const state = ctx.store.getState();
  const remembered = String(storage.get('rememberId') || '');

  const noInput = h('input', {
    className: 'p-login__input',
    type: 'text',
    inputmode: 'numeric',
    autocomplete: 'username',
    maxlength: '12',
    placeholder: '10-12 位学号',
    'aria-label': '学号',
    value: state.session.studentNo || remembered,
  });

  const pwdInput = h('input', {
    className: 'p-login__input p-login__input--pwd',
    type: 'password',
    autocomplete: 'current-password',
    placeholder: '教务系统密码',
    'aria-label': '密码',
  });

  const eyeBtn = h('button', {
    className: 'p-login__eye',
    type: 'button',
    'aria-label': '显示密码',
    'aria-pressed': 'false',
    html: icon('eye', 20),
  });

  const rememberBox = h('input', {
    className: 'p-login__checkbox',
    type: 'checkbox',
    checked: state.session.rememberId || Boolean(remembered),
  });

  const errorEl = h('p', { className: 'p-login__error', role: 'alert', hidden: true });
  const submitBtn = h('button', { className: 'c-btn p-login__submit', type: 'submit', text: '登录' });

  const card = h('form', { className: 'p-login__card', novalidate: true }, [
    h('label', { className: 'p-login__field' }, [
      h('span', { className: 'p-login__label', text: '学号' }),
      noInput,
    ]),
    h('label', { className: 'p-login__field' }, [
      h('span', { className: 'p-login__label', text: '密码' }),
      h('span', { className: 'p-login__pwd' }, [pwdInput, eyeBtn]),
    ]),
    h('label', { className: 'p-login__remember' }, [rememberBox, h('span', { text: '记住学号' })]),
    errorEl,
    submitBtn,
  ]);

  const el = h('div', { className: 'page p-login' }, [
    h('div', { className: 'p-login__brand' }, [
      h('img', {
        className: 'p-login__logo',
        src: 'assets/school-badge.png',
        alt: '唐山师范学院校徽',
      }),
      h('h1', { className: 'p-login__name', text: '掌上唐师' }),
      h('p', { className: 'p-login__slogan', text: '唐山师范学院 · 教务数据随身看' }),
    ]),
    card,
    h('p', {
      className: 'p-login__privacy',
      text: '账号密码只用于本次登录，不上传、不保存。',
    }),
  ]);

  return { el, refs: { noInput, pwdInput, eyeBtn, rememberBox, errorEl, submitBtn, card } };
}

export default {
  name: 'login',
  title: '登录',
  tab: null,
  requiresAuth: false,

  /**
   * @param {object} ctx
   * @returns {HTMLElement}
   */
  render(ctx) {
    const { el, refs } = build(ctx);
    el.__view = refs;
    return el;
  },

  /**
   * @param {HTMLElement} el
   * @param {object} ctx
   * @returns {() => void} cleanup
   */
  mount(el, ctx) {
    const view = el.__view;
    const { actions, router } = ctx;

    /**
     * 显示一条内联错误 + 抖动 + Toast。
     * @param {string} message
     */
    const fail = (message) => {
      view.errorEl.textContent = message;
      view.errorEl.hidden = false;
      view.card.classList.remove('is-shaking');
      // 强制回流，保证连续两次失败也能重新触发动画
      void view.card.offsetWidth;
      view.card.classList.add('is-shaking');
      toast(message, 'danger');
    };

    const clearError = () => {
      view.errorEl.hidden = true;
      view.errorEl.textContent = '';
    };

    const onSubmit = async (event) => {
      event.preventDefault();
      if (view.submitBtn.disabled) return;
      clearError();

      const studentNo = String(view.noInput.value || '').trim();
      const password = String(view.pwdInput.value || '');
      if (!STUDENT_NO_RE.test(studentNo)) {
        fail('学号应为 10-12 位数字');
        view.noInput.focus();
        return;
      }
      if (password.length === 0) {
        fail('请输入密码');
        view.pwdInput.focus();
        return;
      }

      setBusy(view.submitBtn, true);
      const result = await actions.login(studentNo, password, {
        rememberId: view.rememberBox.checked,
      });
      setBusy(view.submitBtn, false);

      if (result && result.ok) {
        toast('登录成功', 'success');
        router.go('/home');
        return;
      }
      const code = result && result.error && result.error.code;
      fail((result && result.error && result.error.message) || '登录失败，请稍后重试');
      if (NET_CODES.has(code)) showNetDialog();
    };

    /**
     * 校园网提示弹窗：教务系统基本只在联通校园网内可达。确定 → 系统 Wi-Fi 设置。
     * 只在连接类失败时出现；账号密码错误（AUTH）不会打扰用户。
     */
    function showNetDialog() {
      const bridge = window.NativeBridge;
      const goWifi = h('button', {
        className: 'c-btn p-login__wifi-btn', type: 'button', text: '确定，去连接校园网',
      });
      goWifi.addEventListener('click', () => {
        if (bridge && typeof bridge.openWifiSettings === 'function') bridge.openWifiSettings();
        dialog.close('api');
      });
      dialog.setContent(h('div', { className: 'p-login__wifi' }, [
        h('p', { className: 'p-login__wifi-title', text: '连不上教务系统？' }),
        h('p', {
          className: 'p-login__wifi-text',
          text: '教务系统可能需要在校园网环境下使用。请先连接学校校园网（联通），再返回重试。',
        }),
        goWifi,
      ]));
      dialog.open();
    }
    const dialog = createSheet({ title: '网络提示' });

    const onTogglePwd = () => {
      const show = view.pwdInput.type === 'password';
      view.pwdInput.type = show ? 'text' : 'password';
      view.eyeBtn.innerHTML = icon(show ? 'eyeOff' : 'eye', 20);
      view.eyeBtn.setAttribute('aria-pressed', String(show));
      view.eyeBtn.setAttribute('aria-label', show ? '隐藏密码' : '显示密码');
    };

    const onShakeEnd = () => view.card.classList.remove('is-shaking');

    view.card.addEventListener('submit', onSubmit);
    view.eyeBtn.addEventListener('click', onTogglePwd);
    view.card.addEventListener('animationend', onShakeEnd);

    // 失焦即收起内联错误：用户开始改输入时错误提示不该继续压着
    const onInput = () => clearError();
    view.noInput.addEventListener('input', onInput);
    view.pwdInput.addEventListener('input', onInput);

    return () => {
      view.card.removeEventListener('submit', onSubmit);
      view.eyeBtn.removeEventListener('click', onTogglePwd);
      view.card.removeEventListener('animationend', onShakeEnd);
      view.noInput.removeEventListener('input', onInput);
      view.pwdInput.removeEventListener('input', onInput);
      dialog.destroy();
    };
  },

  unmount() {
    /* 所有 listener 在 cleanup 里解绑，这里无需额外动作 */
  },
};
